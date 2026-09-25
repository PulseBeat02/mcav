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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Fixtures;
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The page assembler: reordering, duplicates, conflicts, eviction and the frame/page identity check. */
final class PageAssemblerTest {

  /** A two-page keyframe (frame id 0) at six bits. */
  private static final byte[] BIG = Mcv2Fixtures.frames(Mcv2Fixtures.read("conformance/p30r19-compact_final-65p255994.mcs")).get(0);

  /** A one-page keyframe, also frame id 0. */
  private static final byte[] TINY = Mcv2Fixtures.frames(Mcv2Fixtures.read("edge/edge-tiny.mcs")).get(0);

  private static List<byte[]> pages(final byte[] frame) {
    try {
      return TransportPages.makePages(frame, 3, 6);
    } catch (final Mcv2Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static byte[] page(final int number) {
    return pages(BIG).get(number);
  }

  /** A copy of a page with one header field changed and its CRC recomputed, so only the assembler's rules object. */
  private static byte[] rewritten(final byte[] page, final int offset, final long value) {
    try {
      final byte[] raw = TransportPages.fromSymbols(page, 6, (page.length * 6) / 8);
      if (offset == 6 || offset == 16 || offset == 18) {
        Mcv2Format.putU16(raw, offset, (int) value);
      } else {
        Mcv2Format.putU32(raw, offset, value);
      }
      Mcv2Format.putU32(raw, 28, 0);
      final CRC32 crc = new CRC32();
      crc.update(raw);
      Mcv2Format.putU32(raw, 28, crc.getValue());
      return TransportPages.toSymbols(raw, 6);
    } catch (final Mcv2Exception exception) {
      throw new AssertionError(exception);
    }
  }

  @Test
  void assemblesPagesInAnyOrderAndAcceptsIdenticalDuplicates() throws Mcv2Exception {
    final PageAssembler assembler = new PageAssembler(3, 6);
    assertEquals(2, pages(BIG).size());
    assertNull(assembler.push(page(1)));
    assertNull(assembler.push(page(1)));
    assertEquals(1, assembler.getPendingCount());
    assertArrayEquals(BIG, assembler.push(page(0)));
    assertEquals(0, assembler.getPendingCount());
    assertArrayEquals(TINY, assembler.push(pages(TINY).get(0)));
  }

  @Test
  void refusesAnotherStreamAndUnsupportedWidths() {
    final PageAssembler assembler = new PageAssembler(4, 6);
    assertEquals("Wrong stream", assertThrows(Mcv2Exception.class, () -> assembler.push(page(0))).getMessage());
    assertThrows(IllegalArgumentException.class, () -> new PageAssembler(1, 5));
    assertThrows(IllegalArgumentException.class, () -> new PageAssembler(1, 9));
  }

  static Stream<Arguments> disagreements() {
    return Stream.of(
      Arguments.of("page count", page(1), pages(TINY).get(0)),
      Arguments.of("reference id", page(0), rewritten(page(1), 20, 9)),
      Arguments.of("frame length", page(1), rewritten(page(0), 24, BIG.length - 1)),
      Arguments.of("frame type", page(0), rewritten(page(1), 6, 0))
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("disagreements")
  void dropsAFrameWhosePagesDisagree(final String field, final byte[] first, final byte[] second) throws Mcv2Exception {
    final PageAssembler assembler = new PageAssembler(3, 6);
    assertNull(assembler.push(first));
    assertEquals("Inconsistent pages", assertThrows(Mcv2Exception.class, () -> assembler.push(second)).getMessage());
    assertEquals(0, assembler.getPendingCount());
  }

  @Test
  void dropsAFrameOnAConflictingDuplicate() throws Mcv2Exception {
    final PageAssembler assembler = new PageAssembler(3, 6);
    assembler.push(page(0));
    final byte[] conflicting = rewritten(page(0), 40, 0x12345678L);
    assertEquals("Conflicting page duplicate", assertThrows(Mcv2Exception.class, () -> assembler.push(conflicting)).getMessage());
    assertEquals(0, assembler.getPendingCount());
  }

  @Test
  void keepsAtMostFourFramesPendingAndEvictsTheOldest() throws Mcv2Exception {
    final PageAssembler assembler = new PageAssembler(3, 6);
    for (int id = 1; id <= 4; id++) {
      assertNull(assembler.push(rewritten(page(0), 12, id)));
    }
    // frame 0 evicts frame 1, then frame 1 starts again and evicts frame 2
    assertNull(assembler.push(page(0)));
    assertEquals(PageAssembler.MAX_PENDING, assembler.getPendingCount());
    assertNull(assembler.push(rewritten(page(1), 12, 1)));
    assertEquals(PageAssembler.MAX_PENDING, assembler.getPendingCount());
    // frame 0 survived the evictions and completes
    assertArrayEquals(BIG, assembler.push(page(1)));
    assertEquals(3, assembler.getPendingCount());
  }

  static Stream<Arguments> contradictions() {
    return Stream.of(Arguments.of("frame id", 12, 9L), Arguments.of("reference id", 20, 9L), Arguments.of("frame type", 6, 0L));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contradictions")
  void refusesAFrameWhoseHeaderContradictsItsPages(final String field, final int offset, final long value) throws Mcv2Exception {
    final PageAssembler assembler = new PageAssembler(3, 6);
    assembler.push(rewritten(page(0), offset, value));
    final byte[] last = rewritten(page(1), offset, value);
    assertEquals("Frame and page identity mismatch", assertThrows(Mcv2Exception.class, () -> assembler.push(last)).getMessage());
  }

  @Test
  void refusesACompleteFrameThatIsNotValid() throws Mcv2Exception {
    final PageAssembler assembler = new PageAssembler(3, 6);
    // the payload starts with the frame header; a zero width makes the frame invalid
    assembler.push(rewritten(page(0), 40, 0));
    assertThrows(Mcv2Exception.class, () -> assembler.push(page(1)));
    assertEquals(0, assembler.getPendingCount());
  }
}
