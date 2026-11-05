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
package me.brandonli.mcav.capability.installer.debian;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Tag;

// Fuzzes the mapping of pinned snapshot URLs to the live Debian pool: no string throws, and a URL it finds starts with
// the live archive, keeps the archive of the pin and ends with the pool path of the pin.
@Tag("fuzz")
final class DebianSourcesFuzzTest {

  private static final String SNAPSHOT_ARCHIVE = "https://snapshot.debian.org/archive/";
  private static final String LIVE_ARCHIVE = "https://deb.debian.org/";

  private static final String POOL_FOLDER = "pool/";
  private static final char PATH_SEPARATOR = '/';

  @FuzzTest(maxDuration = "30s")
  void shouldMapOnlyPinnedPoolFiles(final byte[] input) {
    final String pinnedUrl = new String(input, StandardCharsets.UTF_8);
    final DebianSources sources = DebianSources.ofDebian();

    final Optional<String> liveUrl = sources.findLiveUrl(pinnedUrl);
    liveUrl.ifPresent(url -> this.assertPoolUrl(pinnedUrl, url));
  }

  private void assertPoolUrl(final String pinnedUrl, final String liveUrl) {
    assertTrue(liveUrl.startsWith(LIVE_ARCHIVE), "the live url starts with the live archive");

    final String livePath = liveUrl.substring(LIVE_ARCHIVE.length());
    final int archiveEnd = livePath.indexOf(PATH_SEPARATOR);
    final String archive = livePath.substring(0, archiveEnd + 1);
    final String pool = livePath.substring(archiveEnd + 1);
    assertTrue(pool.startsWith(POOL_FOLDER), "the live url ends with a pool path");
    assertTrue(pinnedUrl.startsWith(SNAPSHOT_ARCHIVE + archive), "the live url keeps the archive of the pin");
    assertTrue(pinnedUrl.endsWith(PATH_SEPARATOR + pool), "the live url ends with the pool path of the pin");
  }
}
