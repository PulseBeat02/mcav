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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.http.HttpDownloader;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class ReleasePackageManagerTest {

  private static final String VLC_DOWNLOADS_RESOURCE = "mcav/common/installers/vlc.json";

  private static List<Download> downloadsFor(final Os operatingSystem, final Arch architecture, final Bits bits) {
    return Arrays.stream(ReleasePackageManager.readVlcDownloadsFromJsonResource(VLC_DOWNLOADS_RESOURCE))
      .filter(download -> download.getPlatform().getOs() == operatingSystem)
      .filter(download -> download.getPlatform().getArch() == architecture)
      .filter(download -> download.getPlatform().getBits() == bits)
      .toList();
  }

  @Test
  void shouldPinTheLinuxAppImageToOneReviewedBuild() {
    final List<Download> linux = downloadsFor(Os.LINUX, Arch.X86, Bits.BITS_64);
    final int linuxCount = linux.size();
    assertEquals(1, linuxCount);

    final Download appImage = linux.getFirst();
    final String url = appImage.getUrl();
    assertEquals(
      "https://github.com/ivan-hc/VLC-appimage/releases/download/20261001-112729/VLC-media-player_3.0.23_2-16-archimage5.0-x86_64.AppImage",
      url
    );
    final Optional<String> hash = appImage.getHash();
    final Optional<String> expected = Optional.of("7f6772a5a0e1d242b233e597a2833d38d0b1d1ec83ccae4e905430dfad754ab9");
    assertEquals(expected, hash);
  }

  @Test
  void shouldResolveTheDownloadsWithoutAskingTheNetwork() {
    try (final MockedStatic<HttpDownloader> downloader = Mockito.mockStatic(HttpDownloader.class)) {
      final Download[] downloads = ReleasePackageManager.readVlcDownloadsFromJsonResource(VLC_DOWNLOADS_RESOURCE);
      assertEquals(6, downloads.length, "Windows on x86-64, x86 and arm64, macOS on x86-64 and arm64, and Linux on x86-64");

      downloader.verifyNoInteractions();
    }
  }

  @Test
  void shouldOfferNoBuildForThirtyTwoBitLinux() {
    // Java 25, which mcav needs, has no port for 32-bit x86 Linux
    final List<Download> downloads = downloadsFor(Os.LINUX, Arch.X86, Bits.BITS_32);
    assertEquals(List.of(), downloads);
  }

  @Test
  void shouldRejectANullResourcePath() {
    assertThrows(NullPointerException.class, () -> ReleasePackageManager.readVlcDownloadsFromJsonResource(null));
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(ReleasePackageManager.class);
  }
}
