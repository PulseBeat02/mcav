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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Fixtures;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2FrameKind;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class TransportPagesTest {

  private static final int FRAME_BYTES_OFFSET = 24;
  private static final int PAGE_NUMBER_OFFSET = 16;

  private static final String PAGES_PATH = "conformance/pages.json";

  private static final String INVALID_METADATA_MESSAGE = "Invalid page metadata!";

  @Test
  void shouldBeAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(TransportPages.class);
  }

  private static Stream<Arguments> vectors() {
    final String text = new String(Mcv2Fixtures.read(PAGES_PATH), StandardCharsets.UTF_8);
    final JsonObject root = JsonParser.parseString(text).getAsJsonObject();
    final List<Arguments> arguments = new ArrayList<>();
    final int streamId = root.get("stream_id").getAsInt();
    assertEquals(7, streamId);

    final int symbolBits = root.get("symbol_bits").getAsInt();
    assertEquals(6, symbolBits);

    for (final JsonElement entry : root.getAsJsonArray("frames")) {
      final JsonObject expected = entry.getAsJsonObject();
      final String name = "%s#%s@6".formatted(expected.get("stream").getAsString(), expected.get("frame").getAsInt());
      arguments.add(Arguments.of(name, expected));
    }

    final int vectorCount = arguments.size();
    assertEquals(6, vectorCount);

    return arguments.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("vectors")
  void shouldMakeTheReferencePages(final String name, final JsonObject expected) {
    final String stream = name.substring(0, name.indexOf('#'));
    final int index = Integer.parseInt(name.substring(name.indexOf('#') + 1, name.indexOf('@')));
    final int bits = Integer.parseInt(name.substring(name.indexOf('@') + 1));
    final byte[] frame = Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(stream)).get(index);
    final List<byte[]> pages = TransportPages.makePages(frame, 7);
    final List<String> digests = new ArrayList<>();
    for (final byte[] page : pages) {
      digests.add(Mcv2Fixtures.sha256(page));
    }

    final List<String> reference = new ArrayList<>();
    expected.getAsJsonArray("pages").forEach(element -> reference.add(element.getAsString()));
    assertEquals(reference, digests);

    final int expectedPageCount = expected.getAsJsonArray("lengths").size();
    final int pageCount = pages.size();
    assertEquals(expectedPageCount, pageCount);

    for (int page = 0; page < pages.size(); page++) {
      final int expectedLength = expected.getAsJsonArray("lengths").get(page).getAsInt();
      final int length = pages.get(page).length;
      assertEquals(expectedLength, length);
    }

    final long expectedWire = expected.get("wire").getAsLong();
    final long wire = TransportPages.wireBytes(pages, TransportPages.PACKET_OVERHEAD);
    assertEquals(expectedWire, wire);

    final long expectedFullWire = expected.get("wire_full").getAsLong();
    final long fullWire = TransportPages.fullMapWireBytes(pages, TransportPages.PACKET_OVERHEAD);
    assertEquals(expectedFullWire, fullWire);

    final PageAssembler assembler = new PageAssembler(7, bits);
    byte[] assembled = null;
    for (final byte[] page : pages) {
      final TransportPage read = TransportPages.readPage(page);
      final int expectedCount = pages.size();
      final int count = read.getCount();
      assertEquals(expectedCount, count);

      final int expectedLength = page.length;
      final int usefulSymbols = TransportPages.usefulSymbols(Arrays.copyOf(page, TransportPages.PAGE_SYMBOLS));
      assertEquals(expectedLength, usefulSymbols);

      assembled = assembler.push(page).orElse(null);
    }

    assertArrayEquals(frame, assembled);
  }

  @Test
  void shouldReuseAnImmutableValidatedFrame() {
    final byte[] bytes = Mcv2Trees.twoPages();
    final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(bytes);
    final List<byte[]> expected = TransportPages.makePages(bytes, 7);
    bytes[0] = 0;
    final List<byte[]> pages = TransportPages.makePages(parsed, 7);
    final int expectedSize = expected.size();
    final int size = pages.size();
    assertEquals(expectedSize, size);

    for (int index = 0; index < pages.size(); index++) {
      final byte[] expectedPage = expected.get(index);
      final byte[] page = pages.get(index);
      assertArrayEquals(expectedPage, page);
    }

    assertThrows(IllegalArgumentException.class, () -> TransportPages.makePages(parsed, -1));
    assertThrows(IllegalArgumentException.class, () -> TransportPages.makePages(parsed, 1L << 32));
    assertThrows(NullPointerException.class, () -> TransportPages.makePages((Mcv2Decoder.Frame) null, 7));
  }

  @ParameterizedTest(name = "stream id {0}")
  @ValueSource(longs = { 0L, 0xFFFFFFFFL })
  void shouldAcceptTheSmallestAndTheLargestStreamIds(final long streamId) {
    final byte[] frame = Mcv2Trees.tiny();
    final List<byte[]> pages = TransportPages.makePages(frame, streamId);
    final byte[] symbols = pages.get(0);
    final TransportPage page = TransportPages.readPage(symbols);

    final long pageStreamId = page.getStreamId();
    assertEquals(streamId, pageStreamId);
  }

  @Test
  void shouldKnowThePageCapacities() {
    final int capacity = TransportPages.capacity();
    assertEquals(12256, capacity);

    final int pageCountAtCapacity = TransportPages.pageCount(12256);
    assertEquals(1, pageCountAtCapacity);

    final int pageCountPastCapacity = TransportPages.pageCount(12257);
    assertEquals(2, pageCountPastCapacity);
  }

  @Test
  void shouldPackSymbolsLeastSignificantBitFirst() {
    // A5 has low six bits 37 and high two bits 2.
    final byte[] expectedSymbols = new byte[] { 37, 2 };
    final byte[] symbols = TransportPages.toSymbols(new byte[] { (byte) 0xA5 });
    assertArrayEquals(expectedSymbols, symbols);

    final byte[] expectedEmptySymbols = new byte[0];
    final byte[] emptySymbols = TransportPages.toSymbols(new byte[0]);
    assertArrayEquals(expectedEmptySymbols, emptySymbols);

    final Random random = new Random(20260925);
    for (int trial = 0; trial < 3; trial++) {
      final byte[] data = new byte[1 + random.nextInt(300)];
      random.nextBytes(data);
      final byte[] roundTrip = TransportPages.fromSymbols(TransportPages.toSymbols(data), data.length);
      assertArrayEquals(data, roundTrip);
    }
  }

  @Test
  void shouldRefuseMalformedSymbols() {
    final byte[] symbols = TransportPages.toSymbols(new byte[] { 1, 2, 3 });
    assertThrows(Mcv2Exception.class, () -> TransportPages.fromSymbols(symbols, 4));
    assertThrows(Mcv2Exception.class, () -> TransportPages.fromSymbols(symbols, -1));

    final byte[] outside = symbols.clone();
    outside[0] = 64;
    final Mcv2Exception outsideFailure = assertThrows(Mcv2Exception.class, () -> TransportPages.fromSymbols(outside, 3));
    final String outsideMessage = outsideFailure.getMessage();
    assertEquals("Out-of-alphabet symbol!", outsideMessage);

    // one byte is two six-bit symbols; the top four bits of the second are padding
    final byte[] padded = TransportPages.toSymbols(new byte[] { 1 });
    padded[1] |= 0x20;
    final Mcv2Exception paddingFailure = assertThrows(Mcv2Exception.class, () -> TransportPages.fromSymbols(padded, 1));
    final String paddingMessage = paddingFailure.getMessage();
    assertEquals("Nonzero symbol padding!", paddingMessage);
  }

  private byte[] page() {
    final byte[] frame = Mcv2Trees.tiny();
    return TransportPages.makePages(frame, 7).get(0);
  }

  // recomputes nothing, so the page breaks the rule of that byte
  private byte[] withHeaderByte(final byte[] page, final int offset, final int value) {
    final byte[] raw = TransportPages.fromSymbols(page, (page.length * 6) / 8);
    raw[offset] = (byte) value;
    return TransportPages.toSymbols(raw);
  }

  @Test
  void shouldReadAValidPage() {
    final TransportPage read = TransportPages.readPage(this.page());
    final long streamId = read.getStreamId();
    assertEquals(7, streamId);

    final long frameId = read.getFrameId();
    assertEquals(0, frameId);

    final int number = read.getNumber();
    assertEquals(0, number);

    final int count = read.getCount();
    assertEquals(1, count);

    final long referenceId = read.getReferenceId();
    assertEquals(0, referenceId);

    final int flags = read.getFlags();
    assertEquals(1, flags);

    final int symbolBits = read.getSymbolBits();
    assertEquals(6, symbolBits);

    final int expectedPayloadLength = read.getFrameBytes();
    final int payloadLength = read.getPayload().length;
    assertEquals(expectedPayloadLength, payloadLength);
  }

  @ParameterizedTest(name = "header byte {0}")
  @ValueSource(ints = { 0, 4, 5, 6, 7 })
  void shouldRefuseAnUnsupportedHeader(final int offset) {
    final byte[] page = this.page();
    final byte[] raw = TransportPages.fromSymbols(page, (page.length * 6) / 8);
    final byte[] broken = this.withHeaderByte(page, offset, raw[offset] + 2);
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(broken));
    final String message = exception.getMessage();
    assertEquals("Unsupported page header!", message);
  }

  @Test
  void shouldRefuseInconsistentPageMetadata() {
    final byte[] page = this.page();
    final Mcv2Exception frameBytesFailure = assertThrows(Mcv2Exception.class, () ->
      TransportPages.readPage(this.withHeaderByte(page, FRAME_BYTES_OFFSET, 10))
    );
    final String frameBytesMessage = frameBytesFailure.getMessage();
    assertEquals(INVALID_METADATA_MESSAGE, frameBytesMessage);

    final Mcv2Exception oversizeFrameFailure = assertThrows(Mcv2Exception.class, () ->
      TransportPages.readPage(this.withHeaderByte(this.withHeaderByte(page, 26, 0), 27, 1))
    );
    final String oversizeFrameMessage = oversizeFrameFailure.getMessage();
    assertEquals(INVALID_METADATA_MESSAGE, oversizeFrameMessage);

    final Mcv2Exception countFailure = assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(this.withHeaderByte(page, 18, 2)));
    final String countMessage = countFailure.getMessage();
    assertEquals(INVALID_METADATA_MESSAGE, countMessage);

    final Mcv2Exception numberFailure = assertThrows(Mcv2Exception.class, () ->
      TransportPages.readPage(this.withHeaderByte(page, PAGE_NUMBER_OFFSET, 1))
    );
    final String numberMessage = numberFailure.getMessage();
    assertEquals(INVALID_METADATA_MESSAGE, numberMessage);

    final Mcv2Exception checksumFailure = assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(this.withHeaderByte(page, 8, 8)));
    final String checksumMessage = checksumFailure.getMessage();
    assertEquals("Page CRC mismatch!", checksumMessage);
  }

  @Test
  void shouldRefusePagesOfTheWrongSize() {
    final byte[] page = this.page();
    final Mcv2Exception oversizeFailure = assertThrows(Mcv2Exception.class, () ->
      TransportPages.readPage(new byte[TransportPages.PAGE_SYMBOLS + 1])
    );
    final String oversizeMessage = oversizeFailure.getMessage();
    assertEquals("Oversize map page!", oversizeMessage);

    final Mcv2Exception truncatedFailure = assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(Arrays.copyOf(page, 42)));
    final String truncatedMessage = truncatedFailure.getMessage();
    assertEquals("Truncated page header!", truncatedMessage);

    // the useful extent follows from the header; padding symbols after it are not part of the page
    assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(Arrays.copyOf(page, page.length + 1)));

    final int expectedLength = page.length;
    final int usefulSymbols = TransportPages.usefulSymbols(page);
    assertEquals(expectedLength, usefulSymbols);
  }

  @Test
  void shouldRefuseAFrameThatIsNotValid() {
    assertThrows(Mcv2Exception.class, () -> TransportPages.makePages(new byte[48], 1));
    assertThrows(IllegalArgumentException.class, () -> TransportPages.makePages(new byte[48], -1));
    assertThrows(IllegalArgumentException.class, () -> TransportPages.makePages(new byte[48], 1L << 32));
  }

  @Test
  void shouldNameInEveryPageTheFrameItsFrameIsPredictedFrom() {
    final Node root = Mcv2Trees.motion(1, 1);
    final byte[] frame = Mcv2Trees.write(32, 32, 5, 4, Mcv2FrameKind.PREDICTED, List.of(root));
    for (final byte[] page : TransportPages.makePages(frame, 7)) {
      final TransportPage read = TransportPages.readPage(page);
      final long frameId = read.getFrameId();
      assertEquals(5, frameId);
      final long referenceId = read.getReferenceId();
      assertEquals(4, referenceId);
      final int flags = read.getFlags();
      assertEquals(0, flags, "a P frame");
    }
  }

  private byte[] pageOfFrame(final long frameBytes, final int number, final int count, final int size) {
    final byte[] raw = new byte[TransportPages.HEADER_BYTES + size];
    Mcv2Decoder.putUnsignedInt(raw, 0, TransportPages.MAGIC);
    raw[4] = 1;
    raw[5] = 6;
    Mcv2Decoder.putUnsignedShort(raw, PAGE_NUMBER_OFFSET, number);
    Mcv2Decoder.putUnsignedShort(raw, 18, count);
    Mcv2Decoder.putUnsignedInt(raw, FRAME_BYTES_OFFSET, frameBytes);
    final CRC32 crc = new CRC32();
    crc.update(raw);
    Mcv2Decoder.putUnsignedInt(raw, 28, crc.getValue());
    return TransportPages.toSymbols(raw);
  }

  @Test
  void shouldReadThePagesOfTheSmallestAndTheLargestFrames() {
    final int smallestFrameBytes = TransportPages.readPage(this.pageOfFrame(20, 0, 1, 20)).getFrameBytes();
    assertEquals(20, smallestFrameBytes);

    final int largestPayloadLength = TransportPages.readPage(this.pageOfFrame(131071, 10, 11, 8511)).getPayload().length;
    assertEquals(8511, largestPayloadLength);

    assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(this.pageOfFrame(19, 0, 1, 19)));
    assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(this.pageOfFrame(131072, 10, 11, 8512)));
  }

  @Test
  void shouldFitAFrameOneByteShortOfAFullPageInOnePage() {
    final int count = TransportPages.readPage(this.pageOfFrame(12_255, 0, 1, 12_255)).getCount();
    assertEquals(1, count);
  }

  @Test
  void shouldRefuseAPageNumberedPastTheLastPage() {
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(this.pageOfFrame(100, 1, 1, 100)));
    final String message = exception.getMessage();
    assertEquals(INVALID_METADATA_MESSAGE, message);
  }

  @Test
  void shouldRefuseAPageCutRightAfterItsHeaderForItsExtent() {
    // 43 six-bit symbols hold the whole 32-byte header, so the header reads; the missing payload is what is wrong
    final byte[] page = this.page();
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> TransportPages.readPage(Arrays.copyOf(page, 43)));
    final String message = exception.getMessage();
    assertEquals("Invalid symbol extent!", message);
  }

  @Test
  void shouldChargeWholeRowsAndTheEnvelope() {
    final long expectedWire = 128 + 18 + 256 + 18;
    final long wire = TransportPages.wireBytes(List.of(new byte[1], new byte[129]), 18);
    assertEquals(expectedWire, wire);

    final long expectedFullWire = 2 * (16384 + 5);
    final long fullWire = TransportPages.fullMapWireBytes(List.of(new byte[1], new byte[2]), 5);
    assertEquals(expectedFullWire, fullWire);
  }
}
