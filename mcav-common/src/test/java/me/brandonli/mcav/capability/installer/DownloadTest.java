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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import java.util.Optional;
import me.brandonli.mcav.testing.EqualityAssertions;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Test;

final class DownloadTest {

  private static final Platform LINUX = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);

  private static final String TOOL_URL = "https://example.com/tool";
  private static final String HASH = "abc";

  @Test
  void shouldStorePlatformUrlAndHash() {
    final Download download = Download.of(LINUX, TOOL_URL, HASH);
    final Platform platform = download.getPlatform();
    final String url = download.getUrl();
    final Optional<String> hash = download.getHash();
    final Optional<String> expected = Optional.of(HASH);
    assertEquals(LINUX, platform);
    assertEquals(TOOL_URL, url);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribeTheSameDownloadWithEveryFactory() {
    final Download withoutHash = Download.of(LINUX, TOOL_URL);
    final Download fromPartsWithHash = Download.of(Os.LINUX, Arch.X86, Bits.BITS_64, TOOL_URL, HASH);
    final Download fromParts = Download.of(Os.LINUX, Arch.X86, Bits.BITS_64, TOOL_URL);
    final Optional<String> missingHash = withoutHash.getHash();
    final Platform partsPlatform = fromPartsWithHash.getPlatform();
    final Optional<String> partsHash = fromPartsWithHash.getHash();
    final Optional<String> partsMissingHash = fromParts.getHash();
    final boolean hashMissing = missingHash.isEmpty();
    final Optional<String> expected = Optional.of(HASH);
    final boolean partsHashMissing = partsMissingHash.isEmpty();
    assertTrue(hashMissing);
    assertEquals(LINUX, partsPlatform);
    assertEquals(expected, partsHash);
    assertTrue(partsHashMissing);
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final Platform windows = Platform.ofPlatform(Os.WINDOWS, Arch.X86, Bits.BITS_64);
    final Download download = Download.of(LINUX, TOOL_URL, HASH);
    final Download equalDownload = Download.of(LINUX, TOOL_URL, HASH);
    final Download otherPlatform = Download.of(windows, TOOL_URL, HASH);
    final Download otherUrl = Download.of(LINUX, "https://example.com/other", HASH);
    final Download otherHash = Download.of(LINUX, TOOL_URL, "def");
    final Download withoutHash = Download.of(LINUX, TOOL_URL);
    EqualityAssertions.assertEqualityContract(download, equalDownload, otherPlatform, otherUrl, otherHash, withoutHash);
  }

  @Test
  void shouldHashThePlatformTheUrlAndTheHash() {
    final Download download = Download.of(LINUX, TOOL_URL, HASH);

    final int hashCode = download.hashCode();
    final int expected = Objects.hash(LINUX, TOOL_URL, HASH);
    assertEquals(expected, hashCode);
  }

  @Test
  void shouldPrintThePlatformTheUrlAndTheHash() {
    final Download download = Download.of(LINUX, TOOL_URL, HASH);

    final String text = download.toString();
    final String expected = "Download{platform=%s, url=%s, hash=%s}".formatted(LINUX, TOOL_URL, HASH);
    assertEquals(expected, text);
  }

  @Test
  void shouldRejectInvalidArguments() {
    assertThrows(IllegalArgumentException.class, () -> Download.of(LINUX, " "));
    assertThrows(NullPointerException.class, () -> Download.of(null, TOOL_URL));
    assertThrows(NullPointerException.class, () -> Download.of(LINUX, null));
    assertThrows(NullPointerException.class, () -> Download.of(null, Arch.X86, Bits.BITS_64, TOOL_URL));
    assertThrows(NullPointerException.class, () -> Download.of(Os.LINUX, null, Bits.BITS_64, TOOL_URL));
    assertThrows(NullPointerException.class, () -> Download.of(Os.LINUX, Arch.X86, null, TOOL_URL));
    assertThrows(NullPointerException.class, () -> Download.of(Os.LINUX, Arch.X86, Bits.BITS_64, null));
    assertThrows(IllegalArgumentException.class, () -> Download.of(Os.LINUX, Arch.X86, Bits.BITS_64, " "));
  }
}
