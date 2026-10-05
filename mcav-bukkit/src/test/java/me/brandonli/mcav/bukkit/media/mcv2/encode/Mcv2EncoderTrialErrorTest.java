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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format;
import org.junit.jupiter.api.Test;

/**
 * Tests the reference's trial error, which decides the trial a pre-encoded frame keeps: the frame is padded to whole
 * 32-pixel blocks by repeating its last column and row, so their pixels count as often as they are repeated.
 */
final class Mcv2EncoderTrialErrorTest {

  @Test
  void weighsTheLastColumnAndRowByTheBlocksTheyPad() {
    // 33x2 is padded to 64x32: the last column stands for 1 + 31 columns, the last row for 1 + 30 rows
    final long once = errorOfOnePixel(33, 2, 0, 0);
    assertTrue(once > 0, "a pixel off counts");
    assertEquals(32 * once, errorOfOnePixel(33, 2, 32, 0));
    assertEquals(31 * once, errorOfOnePixel(33, 2, 0, 1));
    assertEquals(32 * 31 * once, errorOfOnePixel(33, 2, 32, 1));
  }

  @Test
  void weighsEveryPixelOnceInAPictureOfWholeBlocks() {
    assertEquals(errorOfOnePixel(32, 32, 0, 0), errorOfOnePixel(32, 32, 31, 31));
  }

  /** The trial error of a black picture and the same picture with one pixel off. */
  private static long errorOfOnePixel(final int width, final int height, final int column, final int row) {
    final byte[] source = new byte[width * height * Mcv2Format.CHANNELS];
    final byte[] picture = source.clone();
    final int at = (row * width + column) * Mcv2Format.CHANNELS;
    picture[at] = 50;
    picture[at + 1] = 60;
    picture[at + 2] = 70;
    return Mcv2Encoder.trialError(source, picture, width, height);
  }
}
