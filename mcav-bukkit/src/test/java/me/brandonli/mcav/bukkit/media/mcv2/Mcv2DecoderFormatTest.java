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
package me.brandonli.mcav.bukkit.media.mcv2;

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_PALETTE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_SKIP;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_SOLID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.patternSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.putU16;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.putU32;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.recordSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.signed;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.u16;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.u32;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.unpack565;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/** The shared arithmetic of the bitstream, checked against the values the format defines. */
final class Mcv2DecoderFormatTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Mcv2Decoder.class);
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
  void sizesEveryFixedRecord() {
    assertEquals(0, recordSize(MODE_SKIP, 32));
    assertEquals(2, recordSize(MODE_MOTION, 32));
    assertEquals(3, recordSize(MODE_SOLID, 8));
    assertEquals(6 + 128, recordSize(MODE_PALETTE, 32));
    assertEquals(6 + 8, recordSize(MODE_PALETTE, 8));
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
  void sizesTheWalkRegion() throws Mcv2Exception {
    for (int descriptors = 0; descriptors < 40; descriptors++) {
      final byte[] modes = new byte[descriptors];
      final byte[] frame = Mcv2WireFrames.frame(
        1280,
        32,
        true,
        modes,
        new byte[descriptors][0],
        new int[] { descriptors, 0, 0 },
        new byte[0],
        new byte[][] { {}, {}, {} }
      );
      assertEquals(32 + 8 + 4 + 12 + descriptors + 4 * ((descriptors + 7) / 8) + 4, Mcv2Decoder.parse(frame).getPayloadStart());
    }
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
