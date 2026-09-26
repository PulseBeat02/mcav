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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Compact records: the control byte, the motion forms, the body lengths and every rule a record must follow. */
final class CompactRecordTest {

  @Test
  void parsesTheThreeMotionForms() throws Mcv2Exception {
    assertEquals(new CompactRecord(CompactRecord.DC_Y, 0, 0, 0, 1, 2), CompactRecord.parse(new byte[] { 0x00, 5 }, 0, 3));
    // form 1: signed nibbles, x low and y high: 0xF9 is x = -7, y = -1
    assertEquals(
      new CompactRecord(CompactRecord.GRID2_YC, 1, -7, -1, 3, 8),
      CompactRecord.parse(new byte[] { 9, 0x11, (byte) 0xF9, 0, 0, 0, 0, 0, 0 }, 1, 0)
    );
    // form 2: two signed bytes
    assertEquals(
      new CompactRecord(CompactRecord.LOW2, 2, -128, 127, 3, 8),
      CompactRecord.parse(new byte[] { 0x28, (byte) 0x80, 127, 0, 0, 0, 0, 0 }, 0, 7)
    );
  }

  @Test
  void knowsEveryBodyLength() {
    final int[] expected = { 1, 6, 10, 8, 18, 4, 5, 4, 5 };
    for (int kind = 0; kind < expected.length; kind++) {
      assertEquals(expected[kind], CompactRecord.bodyBytes(kind), "class " + kind);
    }
  }

  @ParameterizedTest(name = "{3}")
  @CsvSource(
    {
      "00,  -1, 0, negative offset",
      "00,   1, 0, offset at the end",
      "00,   0, -1, negative quantizer",
      "00,   0, 8, quantizer above 7",
      "0900, 0, 0, class 9",
      "3000, 0, 0, motion form 3",
      "0700000000, 0, 1, gain with a quantizer",
      "010000000000, 0, 0, truncated body",
      "0500000040, 0, 0, vector index 64",
      "060000000010, 0, 0, product padding",
    }
  )
  void rejectsBrokenRecords(final String hex, final int offset, final int q, final String name) {
    final byte[] data = HexFormat.of().parseHex(hex);
    assertThrows(Mcv2Exception.class, () -> CompactRecord.parse(data, offset, q), name);
  }

  @Test
  void acceptsTheLargestIndexes() throws Mcv2Exception {
    assertEquals(5, CompactRecord.parse(new byte[] { 5, 0, 0, 0, 63 }, 0, 0).length());
    assertEquals(6, CompactRecord.parse(new byte[] { 6, 0, 0, 0, (byte) 0xFF, 0x0F }, 0, 0).length());
  }
}
