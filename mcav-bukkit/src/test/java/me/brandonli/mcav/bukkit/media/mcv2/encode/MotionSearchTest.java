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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
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
  void packsBothPartsOfAVectorIntoOneInt() {
    assertEquals(0x0005FFFD, MotionSearch.pack(5, -3));
    final int[][] vectors = { { 0, 0 }, { 5, -3 }, { -7, 12 }, { -32768, 32767 }, { 32767, -32768 } };
    for (final int[] vector : vectors) {
      final int packed = MotionSearch.pack(vector[0], vector[1]);
      assertEquals(vector[0], MotionSearch.unpackX(packed));
      assertEquals(vector[1], MotionSearch.unpackY(packed));
    }
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
    for (int row = 0; row < HEIGHT; row++) {
      for (int column = 0; column < WIDTH; column++) {
        final int at = (row * WIDTH + column) * 3;
        rgb[at] = (byte) (2 * (int) (60 + 50 * Math.sin(column / 9.0) + 10 * Math.cos(row / 13.0)));
        rgb[at + 1] = (byte) (2 * (int) (60 + 40 * Math.cos(row / 8.0) + 15 * Math.sin(column / 11.0)));
        rgb[at + 2] = (byte) (2 * (int) (60 + 30 * Math.sin((column + row) / 10.0)));
      }
    }
    return rgb;
  }

  /** The block at (blockLeft, blockTop) of the picture displaced by a vector in half pixels, averaging like the decoder. */
  private static int[] block(
    final byte[] rgb,
    final int blockLeft,
    final int blockTop,
    final int size,
    final int motionX,
    final int motionY
  ) {
    final int[] source = new int[size * size * 3];
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final int halfPixelX = 2 * (blockLeft + column) + motionX;
        final int halfPixelY = 2 * (blockTop + row) + motionY;
        for (int channel = 0; channel < 3; channel++) {
          final int sum =
            (rgb[((halfPixelY >> 1) * WIDTH + (halfPixelX >> 1)) * 3 + channel] & 0xFF) +
            (rgb[((halfPixelY >> 1) * WIDTH + ((halfPixelX + 1) >> 1)) * 3 + channel] & 0xFF) +
            (rgb[(((halfPixelY + 1) >> 1) * WIDTH + (halfPixelX >> 1)) * 3 + channel] & 0xFF) +
            (rgb[(((halfPixelY + 1) >> 1) * WIDTH + ((halfPixelX + 1) >> 1)) * 3 + channel] & 0xFF);
          source[(row * size + column) * 3 + channel] = sum / 4;
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

  private static int seeded(final int[] source, final int range, final boolean refinesHalfPixels, final int... seeds) {
    return MotionSearch.seeded(picture(), WIDTH, HEIGHT, source, 32, 32, 16, 0, 0, range, refinesHalfPixels, seeds);
  }

  private static int vector(final int vectorX, final int vectorY) {
    return (vectorX << 16) | (vectorY & 0xFFFF);
  }

  @Test
  void seededSearchStartsFromTheBestSeed() {
    // the true vector is far from zero but one seed is next to it: the diamond walks the last pixel
    final int[] source = block(picture(), 32, 32, 16, 20, -14);
    assertEquals(vector(20, -14), seeded(source, 24, true, vector(0, 0), vector(18, -14), vector(18, -14), vector(-30, 40)));
    // a half-pixel vector is found only by the refinement; without it the vector stays on whole pixels
    final int[] half = block(picture(), 32, 32, 16, 7, -4);
    assertEquals(vector(7, -4), seeded(half, 24, true, vector(6, -4)));
    final int whole = seeded(half, 24, false, vector(6, -4));
    assertEquals(0, (whole >> 16) & 1);
    assertEquals(0, (short) whole & 1);
  }

  @Test
  void seededSearchStaysInsideTheRange() {
    // seeds past the range are clamped to its edge, and so is every step
    final int[] source = block(picture(), 32, 32, 16, 20, 0);
    final int found = seeded(source, 2, true, vector(100, 0), vector(-100, 0));
    assertEquals(4, found >> 16);
    // with a range of one pixel the walk takes at most two steps, both improving here
    final int[] near = block(picture(), 32, 32, 16, 2, 2);
    assertEquals(vector(2, 2), seeded(near, 1, false));
  }

  @Test
  void measuresBlocksNearTheEdgeWithClamping() {
    // a block in the corner, displaced so its samples clamp: the general sampling path
    final byte[] rgb = picture();
    final int[] source = new int[8 * 8 * 3];
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        final int referenceRow = Math.max(0, row - 4);
        final int referenceColumn = Math.max(0, column - 4);
        for (int channel = 0; channel < 3; channel++) {
          source[(row * 8 + column) * 3 + channel] = rgb[(referenceRow * WIDTH + referenceColumn) * 3 + channel] & 0xFF;
        }
      }
    }
    final int found = MotionSearch.seeded(rgb, WIDTH, HEIGHT, source, 0, 0, 8, 0, 0, 4, true, new int[] { vector(-8, -8) });
    assertEquals(true, Math.abs(found >> 16) <= 8 && Math.abs((short) found) <= 8);
    assertEquals(vector(-8, -8), found, "the clamped corner has an exact four-pixel displacement");
  }
}
