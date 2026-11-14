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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_COMPACT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_PALETTE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_SKIP;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_SOLID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.patternSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.putUnsignedInt;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.putUnsignedShort;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.readUnsignedInt;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.readUnsignedShort;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.recordSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.signed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class Mcv2DecoderFormatTest {

  private static final int ROOT_BLOCK_SIZE = 32;

  @Test
  void shouldReadAndWriteLittleEndianIntegers() {
    final byte[] data = new byte[6];
    putUnsignedShort(data, 0, 0xBEEF);
    putUnsignedInt(data, 2, 0xDEADBEEFL);

    final int shortValue = readUnsignedShort(data, 0);
    assertEquals(0xBEEF, shortValue);

    final int shortLowByte = data[0] & 0xFF;
    assertEquals(0xEF, shortLowByte);

    final long unsignedValue = readUnsignedInt(data, 2);
    assertEquals(0xDEADBEEFL, unsignedValue);

    final int lowByte = data[2] & 0xFF;
    assertEquals(0xEF, lowByte);
  }

  @Test
  void shouldSizeEveryFixedRecord() {
    final int skipSize = recordSize(MODE_SKIP, ROOT_BLOCK_SIZE);
    assertEquals(0, skipSize);

    final int motionSize = recordSize(MODE_MOTION, ROOT_BLOCK_SIZE);
    assertEquals(2, motionSize);

    final int solidSize = recordSize(MODE_SOLID, 8);
    assertEquals(3, solidSize);

    final int expectedLargePaletteSize = 6 + 128;
    final int largePaletteSize = recordSize(MODE_PALETTE, ROOT_BLOCK_SIZE);
    assertEquals(expectedLargePaletteSize, largePaletteSize);

    final int expectedSmallPaletteSize = 6 + 8;
    final int smallPaletteSize = recordSize(MODE_PALETTE, 8);
    assertEquals(expectedSmallPaletteSize, smallPaletteSize);

    final int smallCompactSize = recordSize(MODE_COMPACT, 8);
    assertEquals(10, smallCompactSize);

    final int mediumCompactSize = recordSize(MODE_COMPACT, 16);
    assertEquals(10, mediumCompactSize);

    final int largeCompactSize = recordSize(MODE_COMPACT, ROOT_BLOCK_SIZE);
    assertEquals(10, largeCompactSize);

    assertThrows(IllegalArgumentException.class, () -> recordSize(-1, 8));
    assertThrows(IllegalArgumentException.class, () -> recordSize(7, ROOT_BLOCK_SIZE));
  }

  @Test
  void shouldAcceptOnlyTheThreeLeafSizes() {
    for (int size = -1; size <= 64; size++) {
      final boolean expectedBlockSize = size == 8 || size == 16 || size == ROOT_BLOCK_SIZE;
      final boolean blockSize = Mcv2Decoder.isBlockSize(size);
      assertEquals(expectedBlockSize, blockSize);
    }
  }

  @Test
  void shouldSizePatternRecords() {
    final int expectedSmallSize = 6 + 1 + 1;
    final int smallSize = patternSize(8);
    assertEquals(expectedSmallSize, smallSize);

    final int expectedLargeSize = 6 + 1 + 4;
    final int largeSize = patternSize(ROOT_BLOCK_SIZE);
    assertEquals(expectedLargeSize, largeSize);

    final int expectedMediumSize = 6 + 1 + 2;
    final int mediumSize = patternSize(16);
    assertEquals(expectedMediumSize, mediumSize);
  }

  @Test
  void shouldSizeTheWalkRegion() {
    for (int descriptors = 0; descriptors < 40; descriptors++) {
      final byte[] modes = new byte[descriptors];
      final byte[] frame = Mcv2WireFrames.frame(
        1280,
        ROOT_BLOCK_SIZE,
        Mcv2FrameKind.KEYFRAME,
        modes,
        new byte[descriptors][0],
        new int[] { descriptors, 0, 0 }
      );
      final int expectedPayloadStart = 20 + 8 + 4 + 12 + descriptors + 4 * ((descriptors + 7) / 8);
      final int payloadStart = Mcv2Decoder.parse(frame).getPayloadStart();
      assertEquals(expectedPayloadStart, payloadStart);
    }
  }

  @Test
  void shouldSignExtendValues() {
    final int allOnesNibble = signed(0xF, 4);
    assertEquals(-1, allOnesNibble);

    final int largestNibble = signed(0x7, 4);
    assertEquals(7, largestNibble);

    final int smallestNibble = signed(0x8, 4);
    assertEquals(-8, smallestNibble);

    final int smallestShort = signed(0x8000, 16);
    assertEquals(-32768, smallestShort);

    final int largestShort = signed(0x7FFF, 16);
    assertEquals(32767, largestShort);

    final boolean wideValueNegative = signed(0x1FF, 8) < 0;
    assertTrue(wideValueNegative);

    final boolean largestByteNegative = signed(0x7F, 8) < 0;
    assertFalse(largestByteNegative);
  }
}
