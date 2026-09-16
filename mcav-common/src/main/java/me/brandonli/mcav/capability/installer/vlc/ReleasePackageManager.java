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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.http.HttpDownloader;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.Platform;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the VLC downloads for every platform.
 *
 * <p>Windows and macOS builds are fixed releases from videolan.org, listed in the {@code installers/vlc.json}
 * resource together with their hashes. Linux builds are x86 AppImages whose download links change with every
 * release, so they are looked up through the GitHub API. The GitHub lookup only happens on x86 Linux and only when
 * the downloads are actually needed, and it fails soft: if GitHub cannot be reached or answers with something
 * unexpected, VLC is simply reported as unsupported on that machine.
 *
 * <p>The AppImage is run to extract it, so it is only offered when GitHub publishes the SHA-256 digest of the asset
 * (the {@code digest} field of a release asset, such as {@code "sha256:4f3c..."}), and the download is verified
 * against that digest. An AppImage without a digest is refused with a warning instead of being run unverified.
 */
public final class ReleasePackageManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReleasePackageManager.class);
  private static final String X86_64_RELEASE_API =
    "https://api.github.com/repos/ivan-hc/VLC-appimage/releases/tags/continuous-with-plugins";
  private static final String X86_32_RELEASE_API =
    "https://api.github.com/repos/ivan-hc/32-bit-AppImage-packages-database/releases/tags/vlc";
  private static final String APP_IMAGE_SUFFIX = ".AppImage";
  private static final String SHA256_DIGEST_PREFIX = "sha256:";
  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
  private static final Platform LINUX_X86_64 = Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_64);
  private static final Platform LINUX_X86_32 = Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_32);

  private ReleasePackageManager() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Reads the VLC downloads from a JSON resource and adds the Linux AppImage download when running on x86 Linux.
   *
   * @param resourcePath the name of the JSON resource, such as {@code vlc.json}
   * @return the downloads for every supported platform
   */
  public static Download[] readVLCDownloadsFromJsonResource(final String resourcePath) {
    Preconditions.checkNotNull(resourcePath, "Resource path must not be null");
    final Download[] fixed = IOUtils.readDownloadsFromJsonResource(resourcePath);
    final Platform current = Platform.getCurrentPlatform();
    return addAppImageDownload(fixed, current, HttpDownloader::fetchText);
  }

  /**
   * Adds the latest verified VLC AppImage to the fixed downloads when the platform is x86 Linux, the only platform
   * AppImages are published for.
   *
   * @param fixed    the downloads of the resource
   * @param platform the platform to add the download for
   * @param fetcher  fetches the GitHub release descriptions
   * @return the downloads, including the AppImage when one with a SHA-256 digest could be found
   */
  @VisibleForTesting
  static Download[] addAppImageDownload(final Download[] fixed, final Platform platform, final ReleaseFetcher fetcher) {
    final OS operatingSystem = platform.getOS();
    final Arch architecture = platform.getArch();
    final boolean x86Linux = operatingSystem == OS.LINUX && architecture == Arch.X86;
    if (!x86Linux) {
      return fixed;
    }
    final Bits bits = platform.getBits();
    final boolean sixtyFourBit = bits == Bits.BITS_64;
    final String releaseApi = sixtyFourBit ? X86_64_RELEASE_API : X86_32_RELEASE_API;
    final Platform downloadPlatform = sixtyFourBit ? LINUX_X86_64 : LINUX_X86_32;
    final Optional<Download> appImage = findAppImageDownload(releaseApi, downloadPlatform, fetcher);
    if (appImage.isEmpty()) {
      return fixed;
    }
    final Download download = appImage.get();
    final List<Download> fixedList = List.of(fixed);
    final List<Download> downloads = new ArrayList<>(fixedList);
    downloads.add(download);
    return downloads.toArray(Download[]::new);
  }

  private static Optional<Download> findAppImageDownload(final String releaseApi, final Platform platform, final ReleaseFetcher fetcher) {
    final URI uri = URI.create(releaseApi);
    try {
      final Optional<String> body = fetcher.fetch(uri);
      if (body.isEmpty()) {
        return Optional.empty();
      }
      final String json = body.get();
      return findAppImageAsset(json, platform);
    } catch (final IOException | JsonParseException exception) {
      final String reason = exception.getMessage();
      LOGGER.warn("Could not resolve the VLC AppImage from {}: {}", releaseApi, reason);
      return Optional.empty();
    }
  }

  /**
   * Finds the first AppImage among the assets of a GitHub release and turns it into a download that is verified
   * against the SHA-256 digest GitHub computed for the asset.
   *
   * @param json     the JSON of the release
   * @param platform the platform the AppImage is built for
   * @return the download, or empty if the release has no AppImage asset or its AppImage has no SHA-256 digest
   * @throws JsonParseException if the text is not valid JSON
   */
  @VisibleForTesting
  static Optional<Download> findAppImageAsset(final String json, final Platform platform) {
    final JsonArray assets = readAssets(json);
    for (final JsonElement element : assets) {
      final Optional<JsonObject> asset = getAppImageAsset(element);
      if (asset.isPresent()) {
        final JsonObject appImage = asset.get();
        return toVerifiedDownload(appImage, platform);
      }
    }
    return Optional.empty();
  }

  private static JsonArray readAssets(final String json) {
    final JsonElement parsed = JsonParser.parseString(json);
    if (!parsed.isJsonObject()) {
      return new JsonArray();
    }
    final JsonObject release = parsed.getAsJsonObject();
    final JsonElement assetsElement = release.get("assets");
    if (assetsElement == null || !assetsElement.isJsonArray()) {
      return new JsonArray();
    }
    return assetsElement.getAsJsonArray();
  }

  private static Optional<JsonObject> getAppImageAsset(final JsonElement element) {
    if (!element.isJsonObject()) {
      return Optional.empty();
    }
    final JsonObject asset = element.getAsJsonObject();
    final JsonElement nameElement = asset.get("name");
    final JsonElement urlElement = asset.get("browser_download_url");
    final boolean usable = isText(nameElement) && isText(urlElement);
    if (!usable) {
      return Optional.empty();
    }
    final String name = nameElement.getAsString();
    final boolean appImage = name.endsWith(APP_IMAGE_SUFFIX);
    return appImage ? Optional.of(asset) : Optional.empty();
  }

  private static Optional<Download> toVerifiedDownload(final JsonObject asset, final Platform platform) {
    final JsonElement urlElement = asset.get("browser_download_url");
    final String url = urlElement.getAsString();
    final JsonElement digestElement = asset.get("digest");
    final Optional<String> sha256 = readSha256(digestElement);
    if (sha256.isEmpty()) {
      LOGGER.warn("Refusing the VLC AppImage {}: GitHub publishes no SHA-256 digest for it, and unverified code is never run", url);
      return Optional.empty();
    }
    final String hash = sha256.get();
    final Download download = new Download(platform, url, hash);
    return Optional.of(download);
  }

  /**
   * Reads the hexadecimal hash of a {@code sha256:<hex>} digest.
   *
   * @return the hash, or empty if the digest is missing, {@code null}, uses another algorithm, or is malformed
   */
  private static Optional<String> readSha256(final @Nullable JsonElement digestElement) {
    if (digestElement == null || !digestElement.isJsonPrimitive()) {
      return Optional.empty();
    }
    final String digest = digestElement.getAsString();
    final boolean sha256 = digest.startsWith(SHA256_DIGEST_PREFIX);
    if (!sha256) {
      return Optional.empty();
    }
    final int prefixLength = SHA256_DIGEST_PREFIX.length();
    final String hex = digest.substring(prefixLength);
    final Matcher matcher = SHA256_HEX.matcher(hex);
    final boolean valid = matcher.matches();
    return valid ? Optional.of(hex) : Optional.empty();
  }

  private static boolean isText(final @Nullable JsonElement element) {
    return element != null && element.isJsonPrimitive();
  }

  /**
   * Fetches the description of a GitHub release.
   */
  @FunctionalInterface
  interface ReleaseFetcher {
    /**
     * Fetches the release description.
     *
     * @param uri the URI of the release in the GitHub API
     * @return the JSON of the release, or empty if the server answered with an error
     * @throws IOException if the request fails
     */
    Optional<String> fetch(URI uri) throws IOException;
  }
}
