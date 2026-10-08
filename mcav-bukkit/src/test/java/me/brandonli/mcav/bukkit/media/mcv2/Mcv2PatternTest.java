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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class Mcv2PatternTest {

  private static final byte[] INLINE = { 1, 2, 3, 4, 5, 6, 0, (byte) 0b1010_0101 };

  private static int colorAt(final byte[] picture, final int width, final int x, final int y) {
    final int at = (y * width + x) * 3;
    return ((picture[at] & 255) << 16) | ((picture[at + 1] & 255) << 8) | (picture[at + 2] & 255);
  }

  @Test
  void resolvesAnInlineRecordAlongColumns() throws Mcv2Exception {
    final byte[] data = Mcv2WireFrames.block(8, 4, 0, INLINE, true);
    final byte[] picture = Mcv2Decoder.decode(data, null, 0);
    assertEquals(0x040506, colorAt(picture, 8, 0, 7));
    assertEquals(0x010203, colorAt(picture, 8, 1, 0));
    for (int y = 0; y < 8; y++) {
      for (int x = 0; x < 8; x++) {
        assertEquals(((INLINE[7] >> x) & 1) == 0 ? 0x010203 : 0x040506, colorAt(picture, 8, x, y));
      }
    }
  }

  @Test
  void resolvesTablesAlongRows() throws Mcv2Exception {
    final byte[] endpoints = { 0, 0, -1, -1, 0, (byte) 248, 31, 0 };
    final byte[][] words = { {}, { 0, 0, 1, 1, 1, 0 }, {} };
    final byte[] data = Mcv2WireFrames.frame(
      16,
      16,
      true,
      new byte[] { 6, 4, 0, 0, 0 },
      Mcv2WireFrames.records(5, 1, new byte[] { 1, 1 }),
      new int[] { 1, 4, 0 },
      endpoints,
      words
    );
    final byte[] picture = Mcv2Decoder.decode(data, null, 0);
    assertEquals(0x0000FF, colorAt(picture, 16, 5, 0));
    assertEquals(0xFF0000, colorAt(picture, 16, 0, 1));
  }

  @Test
  void rejectsBrokenRecords() {
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(Mcv2WireFrames.block(8, 4, 0, Arrays.copyOf(INLINE, 7), true)));
    final byte[] orientation = INLINE.clone();
    orientation[6] = 2;
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(Mcv2WireFrames.block(8, 4, 0, orientation, true)));
    for (final byte[] record : new byte[][] { { 2, 0 }, { 0, 3 } }) {
      final byte[] data = Mcv2WireFrames.frame(
        32,
        32,
        true,
        new byte[] { 4 },
        new byte[][] { record },
        new int[] { 1, 0, 0 },
        new byte[4],
        new byte[][] { {}, {}, { 0, 1, 2, 3, 4 } }
      );
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data));
    }
  }

  @Test
  void refusesARecordThatRunsPastTheEndOfTheData() {
    final byte[] shifted = { 0, 1, 2, 3, 4, 5, 6 };
    assertEquals(
      "Truncated record",
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(Mcv2WireFrames.block(8, 4, 0, shifted, true))).getMessage()
    );
  }
}
