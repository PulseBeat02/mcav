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
package me.brandonli.mcav.capability.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Download}.
 */
final class DownloadTest {

  private static final Platform LINUX = Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_64);

  @Test
  void storesPlatformUrlAndHash() {
    final Download download = new Download(LINUX, "https://example.com/tool", "abc");
    final Platform platform = download.getPlatform();
    final String url = download.getUrl();
    final String hash = download.getHash();
    assertEquals(LINUX, platform);
    assertEquals("https://example.com/tool", url);
    assertEquals("abc", hash);
  }

  @Test
  void everyConstructorDescribesTheSameDownload() {
    final Download withoutHash = new Download(LINUX, "https://example.com/tool");
    final Download fromPartsWithHash = new Download(OS.LINUX, Arch.X86, Bits.BITS_64, "https://example.com/tool", "abc");
    final Download fromParts = new Download(OS.LINUX, Arch.X86, Bits.BITS_64, "https://example.com/tool");
    final String missingHash = withoutHash.getHash();
    final Platform partsPlatform = fromPartsWithHash.getPlatform();
    final String partsHash = fromPartsWithHash.getHash();
    final String partsMissingHash = fromParts.getHash();
    assertNull(missingHash);
    assertEquals(LINUX, partsPlatform);
    assertEquals("abc", partsHash);
    assertNull(partsMissingHash);
  }

  @Test
  void rejectsInvalidArguments() {
    assertThrows(IllegalArgumentException.class, () -> new Download(LINUX, " "));
    assertThrows(NullPointerException.class, () -> new Download(null, "https://example.com/tool"));
    assertThrows(NullPointerException.class, () -> new Download(LINUX, null));
  }
}
