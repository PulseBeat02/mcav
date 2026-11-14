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

import org.junit.jupiter.api.Test;

final class Mcv2PatternTest {

  private static final byte[] INLINE = { 1, 2, 3, 4, 5, 6, 0, (byte) 0b1010_0101 };

  private int colorAt(final byte[] picture, final int width, final int column, final int row) {
    final int offset = (row * width + column) * 3;
    return ((picture[offset] & 255) << 16) | ((picture[offset + 1] & 255) << 8) | (picture[offset + 2] & 255);
  }

  @Test
  void shouldResolveAnInlineRecordAlongColumns() {
    final byte[] data = Mcv2WireFrames.block(8, 4, 0, INLINE, Mcv2FrameKind.KEYFRAME);
    final byte[] picture = Mcv2Decoder.decode(data, null, 0);
    final int lastRowColor = this.colorAt(picture, 8, 0, 7);
    assertEquals(0x040506, lastRowColor);

    final int secondColumnColor = this.colorAt(picture, 8, 1, 0);
    assertEquals(0x010203, secondColumnColor);

    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        final int expected = ((INLINE[7] >> column) & 1) == 0 ? 0x010203 : 0x040506;
        final int color = this.colorAt(picture, 8, column, row);
        assertEquals(expected, color);
      }
    }
  }

  @Test
  void shouldRefuseARecordThatRunsPastTheEndOfTheData() {
    final byte[] shifted = { 0, 1, 2, 3, 4, 5, 6 };
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () ->
      Mcv2Decoder.parse(Mcv2WireFrames.block(8, 4, 0, shifted, Mcv2FrameKind.KEYFRAME))
    );
    final String message = exception.getMessage();
    assertEquals("Truncated record!", message);
  }
}
