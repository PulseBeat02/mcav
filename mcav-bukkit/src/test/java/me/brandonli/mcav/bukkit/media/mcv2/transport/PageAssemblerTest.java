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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class PageAssemblerTest {

  private static final int REFERENCE_ID_OFFSET = 20;
  private static final int FRAME_ID_OFFSET = 12;

  private static final int FRAME_DIMENSIONS_OFFSET = 40;

  private static final String FRAME_ID_FIELD = "frame id";
  private static final String REFERENCE_ID_FIELD = "reference id";
  private static final String FRAME_TYPE_FIELD = "frame type";

  private static final byte[] BIG = Mcv2Trees.twoPages();

  private static final byte[] TINY = Mcv2Trees.tiny();

  private static List<byte[]> pages(final byte[] frame) {
    return TransportPages.makePages(frame, 3);
  }

  private static byte[] page(final int number) {
    return pages(BIG).get(number);
  }

  // recomputes the CRC, so only the assembler's rules object
  private static byte[] rewritten(final byte[] page, final int offset, final long value) {
    final byte[] raw = TransportPages.fromSymbols(page, (page.length * 6) / 8);
    if (offset == 6 || offset == 16 || offset == 18) {
      Mcv2Decoder.putUnsignedShort(raw, offset, (int) value);
    } else {
      Mcv2Decoder.putUnsignedInt(raw, offset, value);
    }

    Mcv2Decoder.putUnsignedInt(raw, 28, 0);
    final CRC32 crc = new CRC32();
    crc.update(raw);
    Mcv2Decoder.putUnsignedInt(raw, 28, crc.getValue());
    return TransportPages.toSymbols(raw);
  }

  @Test
  void shouldAssemblePagesInAnyOrderAndAcceptIdenticalDuplicates() {
    final PageAssembler assembler = new PageAssembler(3, 6);
    final int pageCount = pages(BIG).size();
    assertEquals(2, pageCount);

    final Optional<byte[]> firstPush = assembler.push(page(1));
    final boolean firstPushIncomplete = firstPush.isEmpty();
    assertTrue(firstPushIncomplete);

    final Optional<byte[]> duplicatePush = assembler.push(page(1));
    final boolean duplicatePushIncomplete = duplicatePush.isEmpty();
    assertTrue(duplicatePushIncomplete);

    final int pendingCountBeforeCompletion = assembler.getPendingCount();
    assertEquals(1, pendingCountBeforeCompletion);

    final byte[] assembled = assembler.push(page(0)).orElseThrow();
    assertArrayEquals(BIG, assembled);

    final int pendingCountAfterCompletion = assembler.getPendingCount();
    assertEquals(0, pendingCountAfterCompletion);

    final byte[] assembledTiny = assembler.push(pages(TINY).get(0)).orElseThrow();
    assertArrayEquals(TINY, assembledTiny);
  }

  @Test
  void shouldRefuseAnotherStreamAndUnsupportedWidths() {
    final PageAssembler assembler = new PageAssembler(4, 6);
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> assembler.push(page(0)));
    final String message = exception.getMessage();
    assertEquals("Wrong stream!", message);

    assertThrows(IllegalArgumentException.class, () -> new PageAssembler(1, 5));
    assertThrows(IllegalArgumentException.class, () -> new PageAssembler(1, 9));
  }

  @Test
  void shouldRefuseANullPage() {
    final PageAssembler assembler = new PageAssembler(3, 6);

    assertThrows(NullPointerException.class, () -> assembler.push(null));
  }

  private static Stream<Arguments> disagreements() {
    return Stream.of(
      Arguments.of("page count", page(1), pages(TINY).get(0)),
      Arguments.of(REFERENCE_ID_FIELD, page(0), rewritten(page(1), REFERENCE_ID_OFFSET, 9)),
      Arguments.of("frame length", page(1), rewritten(page(0), 24, BIG.length - 1)),
      Arguments.of(FRAME_TYPE_FIELD, page(0), rewritten(page(1), 6, 0))
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("disagreements")
  void shouldDropAFrameWhosePagesDisagree(final String field, final byte[] first, final byte[] second) {
    final PageAssembler assembler = new PageAssembler(3, 6);
    final Optional<byte[]> firstPush = assembler.push(first);
    final boolean firstPushIncomplete = firstPush.isEmpty();
    assertTrue(firstPushIncomplete);

    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> assembler.push(second));
    final String message = exception.getMessage();
    assertEquals("Inconsistent pages!", message);

    final int pendingCount = assembler.getPendingCount();
    assertEquals(0, pendingCount);
  }

  @Test
  void shouldDropAFrameOnAConflictingDuplicate() {
    final PageAssembler assembler = new PageAssembler(3, 6);
    assembler.push(page(0));
    final byte[] conflicting = rewritten(page(0), FRAME_DIMENSIONS_OFFSET, 0x12345678L);
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> assembler.push(conflicting));
    final String message = exception.getMessage();
    assertEquals("Conflicting page duplicate!", message);

    final int pendingCount = assembler.getPendingCount();
    assertEquals(0, pendingCount);
  }

  @Test
  void shouldKeepAtMostFourFramesPendingAndEvictTheOldest() {
    final PageAssembler assembler = new PageAssembler(3, 6);
    for (int id = 1; id <= 4; id++) {
      final Optional<byte[]> pending = assembler.push(rewritten(page(0), FRAME_ID_OFFSET, id));
      final boolean framePending = pending.isEmpty();
      assertTrue(framePending);
    }

    // frame 0 evicts frame 1, then frame 1 starts again and evicts frame 2
    final Optional<byte[]> firstEviction = assembler.push(page(0));
    final boolean firstEvictionIncomplete = firstEviction.isEmpty();
    assertTrue(firstEvictionIncomplete);

    final int pendingCountAfterFirstEviction = assembler.getPendingCount();
    assertEquals(PageAssembler.MAXIMUM_PENDING, pendingCountAfterFirstEviction);

    final Optional<byte[]> secondEviction = assembler.push(rewritten(page(1), FRAME_ID_OFFSET, 1));
    final boolean secondEvictionIncomplete = secondEviction.isEmpty();
    assertTrue(secondEvictionIncomplete);

    final int pendingCountAfterSecondEviction = assembler.getPendingCount();
    assertEquals(PageAssembler.MAXIMUM_PENDING, pendingCountAfterSecondEviction);
    // frame 0 survived the evictions and completes
    final byte[] assembled = assembler.push(page(1)).orElseThrow();
    assertArrayEquals(BIG, assembled);

    final int pendingCountAfterCompletion = assembler.getPendingCount();
    assertEquals(3, pendingCountAfterCompletion);
  }

  private static Stream<Arguments> contradictions() {
    return Stream.of(
      Arguments.of(FRAME_ID_FIELD, FRAME_ID_OFFSET, 9L),
      Arguments.of(REFERENCE_ID_FIELD, REFERENCE_ID_OFFSET, 9L),
      Arguments.of(FRAME_TYPE_FIELD, 6, 0L)
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contradictions")
  void shouldRefuseAFrameWhoseHeaderContradictsItsPages(final String field, final int offset, final long value) {
    final PageAssembler assembler = new PageAssembler(3, 6);
    assembler.push(rewritten(page(0), offset, value));
    final byte[] last = rewritten(page(1), offset, value);
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> assembler.push(last));
    final String message = exception.getMessage();
    assertEquals("Frame and page identity mismatch!", message);
  }

  @Test
  void shouldRefuseACompleteFrameThatIsNotValid() {
    final PageAssembler assembler = new PageAssembler(3, 6);
    // the payload starts with the frame header; a zero width makes the frame invalid
    assembler.push(rewritten(page(0), FRAME_DIMENSIONS_OFFSET, 0));
    assertThrows(Mcv2Exception.class, () -> assembler.push(page(1)));

    final int pendingCount = assembler.getPendingCount();
    assertEquals(0, pendingCount);
  }
}
