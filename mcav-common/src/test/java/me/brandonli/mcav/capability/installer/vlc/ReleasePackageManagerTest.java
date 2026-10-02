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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.http.HttpDownloader;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link ReleasePackageManager}: every VLC download, the Linux AppImage included, is a pinned build with its hash.
 */
final class ReleasePackageManagerTest {

  private static List<Download> downloadsFor(final OS operatingSystem, final Arch architecture, final Bits bits) {
    return Arrays.stream(ReleasePackageManager.readVLCDownloadsFromJsonResource("vlc.json"))
      .filter(download -> download.getPlatform().getOS() == operatingSystem)
      .filter(download -> download.getPlatform().getArch() == architecture)
      .filter(download -> download.getPlatform().getBits() == bits)
      .toList();
  }

  @Test
  void pinsTheLinuxAppImageToOneReviewedBuild() {
    final List<Download> linux = downloadsFor(OS.LINUX, Arch.X86, Bits.BITS_64);
    assertEquals(1, linux.size());
    final Download appImage = linux.getFirst();
    // a dated release, which never changes, not the weekly tag "continuous" of the publisher's account
    assertEquals(
      "https://github.com/ivan-hc/VLC-appimage/releases/download/20261001-112729/VLC-media-player_3.0.23_2-16-archimage5.0-x86_64.AppImage",
      appImage.getUrl()
    );
    assertEquals("7f6772a5a0e1d242b233e597a2833d38d0b1d1ec83ccae4e905430dfad754ab9", appImage.getHash());
  }

  @Test
  void resolvesTheDownloadsWithoutAskingTheNetwork() {
    try (final MockedStatic<HttpDownloader> downloader = Mockito.mockStatic(HttpDownloader.class)) {
      final Download[] downloads = ReleasePackageManager.readVLCDownloadsFromJsonResource("vlc.json");
      assertEquals(5, downloads.length, "Windows and macOS, two each, and Linux on x86-64");
      downloader.verifyNoInteractions();
    }
  }

  @Test
  void verifiesEveryDownloadWithAHash() {
    for (final Download download : ReleasePackageManager.readVLCDownloadsFromJsonResource("vlc.json")) {
      final String hash = download.getHash();
      assertNotNull(hash, "Every VLC download must be verified with a hash: " + download.getUrl());
      assertTrue(hash.matches("[0-9a-f]{64}"), hash);
    }
  }

  @Test
  void offersNoBuildForThirtyTwoBitLinux() {
    // Java 25, which mcav needs, has no port for 32-bit x86 Linux
    assertEquals(List.of(), downloadsFor(OS.LINUX, Arch.X86, Bits.BITS_32));
  }

  @Test
  void rejectsANullResourcePath() {
    assertThrows(NullPointerException.class, () -> ReleasePackageManager.readVLCDownloadsFromJsonResource(null));
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(ReleasePackageManager.class);
  }
}
