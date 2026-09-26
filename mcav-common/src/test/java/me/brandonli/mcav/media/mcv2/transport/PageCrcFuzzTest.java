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
package me.brandonli.mcav.media.mcv2.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Fixtures;
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the checks a map page passes before its bytes are trusted - the header, the symbol alphabet, the padding and
 * the CRC - on real pages rather than on random symbols, which would never get past the CRC: the first input byte picks
 * the symbol width, the second one of the pages of three committed frames (one of them two pages long), the third cuts
 * the page short or lengthens it with zero symbols, and every byte after them is XORed onto the page's symbols from its
 * start. Whatever the change, the page is rejected with {@link Mcv2Exception}, or it is accepted and everything it
 * reports is what its symbols say: rebuilt from what was read, header and payload with a freshly computed CRC give back
 * the same symbols. The CRC detects corruption and is not authentication, so a change that keeps it valid is accepted;
 * what the target checks is that nothing unchecked gets through.
 */
@Tag("fuzz")
final class PageCrcFuzzTest {

  private static final long STREAM = 7;

  private static final List<byte[]> FRAMES = List.of(
    Mcv2Fixtures.frames(Mcv2Fixtures.read("edge/edge-tiny.mcs")).get(0),
    Mcv2Fixtures.frames(Mcv2Fixtures.read("edge/edge-derived-plain.mcs")).get(1),
    Mcv2Fixtures.frames(Mcv2Fixtures.read("conformance/p30r19-compact_final-65p255994.mcs")).get(0)
  );

  /** The pages of the frames, by symbol width minus six. */
  private static final List<List<byte[]>> PAGES = List.of(pages(6), pages(7), pages(8));

  private static List<byte[]> pages(final int symbolBits) {
    final List<byte[]> pages = new ArrayList<>();
    try {
      for (final byte[] frame : FRAMES) {
        pages.addAll(TransportPages.makePages(frame, STREAM, symbolBits));
      }
    } catch (final Mcv2Exception exception) {
      throw new AssertionError(exception);
    }
    return pages;
  }

  @FuzzTest(maxDuration = "30s")
  void acceptsOnlyPagesThatCheck(final byte[] data) {
    if (data.length < 3) {
      return;
    }
    final int symbolBits = 6 + ((data[0] & 0xFF) % 3);
    final List<byte[]> pages = PAGES.get(symbolBits - 6);
    final byte[] original = pages.get((data[1] & 0xFF) % pages.size());
    final byte[] symbols = Arrays.copyOf(original, Math.max(0, original.length + data[2]));
    for (int i = 3; i < data.length && i - 3 < symbols.length; i++) {
      symbols[i - 3] ^= data[i];
    }
    final TransportPage page;
    try {
      page = TransportPages.readPage(symbols, symbolBits);
    } catch (final Mcv2Exception rejected) {
      return;
    }
    assertArrayEquals(symbols, rebuild(page));
  }

  /** The symbols of a page, written from its fields as the sender writes them. */
  private static byte[] rebuild(final TransportPage page) {
    final byte[] payload = page.getPayload();
    final byte[] raw = new byte[TransportPages.HEADER_BYTES + payload.length];
    Mcv2Format.putU32(raw, 0, TransportPages.MAGIC);
    raw[4] = 1;
    raw[5] = (byte) page.getSymbolBits();
    Mcv2Format.putU16(raw, 6, page.getFlags());
    Mcv2Format.putU32(raw, 8, page.getStreamId());
    Mcv2Format.putU32(raw, 12, page.getFrameId());
    Mcv2Format.putU16(raw, 16, page.getNumber());
    Mcv2Format.putU16(raw, 18, page.getCount());
    Mcv2Format.putU32(raw, 20, page.getReferenceId());
    Mcv2Format.putU32(raw, 24, page.getFrameBytes());
    System.arraycopy(payload, 0, raw, TransportPages.HEADER_BYTES, payload.length);
    final CRC32 crc = new CRC32();
    crc.update(raw);
    Mcv2Format.putU32(raw, 28, crc.getValue());
    return TransportPages.toSymbols(raw, page.getSymbolBits());
  }
}
