/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.capability.installer.vlc.github;

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

public final class ReleasePackageManager {

  private static final String RELEASE_X86_64_URL =
    "https://api.github.com/repos/ivan-hc/VLC-appimage/releases/tags/continuous-with-plugins";
  private static final String RELEASE_X86_32_URL =
    "https://api.github.com/repos/ivan-hc/32-bit-AppImage-packages-database/releases/tags/vlc";

  public static void init() {
    // init
  }

  private ReleasePackageManager() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static Download[] readVLCDownloadsFromJsonResource(final String resourcePath) {
    final String installerJson = String.format("installers/%s", resourcePath);
    try (final Reader reader = IOUtils.getResourceAsStreamReader(installerJson)) {
      final Gson gson = new Gson();
      final Type downloadArrayType = new TypeToken<Download[]>() {}.getType();
      final Download[] downloads = requireNonNull(gson.fromJson(reader, downloadArrayType));
      final Download[] add = getDownloads();
      return ObjectArrays.concat(downloads, add, Download.class);
    } catch (final IOException e) {
      throw new UncheckedIOException(e.getMessage());
    }
  }

  private static Download[] getDownloads() {
    final ReleasePackage releasePackage = download(true);
    final ReleasePackage other = download(false);
    if (releasePackage == null || other == null) {
      return new Download[0];
    }
    final Download linux64 = new Download(
      Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_64),
      releasePackage.getUrl(),
      releasePackage.getHash()
    );
    final Download linux32 = new Download(Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_32), other.getUrl(), other.getHash());
    return new Download[] { linux64, linux32 };
  }

  private static @Nullable ReleasePackage download(final boolean continuous) {
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

    final String hash = IOUtils.getSHA256Hash(downloadUrl);
    return new ReleasePackage(downloadUrl, hash);
  }

  private static JsonArray getJsonAssets(final boolean continuous) {
    try {
      final String apiUrl = continuous ? RELEASE_X86_64_URL : RELEASE_X86_32_URL;
      final HttpClient client = HttpClient.newHttpClient();
      final URI uri = URI.create(apiUrl);
      final HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
      final HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString();
      final HttpResponse<String> response = client.send(request, bodyHandler);
      final String responseBody = response.body();
      final JsonElement parsed = JsonParser.parseString(responseBody);
      final JsonObject releaseJson = parsed.getAsJsonObject();
      return releaseJson.getAsJsonArray("assets");
    } catch (final IOException e) {
      throw new UncheckedIOException(e.getMessage());
    } catch (final InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new UncheckedIOException(e.getMessage());
    }
  }
}
