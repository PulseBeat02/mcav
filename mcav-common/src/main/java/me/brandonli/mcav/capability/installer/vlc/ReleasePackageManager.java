/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.brandonli.mcav.capability.installer.vlc;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ObjectArrays;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.UncheckedIOException;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.Platform;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * ReleasePackageManager is responsible for managing the release packages of VLC.
 */
public final class ReleasePackageManager {

  private static final String RELEASE_X86_64_URL =
    "https://api.github.com/repos/ivan-hc/VLC-appimage/releases/tags/continuous-with-plugins";
  private static final String RELEASE_X86_32_URL =
    "https://api.github.com/repos/ivan-hc/32-bit-AppImage-packages-database/releases/tags/vlc";

  /**
   * Initializes the ReleasePackageManager.
   */
  public static void init() {
    // init
  }

  private ReleasePackageManager() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Reads VLC downloads from a JSON resource.
   *
   * @param resourcePath the path to the JSON resource
   * @return an array of Download objects
   */
  public static Download[] readVLCDownloadsFromJsonResource(final String resourcePath) {
    final String installerJson = String.format("installers/%s", resourcePath);
    try (final Reader reader = IOUtils.getResourceAsStreamReader(installerJson)) {
      final Gson gson = new Gson();
      final Type downloadArrayType = new TypeToken<Download[]>() {}.getType();
      final Download[] downloads = requireNonNull(gson.fromJson(reader, downloadArrayType));
      final Download[] add = getDownloads();
      return ObjectArrays.concat(downloads, add, Download.class);
    } catch (final IOException e) {
      throw new UncheckedIOException(e.getMessage(), e);
    }
  }

  private static Download[] getDownloads() {
    final String releasePackage = download(true);
    final String other = download(false);
    if (releasePackage == null || other == null) {
      return new Download[0];
    }
    final Download linux64 = new Download(Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_64), releasePackage, null);
    final Download linux32 = new Download(Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_32), other, null);
    return new Download[] { linux64, linux32 };
  }

  private static @Nullable String download(final boolean continuous) {
    final JsonArray assets = getJsonAssets(continuous);
    if (assets == null) {
      return null;
    }

    String downloadUrl = "";
    final int size = assets.size();
    for (int i = 0; i < size; i++) {
      final JsonElement element = assets.get(i);
      final JsonObject asset = element.getAsJsonObject();
      final JsonElement name = asset.get("name");
      final String assetName = name.getAsString();
      if (assetName.endsWith(".AppImage")) {
        downloadUrl = asset.get("browser_download_url").getAsString();
        break;
      }
    }

    if (downloadUrl.isEmpty()) {
      return null;
    }

    return downloadUrl;
  }

  private static JsonArray getJsonAssets(final boolean continuous) {
    try (final HttpClient client = HttpClient.newHttpClient()) {
      final String apiUrl = continuous ? RELEASE_X86_64_URL : RELEASE_X86_32_URL;
      final URI uri = URI.create(apiUrl);
      final HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
      final HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString();
      final HttpResponse<String> response = client.send(request, bodyHandler);
      final String responseBody = response.body();
      final JsonElement parsed = JsonParser.parseString(responseBody);
      final JsonObject releaseJson = parsed.getAsJsonObject();
      return releaseJson.getAsJsonArray("assets");
    } catch (final IOException e) {
      throw new UncheckedIOException(e.getMessage(), e);
    } catch (final InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new UncheckedIOException(e.getMessage(), e);
    }
  }
}
