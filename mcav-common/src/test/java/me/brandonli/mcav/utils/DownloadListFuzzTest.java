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
package me.brandonli.mcav.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Tag;

// Fuzzes the parser of the download lists the installers ship with: whatever the text, it is refused with an
// IOException or every download it yields keeps the invariants its constructor promises.
@Tag("fuzz")
final class DownloadListFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void shouldParseCompleteDownloadsOrRefuseTheText(final byte[] input) {
    final String text = new String(input, StandardCharsets.UTF_8);
    final Reader reader = new StringReader(text);
    final Download[] downloads;
    try {
      downloads = IoUtils.parseDownloads(reader);
    } catch (final IOException exception) {
      return;
    }

    for (final Download download : downloads) {
      this.checkComplete(download);
    }
  }

  private void checkComplete(final Download download) {
    assertNotNull(download);

    final Platform platform = download.getPlatform();
    assertNotNull(platform);

    final Os os = platform.getOs();
    final Arch architecture = platform.getArch();
    final Bits bits = platform.getBits();
    assertNotNull(os);
    assertNotNull(architecture);
    assertNotNull(bits);

    final String url = download.getUrl();
    assertNotNull(url);

    final boolean blank = url.isBlank();
    assertFalse(blank);
  }
}
