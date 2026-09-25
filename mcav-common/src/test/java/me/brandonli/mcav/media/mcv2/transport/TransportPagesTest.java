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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Fixtures;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The map-page transport: pages identical to the reference's {@code make_pages} (compared by SHA-256 of every page's
 * symbols at 6, 7 and 8 bits), symbol packing, and every rule {@code read_page} enforces.
 */
final class TransportPagesTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(TransportPages.class);
  }

  static Stream<Arguments> vectors() {
    final String text = new String(Mcv2Fixtures.read("conformance/pages.json"), StandardCharsets.UTF_8);
    final JsonObject root = JsonParser.parseString(text).getAsJsonObject();
    final List<Arguments> arguments = new ArrayList<>();
    for (final Map.Entry<String, JsonElement> entry : root.entrySet()) {
      arguments.add(Arguments.of(entry.getKey(), entry.getValue().getAsJsonObject()));
    }
    return arguments.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("vectors")
  void makesTheReferencePages(final String name, final JsonObject expected) throws Mcv2Exception {
    final String stream = name.substring(0, name.indexOf('#'));
    final int index = Integer.parseInt(name.substring(name.indexOf('#') + 1, name.indexOf('@')));
    final int bits = Integer.parseInt(name.substring(name.indexOf('@') + 1));
    final byte[] frame = Mcv2Fixtures.frames(Mcv2Fixtures.read(stream)).get(index);
    final List<byte[]> pages = TransportPages.makePages(frame, 7, bits);
    final List<String> digests = new ArrayList<>();
    for (final byte[] page : pages) {
      digests.add(Mcv2Fixtures.sha256(page));
    }
    final List<String> reference = new ArrayList<>();
    expected.getAsJsonArray("pages").forEach(element -> reference.add(element.getAsString()));
    assertEquals(reference, digests);
    assertEquals(expected.get("wire").getAsLong(), TransportPages.wireBytes(pages, false, TransportPages.PACKET_OVERHEAD));
    assertEquals(expected.get("wire_full").getAsLong(), TransportPages.wireBytes(pages, true, TransportPages.PACKET_OVERHEAD));
    // every page reads back, and the pages reassemble the frame
    final PageAssembler assembler = new PageAssembler(7, bits);
    byte[] assembled = null;
    for (final byte[] page : pages) {
      final TransportPage read = TransportPages.readPage(page, bits);
      assertEquals(pages.size(), read.getCount());
      assertEquals(page.length, TransportPages.usefulSymbols(Arrays.copyOf(page, TransportPages.PAGE_SYMBOLS), bits));
      assembled = assembler.push(page);
    }
    assertArrayEquals(frame, assembled);
  }

  @Test
  void knowsThePageCapacities() {
    assertEquals(12256, TransportPages.capacity(6));
    assertEquals(14304, TransportPages.capacity(7));
    assertEquals(16352, TransportPages.capacity(8));
    assertEquals(1, TransportPages.pageCount(12256, 6));
    assertEquals(2, TransportPages.pageCount(12257, 6));
    assertThrows(IllegalArgumentException.class, () -> TransportPages.capacity(5));
    assertThrows(IllegalArgumentException.class, () -> TransportPages.capacity(9));
  }

  @Test
  void packsSymbolsLeastSignificantBitFirst() throws Mcv2Exception {
    // the format's own example: A5 is 37, 2 at six bits and 37, 1 at seven
    assertArrayEquals(new byte[] { 37, 2 }, TransportPages.toSymbols(new byte[] { (byte) 0xA5 }, 6));
    assertArrayEquals(new byte[] { 37, 1 }, TransportPages.toSymbols(new byte[] { (byte) 0xA5 }, 7));
    assertArrayEquals(new byte[] { (byte) 0xA5 }, TransportPages.toSymbols(new byte[] { (byte) 0xA5 }, 8));
    assertArrayEquals(new byte[0], TransportPages.toSymbols(new byte[0], 6));
    final Random random = new Random(20260925);
    for (final int bits : new int[] { 6, 7, 8 }) {
      final byte[] data = new byte[1 + random.nextInt(300)];
      random.nextBytes(data);
      assertArrayEquals(data, TransportPages.fromSymbols(TransportPages.toSymbols(data, bits), bits, data.length));
    }
  }

  @Test
  void refusesMalformedSymbols() {
    final byte[] symbols = TransportPages.toSymbols(new byte[] { 1, 2, 3 }, 6);
    assertThrows(Mcv2Exception.class, () -> TransportPages.fromSymbols(symbols, 6, 4));
    assertThrows(Mcv2Exception.class, () -> TransportPages.fromSymbols(symbols, 5, 3));
    assertThrows(Mcv2Exception.class, () -> TransportPages.fromSymbols(symbols, 9, 3));
    assertThrows(Mcv2Exception.class, () -> TransportPages.fromSymbols(symbols, 6, -1));
    final byte[] outside = symbols.clone();
    outside[0] = 64;
    assertEquals("Out-of-alphabet symbol", assertThrows(Mcv2Exception.class, () -> TransportPages.fromSymbols(outside, 6, 3)).getMessage());
    // one byte is two six-bit symbols; the top four bits of the second are padding
    final byte[] padded = TransportPages.toSymbols(new byte[] { 1 }, 6);
    padded[1] |= 0x20;
    assertEquals("Nonzero symbol padding", assertThrows(Mcv2Exception.class, () -> TransportPages.fromSymbols(padded, 6, 1)).getMessage());
    assertThrows(IllegalArgumentException.class, () -> TransportPages.toSymbols(new byte[1], 5));
  }

  private static byte[] page() throws Mcv2Exception {
    final byte[] frame = Mcv2Fixtures.frames(Mcv2Fixtures.read("edge/edge-tiny.mcs")).get(0);
    return TransportPages.makePages(frame, 7, 6).get(0);
  }

  /** Rewrites one header byte of a six-bit page and recomputes nothing, so the page tells the rule it breaks. */
  private static byte[] withHeaderByte(final byte[] page, final int offset, final int value) throws Mcv2Exception {
    final byte[] raw = TransportPages.fromSymbols(page, 6, (page.length * 6) / 8);
    raw[offset] = (byte) value;
    return TransportPages.toSymbols(raw, 6);
  }

  @Test
  void readsAValidPage() throws Mcv2Exception {
    final TransportPage read = TransportPages.readPage(page(), 6);
    assertEquals(7, read.getStreamId());
    assertEquals(0, read.getFrameId());
    assertEquals(0, read.getNumber());
    assertEquals(1, read.getCount());
    assertEquals(0, read.getReferenceId());
    assertEquals(1, read.getFlags());
    assertEquals(6, read.getSymbolBits());
    assertEquals(read.getFrameBytes(), read.getPayload().length);
  }

  @ParameterizedTest(name = "header byte {0}")
  @ValueSource(ints = { 0, 4, 5, 6, 7 })
  void refusesAnUnsupportedHeader(final int offset) throws Mcv2Exception {
    final byte[] page = page();
    final byte[] raw = TransportPages.fromSymbols(page, 6, (page.length * 6) / 8);
    final byte[] broken = withHeaderByte(page, offset, raw[offset] + 2);
    assertEquals("Unsupported page header", assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(broken, 6)).getMessage());
  }

  @Test
  void refusesInconsistentPageMetadata() throws Mcv2Exception {
    final byte[] page = page();
    assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(withHeaderByte(page, 24, 10), 6));
    assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(withHeaderByte(withHeaderByte(page, 26, 0), 27, 1), 6));
    assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(withHeaderByte(page, 18, 2), 6));
    assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(withHeaderByte(page, 16, 1), 6));
    assertEquals(
      "Page CRC mismatch",
      assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(withHeaderByte(page, 8, 8), 6)).getMessage()
    );
  }

  @Test
  void refusesPagesOfTheWrongSize() throws Mcv2Exception {
    final byte[] page = page();
    assertEquals(
      "Oversize map page",
      assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(new byte[TransportPages.PAGE_SYMBOLS + 1], 6)).getMessage()
    );
    assertEquals(
      "Truncated page header",
      assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(Arrays.copyOf(page, 42), 6)).getMessage()
    );
    assertEquals("Truncated page header", assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(page, 5)).getMessage());
    assertEquals("Truncated page header", assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(page, 9)).getMessage());
    // the useful extent follows from the header; padding symbols after it are not part of the page
    assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(Arrays.copyOf(page, page.length + 1), 6));
    assertEquals(page.length, TransportPages.usefulSymbols(page, 6));
  }

  @Test
  void refusesAFrameThatIsNotValid() {
    assertThrows(Mcv2Exception.class, () -> TransportPages.makePages(new byte[48], 1, 6));
    assertThrows(IllegalArgumentException.class, () -> TransportPages.makePages(new byte[48], -1, 6));
    assertThrows(IllegalArgumentException.class, () -> TransportPages.makePages(new byte[48], 1L << 32, 6));
  }

  @Test
  void chargesWholeRowsAndTheEnvelope() {
    assertEquals(128 + 18 + 256 + 18, TransportPages.wireBytes(List.of(new byte[1], new byte[129]), false, 18));
    assertEquals(2 * (16384 + 5), TransportPages.wireBytes(List.of(new byte[1], new byte[2]), true, 5));
  }
}
