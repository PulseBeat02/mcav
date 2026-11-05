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
package me.brandonli.mcav.bukkit.media.mcv2.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class PageCrcFuzzTest {

  private static final long STREAM = 7;

  private static final List<byte[]> FRAMES = List.of(
    Mcv2Trees.tiny(),
    Mcv2Trees.predicted(4, 4, Mcv2Trees.motion(-1, 1)),
    Mcv2Trees.twoPages()
  );

  private static final List<byte[]> PAGES = pages();

  private static List<byte[]> pages() {
    final List<byte[]> pages = new ArrayList<>();
    for (final byte[] frame : FRAMES) {
      pages.addAll(TransportPages.makePages(frame, STREAM));
    }

    return pages;
  }

  @FuzzTest(maxDuration = "30s")
  void shouldAcceptOnlyPagesThatCheck(final byte[] data) {
    if (data.length < 3) {
      return;
    }

    final List<byte[]> pages = PAGES;
    final byte[] original = pages.get((data[1] & 0xFF) % pages.size());
    final byte[] symbols = Arrays.copyOf(original, Math.max(0, original.length + data[2]));
    for (int offset = 3; offset < data.length && offset - 3 < symbols.length; offset++) {
      symbols[offset - 3] ^= data[offset];
    }

    final TransportPage page;
    try {
      page = TransportPages.readPage(symbols);
    } catch (final Mcv2Exception exception) {
      return;
    }

    final byte[] rebuilt = this.rebuild(page);
    assertArrayEquals(symbols, rebuilt);
  }

  private byte[] rebuild(final TransportPage page) {
    final byte[] payload = page.getPayload();
    final byte[] raw = new byte[TransportPages.HEADER_BYTES + payload.length];
    Mcv2Decoder.putUnsignedInt(raw, 0, TransportPages.MAGIC);
    raw[4] = 1;
    raw[5] = (byte) page.getSymbolBits();
    Mcv2Decoder.putUnsignedShort(raw, 6, page.getFlags());
    Mcv2Decoder.putUnsignedInt(raw, 8, page.getStreamId());
    Mcv2Decoder.putUnsignedInt(raw, 12, page.getFrameId());
    Mcv2Decoder.putUnsignedShort(raw, 16, page.getNumber());
    Mcv2Decoder.putUnsignedShort(raw, 18, page.getCount());
    Mcv2Decoder.putUnsignedInt(raw, 20, page.getReferenceId());
    Mcv2Decoder.putUnsignedInt(raw, 24, page.getFrameBytes());
    System.arraycopy(payload, 0, raw, TransportPages.HEADER_BYTES, payload.length);
    final CRC32 crc = new CRC32();
    crc.update(raw);
    Mcv2Decoder.putUnsignedInt(raw, 28, crc.getValue());
    return TransportPages.toSymbols(raw);
  }
}
