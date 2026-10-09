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

  private static int colorAt(final byte[] picture, final int width, final int column, final int row) {
    final int offset = (row * width + column) * 3;
    return ((picture[offset] & 255) << 16) | ((picture[offset + 1] & 255) << 8) | (picture[offset + 2] & 255);
  }

  @Test
  void resolvesAnInlineRecordAlongColumns() throws Mcv2Exception {
    final byte[] data = Mcv2WireFrames.block(8, 4, 0, INLINE, true);
    final byte[] picture = Mcv2Decoder.decode(data, null, 0);
    assertEquals(0x040506, colorAt(picture, 8, 0, 7));
    assertEquals(0x010203, colorAt(picture, 8, 1, 0));
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        assertEquals(((INLINE[7] >> column) & 1) == 0 ? 0x010203 : 0x040506, colorAt(picture, 8, column, row));
      }
    }
  }

  @Test
  void rejectsBrokenRecords() {
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(Mcv2WireFrames.block(8, 4, 0, Arrays.copyOf(INLINE, 7), true)));
    final byte[] orientation = INLINE.clone();
    orientation[6] = 2;
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(Mcv2WireFrames.block(8, 4, 0, orientation, true)));
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
