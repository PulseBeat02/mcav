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
package me.brandonli.mcav.media.mcv2;

import static me.brandonli.mcav.media.mcv2.Mcv2Format.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/** The shared arithmetic of the bitstream, checked against the values the format defines. */
final class Mcv2FormatTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Mcv2Format.class);
  }

  @Test
  void readsAndWritesLittleEndianIntegers() {
    final byte[] data = new byte[6];
    putU16(data, 0, 0xBEEF);
    putU32(data, 2, 0xDEADBEEFL);
    assertEquals(0xBEEF, u16(data, 0));
    assertEquals(0xEF, data[0] & 0xFF);
    assertEquals(0xDEADBEEFL, u32(data, 2));
    assertEquals(0xEF, data[2] & 0xFF);
  }

  @Test
  void knowsWhichModesAreResiduals() {
    for (int mode = 0; mode < 32; mode++) {
      final boolean expected = (mode >= 8 && mode <= 11) || mode == 13 || mode == 15;
      assertEquals(expected, isResidual(mode), "mode " + mode);
    }
  }

  @Test
  void sizesEveryFixedRecord() {
    assertEquals(0, recordSize(MODE_SKIP, 32));
    assertEquals(2, recordSize(MODE_MOTION, 32));
    assertEquals(3, recordSize(MODE_SOLID, 8));
    assertEquals(6 + 128, recordSize(MODE_PALETTE, 32));
    assertEquals(6 + 8, recordSize(MODE_PALETTE, 8));
    assertEquals(3, recordSize(4, 8));
    assertEquals(3 * 64, recordSize(7, 8));
    assertEquals(2 + 3, recordSize(8, 16));
    assertEquals(2 + 192, recordSize(11, 32));
    assertEquals(16 + 2, recordSize(12, 8));
    assertEquals(2 + 16 + 2, recordSize(13, 8));
    assertEquals(64 + 8, recordSize(14, 16));
    assertEquals(2 + 64 + 8, recordSize(15, 32));
    assertEquals(4, lumaGrid(13));
    assertEquals(8, lumaGrid(15));
    assertEquals(1, chromaGrid(12));
    assertEquals(2, chromaGrid(14));
  }

  @Test
  void sizesPatternRecords() {
    assertEquals(6 + 1 + 1, patternSize(8, false, false));
    assertEquals(6 + 1 + 4, patternSize(32, false, false));
    assertEquals(1 + 1 + 2, patternSize(16, true, false));
    assertEquals(6 + 1, patternSize(32, false, true));
    assertEquals(2, patternSize(8, true, true));
  }

  @Test
  void widensSymbolsOnlyAsFarAsTheTableNeeds() {
    assertEquals(1, symbolWidth(1));
    assertEquals(1, symbolWidth(2));
    assertEquals(2, symbolWidth(3));
    assertEquals(4, symbolWidth(16));
    assertEquals(5, symbolWidth(17));
    assertEquals(5, symbolWidth(32));
    assertEquals(1, symbolWidth(0));
  }

  @Test
  void sizesTheWalkRegion() {
    assertEquals(20, walkBytes(5, false));
    assertEquals(0, walkBytes(0, false));
    // five checkpoints: two coarse pairs of four bytes and three two-byte deltas, after the two width bytes
    assertEquals(2 + 8 + 6, walkBytes(5, true));
    assertEquals(2 + 4, walkBytes(1, true));
  }

  @Test
  void expandsRgb565ByReplicatingTheHighBits() {
    assertEquals(0xFFFFFF, unpack565(0xFF, 0xFF));
    assertEquals(0x000000, unpack565(0, 0));
    // red 10101, green 101010, blue 01010
    final int value = 0b10101_101010_01010;
    assertEquals(0xADAA52, unpack565(value & 0xFF, value >> 8));
  }

  @Test
  void signExtends() {
    assertEquals(-1, signed(0xF, 4));
    assertEquals(7, signed(0x7, 4));
    assertEquals(-8, signed(0x8, 4));
    assertEquals(-32768, signed(0x8000, 16));
    assertEquals(32767, signed(0x7FFF, 16));
    assertTrue(signed(0x1FF, 8) < 0);
    assertFalse(signed(0x7F, 8) < 0);
  }
}
