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
package me.brandonli.mcav.media.mcv2.encode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/** The local motion search: its step ladder, and the vectors it finds on smooth pictures. */
final class MotionSearchTest {

  private static final int WIDTH = 96;
  private static final int HEIGHT = 96;

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(MotionSearch.class);
  }

  @Test
  void stepsHalveFromTheRangeInHalfPixels() {
    assertArrayEquals(new int[0], MotionSearch.steps(0, true));
    assertArrayEquals(new int[] { 32, 16, 8, 4, 2, 1 }, MotionSearch.steps(24, true));
    assertArrayEquals(new int[] { 32, 16, 8, 4, 2 }, MotionSearch.steps(24, false));
    assertArrayEquals(new int[] { 2, 1 }, MotionSearch.steps(1, true));
    assertArrayEquals(new int[] { 64, 32, 16, 8, 4, 2 }, MotionSearch.steps(63, false));
  }

  /** A smooth picture whose values are all even, so every half-pixel average is an integer. */
  private static byte[] picture() {
    final byte[] rgb = new byte[WIDTH * HEIGHT * 3];
    for (int y = 0; y < HEIGHT; y++) {
      for (int x = 0; x < WIDTH; x++) {
        final int at = (y * WIDTH + x) * 3;
        rgb[at] = (byte) (2 * (int) (60 + 50 * Math.sin(x / 9.0) + 10 * Math.cos(y / 13.0)));
        rgb[at + 1] = (byte) (2 * (int) (60 + 40 * Math.cos(y / 8.0) + 15 * Math.sin(x / 11.0)));
        rgb[at + 2] = (byte) (2 * (int) (60 + 30 * Math.sin((x + y) / 10.0)));
      }
    }
    return rgb;
  }

  /** The block at (x, y) of the picture displaced by a vector in half pixels, averaging like the decoder. */
  private static int[] block(final byte[] rgb, final int x, final int y, final int size, final int mx, final int my) {
    final int[] source = new int[size * size * 3];
    for (int py = 0; py < size; py++) {
      for (int px = 0; px < size; px++) {
        final int hx = 2 * (x + px) + mx;
        final int hy = 2 * (y + py) + my;
        for (int c = 0; c < 3; c++) {
          final int sum =
            (rgb[((hy >> 1) * WIDTH + (hx >> 1)) * 3 + c] & 0xFF) +
            (rgb[((hy >> 1) * WIDTH + ((hx + 1) >> 1)) * 3 + c] & 0xFF) +
            (rgb[(((hy + 1) >> 1) * WIDTH + (hx >> 1)) * 3 + c] & 0xFF) +
            (rgb[(((hy + 1) >> 1) * WIDTH + ((hx + 1) >> 1)) * 3 + c] & 0xFF);
          source[(py * size + px) * 3 + c] = sum / 4;
        }
      }
    }
    return source;
  }

  private static int search(final int[] source, final int globalX, final int globalY, final int range) {
    return MotionSearch.search(picture(), WIDTH, HEIGHT, source, 32, 32, 16, globalX, globalY, range, MotionSearch.steps(range, true));
  }

  @Test
  void findsAWholePixelDisplacement() {
    final int[] source = block(picture(), 32, 32, 16, 10, -6);
    assertEquals((10 << 16) | (-6 & 0xFFFF), search(source, 0, 0, 8));
  }

  @Test
  void findsAHalfPixelDisplacement() {
    for (final int[] vector : new int[][] { { 5, 0 }, { 0, -3 }, { 7, 9 } }) {
      final int[] source = block(picture(), 32, 32, 16, vector[0], vector[1]);
      assertEquals((vector[0] << 16) | (vector[1] & 0xFFFF), search(source, 0, 0, 8));
    }
  }

  @Test
  void searchesAroundTheGlobalVectorWithinTheRange() {
    final int[] source = block(picture(), 32, 32, 16, 12, 0);
    // two pixels around a global vector of four: the true six pixels are out of reach, the edge of the range is best
    assertEquals(12 << 16, search(source, 8, 0, 2));
    // without a range there is no search at all
    assertEquals((8 << 16) | (-2 & 0xFFFF), search(source, 8, -2, 0));
  }
}
