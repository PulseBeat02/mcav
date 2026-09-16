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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.vlc.ReleasePackageManager.ReleaseFetcher;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.http.HttpDownloader;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link ReleasePackageManager} with canned GitHub release descriptions, so the network is never used.
 */
final class ReleasePackageManagerTest {

  private static final Platform WINDOWS_X86_64 = Platform.ofPlatform(OS.WINDOWS, Arch.X86, Bits.BITS_64);
  private static final Platform LINUX_X86_64 = Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_64);
  private static final Platform LINUX_X86_32 = Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_32);
  private static final Platform LINUX_ARM_64 = Platform.ofPlatform(OS.LINUX, Arch.ARM, Bits.BITS_64);
  private static final Platform LINUX_OTHER_64 = Platform.ofPlatform(OS.LINUX, Arch.OTHER, Bits.BITS_64);
  private static final Download WINDOWS_DOWNLOAD = new Download(WINDOWS_X86_64, "https://example.com/vlc.zip");
  private static final String APP_IMAGE_URL = "https://example.com/VLC-3.0.21-x86_64.AppImage";
  private static final String APP_IMAGE_SHA256 = "4f3c9a1b".repeat(8);
  private static final String OTHER_SHA256 = "0123abcd".repeat(8);
  private static final String RELEASE_JSON =
    """
    {"assets": [
      {"name": "VLC.AppImage.zsync", "browser_download_url": "https://example.com/VLC.AppImage.zsync", "digest": "sha256:%s"},
      {"name": "VLC-3.0.21-x86_64.AppImage", "browser_download_url": "%s", "digest": "sha256:%s"},
      {"name": "VLC-later.AppImage", "browser_download_url": "https://example.com/later.AppImage", "digest": "sha256:%s"}
    ]}
    """.formatted(OTHER_SHA256, APP_IMAGE_URL, APP_IMAGE_SHA256, OTHER_SHA256);

  @Test
  void returnsTheFixedDownloadsUnchangedOutsideOfLinux() {
    final Download[] fixed = { WINDOWS_DOWNLOAD };
    final Download[] downloads = ReleasePackageManager.addAppImageDownload(fixed, WINDOWS_X86_64, ReleasePackageManagerTest::failFetch);
    assertSame(fixed, downloads);
  }

  @Test
  void neverAsksGitHubOnLinuxWithoutAnX86Processor() {
    final Download[] fixed = { WINDOWS_DOWNLOAD };
    final Download[] arm = ReleasePackageManager.addAppImageDownload(fixed, LINUX_ARM_64, ReleasePackageManagerTest::failFetch);
    final Download[] other = ReleasePackageManager.addAppImageDownload(fixed, LINUX_OTHER_64, ReleasePackageManagerTest::failFetch);
    assertSame(fixed, arm);
    assertSame(fixed, other);
  }

  @Test
  void addsTheVerifiedSixtyFourBitAppImageOnSixtyFourBitLinux() {
    final Download[] fixed = { WINDOWS_DOWNLOAD };
    final List<URI> requested = new ArrayList<>();
    final ReleaseFetcher fetcher = recordingFetcher(requested);
    final Download[] downloads = ReleasePackageManager.addAppImageDownload(fixed, LINUX_X86_64, fetcher);
    assertEquals(2, downloads.length);
    assertSame(WINDOWS_DOWNLOAD, downloads[0]);

    final Download appImage = downloads[1];
    final Platform appImagePlatform = appImage.getPlatform();
    final String appImageUrl = appImage.getUrl();
    final String appImageHash = appImage.getHash();
    assertEquals(LINUX_X86_64, appImagePlatform);
    assertEquals(APP_IMAGE_URL, appImageUrl);
    assertEquals(APP_IMAGE_SHA256, appImageHash, "the download is verified against the digest GitHub publishes");
    assertRequestedOnce(requested, "ivan-hc/VLC-appimage");
    assertEquals(1, fixed.length);
  }

  @Test
  void addsTheThirtyTwoBitAppImageOnThirtyTwoBitLinux() {
    final Download[] fixed = {};
    final List<URI> requested = new ArrayList<>();
    final ReleaseFetcher fetcher = recordingFetcher(requested);
    final Download[] downloads = ReleasePackageManager.addAppImageDownload(fixed, LINUX_X86_32, fetcher);
    assertEquals(1, downloads.length);
    final Download appImage = downloads[0];
    final Platform appImagePlatform = appImage.getPlatform();
    assertEquals(LINUX_X86_32, appImagePlatform);
    assertRequestedOnce(requested, "32-bit-AppImage-packages-database");
  }

  @Test
  void keepsTheFixedDownloadsWhenGitHubAnswersWithAnError() {
    final Download[] fixed = { WINDOWS_DOWNLOAD };
    final Download[] downloads = ReleasePackageManager.addAppImageDownload(fixed, LINUX_X86_64, _ -> Optional.empty());
    assertArrayEquals(fixed, downloads);
  }

  @Test
  void keepsTheFixedDownloadsWhenGitHubCannotBeReached() {
    final Download[] fixed = { WINDOWS_DOWNLOAD };
    final Download[] downloads = ReleasePackageManager.addAppImageDownload(fixed, LINUX_X86_64, _ -> {
      throw new IOException("offline");
    });
    assertArrayEquals(fixed, downloads);
  }

  @Test
  void keepsTheFixedDownloadsWhenGitHubAnswersWithInvalidJson() {
    final Download[] fixed = { WINDOWS_DOWNLOAD };
    final Download[] downloads = ReleasePackageManager.addAppImageDownload(fixed, LINUX_X86_64, _ -> Optional.of("{ not json"));
    assertArrayEquals(fixed, downloads);
  }

  @Test
  void keepsTheFixedDownloadsWhenTheReleaseHasNoAppImage() {
    final Download[] fixed = { WINDOWS_DOWNLOAD };
    final String release =
      """
      {"assets": [{"name": "VLC.tar.gz", "browser_download_url": "https://example.com/VLC.tar.gz", "digest": "sha256:%s"}]}
      """.formatted(OTHER_SHA256);
    final Download[] downloads = ReleasePackageManager.addAppImageDownload(fixed, LINUX_X86_64, _ -> Optional.of(release));
    assertArrayEquals(fixed, downloads);
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "",
      ", \"digest\": null",
      ", \"digest\": {\"sha256\": \"abc\"}",
      ", \"digest\": \"sha512:0123abcd\"",
      ", \"digest\": \"sha256:not-hexadecimal-at-all\"",
      ", \"digest\": \"sha256:0123abcd\"",
      ", \"digest\": \"4f3c9a1b4f3c9a1b4f3c9a1b4f3c9a1b4f3c9a1b4f3c9a1b4f3c9a1b4f3c9a1b\"",
    }
  )
  void refusesAnAppImageWithoutAUsableSha256Digest(final String digestField) {
    // the AppImage is run to extract it, so an AppImage that cannot be verified is never offered
    final String release =
      """
      {"assets": [{"name": "VLC.AppImage", "browser_download_url": "https://example.com/VLC.AppImage"%s}]}
      """.formatted(digestField);
    final Download[] fixed = { WINDOWS_DOWNLOAD };
    final Download[] downloads = ReleasePackageManager.addAppImageDownload(fixed, LINUX_X86_64, _ -> Optional.of(release));
    assertArrayEquals(fixed, downloads);
  }

  @Test
  void refusesTheReleaseWhenItsFirstAppImageHasNoDigest() {
    final String release =
      """
      {"assets": [
        {"name": "unverified.AppImage", "browser_download_url": "https://example.com/unverified.AppImage"},
        {"name": "verified.AppImage", "browser_download_url": "https://example.com/verified.AppImage", "digest": "sha256:%s"}
      ]}
      """.formatted(APP_IMAGE_SHA256);
    final Optional<Download> download = ReleasePackageManager.findAppImageAsset(release, LINUX_X86_64);
    final boolean refused = download.isEmpty();
    assertTrue(refused, "the published AppImage is refused rather than replaced by another asset");
  }

  @Test
  void acceptsDigestsInUpperCase() {
    final String upperHash = APP_IMAGE_SHA256.toUpperCase(Locale.ROOT);
    final String release =
      """
      {"assets": [{"name": "VLC.AppImage", "browser_download_url": "https://example.com/VLC.AppImage", "digest": "sha256:%s"}]}
      """.formatted(upperHash);
    final Optional<Download> download = ReleasePackageManager.findAppImageAsset(release, LINUX_X86_64);
    final boolean found = download.isPresent();
    assertTrue(found);
    final Download appImage = download.get();
    final String hash = appImage.getHash();
    assertEquals(upperHash, hash);
  }

  @Test
  void findsTheFirstAppImageAsset() {
    final Optional<Download> download = ReleasePackageManager.findAppImageAsset(RELEASE_JSON, LINUX_X86_64);
    final boolean found = download.isPresent();
    assertTrue(found);
    final Download appImage = download.get();
    final String url = appImage.getUrl();
    final String hash = appImage.getHash();
    final Platform platform = appImage.getPlatform();
    assertEquals(APP_IMAGE_URL, url);
    assertEquals(APP_IMAGE_SHA256, hash);
    assertEquals(LINUX_X86_64, platform);
  }

  @Test
  void skipsMalformedAssets() {
    final String release =
      """
      {"assets": [
        "not an object",
        {"browser_download_url": "https://example.com/no-name.AppImage"},
        {"name": "no-url.AppImage"},
        {"name": {"nested": true}, "browser_download_url": "https://example.com/object-name.AppImage"},
        {"name": "array-url.AppImage", "browser_download_url": ["https://example.com/array.AppImage"]},
        {"name": null, "browser_download_url": "https://example.com/null-name.AppImage"},
        {"name": "good.AppImage", "browser_download_url": "https://example.com/good.AppImage", "digest": "sha256:%s"}
      ]}
      """.formatted(APP_IMAGE_SHA256);
    final Optional<Download> download = ReleasePackageManager.findAppImageAsset(release, LINUX_X86_64);
    final boolean found = download.isPresent();
    assertTrue(found);
    final Download appImage = download.get();
    final String url = appImage.getUrl();
    assertEquals("https://example.com/good.AppImage", url);
  }

  @Test
  void findsNothingInReleasesWithoutAssets() {
    assertNoAppImage("[1, 2, 3]");
    assertNoAppImage("{\"name\": \"release\"}");
    assertNoAppImage("{\"assets\": {}}");
    assertNoAppImage("");
  }

  @Test
  void rejectsInvalidJson() {
    assertThrows(JsonParseException.class, () -> ReleasePackageManager.findAppImageAsset("{\"assets\": [", LINUX_X86_64));
  }

  @Test
  void verifiesEveryDownloadOfTheBundledListTogetherWithTheAppImage() {
    final Download[] fixed = IOUtils.readDownloadsFromJsonResource("vlc.json");
    final Download[] downloads = ReleasePackageManager.addAppImageDownload(fixed, LINUX_X86_64, _ -> Optional.of(RELEASE_JSON));
    final boolean hasWindows = containsOperatingSystem(downloads, OS.WINDOWS);
    final boolean hasMac = containsOperatingSystem(downloads, OS.MAC);
    final boolean hasLinux = containsOperatingSystem(downloads, OS.LINUX);
    assertTrue(hasWindows);
    assertTrue(hasMac);
    assertTrue(hasLinux);
    for (final Download download : downloads) {
      final String hash = download.getHash();
      final String url = download.getUrl();
      assertNotNull(hash, "Every VLC download must be verified with a hash: " + url);
    }
  }

  @Test
  void readsTheBundledResourceWithoutTheNetwork() {
    try (final MockedStatic<HttpDownloader> downloader = Mockito.mockStatic(HttpDownloader.class)) {
      downloader.when(() -> HttpDownloader.fetchText(ArgumentMatchers.any(URI.class))).thenReturn(Optional.empty());
      final Download[] downloads = ReleasePackageManager.readVLCDownloadsFromJsonResource("vlc.json");
      final boolean hasWindows = containsOperatingSystem(downloads, OS.WINDOWS);
      final boolean hasMac = containsOperatingSystem(downloads, OS.MAC);
      assertTrue(hasWindows);
      assertTrue(hasMac);
    }
  }

  @Test
  void rejectsANullResourcePath() {
    assertThrows(NullPointerException.class, () -> ReleasePackageManager.readVLCDownloadsFromJsonResource(null));
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(ReleasePackageManager.class);
  }

  private static ReleaseFetcher recordingFetcher(final List<URI> requested) {
    return uri -> {
      requested.add(uri);
      return Optional.of(RELEASE_JSON);
    };
  }

  private static void assertRequestedOnce(final List<URI> requested, final String repository) {
    final int requestCount = requested.size();
    assertEquals(1, requestCount);
    final URI requestedUri = requested.getFirst();
    final String requestedText = requestedUri.toString();
    final boolean asksTheRepository = requestedText.contains(repository);
    assertTrue(asksTheRepository, requestedText);
  }

  private static void assertNoAppImage(final String json) {
    final Optional<Download> download = ReleasePackageManager.findAppImageAsset(json, LINUX_X86_64);
    final boolean nothingFound = download.isEmpty();
    assertTrue(nothingFound, json);
  }

  private static boolean containsOperatingSystem(final Download[] downloads, final OS operatingSystem) {
    for (final Download download : downloads) {
      final Platform platform = download.getPlatform();
      final OS downloadOperatingSystem = platform.getOS();
      if (downloadOperatingSystem == operatingSystem) {
        return true;
      }
    }
    return false;
  }

  private static Optional<String> failFetch(final URI uri) {
    throw new AssertionError("GitHub must not be asked for " + uri);
  }
}
