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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The two-colour palette fit. */
final class MCV2PaletteFitTest {

  private static void fit(final int[] source, final int count, final int[] colors, final byte[] selectors) {
    final int[] tile = new int[8 * 8 * 3];
    for (int pixel = 0; pixel < 64; pixel++) {
      System.arraycopy(source, (pixel % count) * 3, tile, pixel * 3, 3);
    }
    final int[] endpoints = new int[6];
    Mcv2Internals.javaKernels().cluster(tile, 8, endpoints);
    Mcv2Internals.javaKernels().finishPalette(source, count, endpoints, colors, selectors);
  }

  private static int[] block(final int[]... pixels) {
    final int[] source = new int[pixels.length * 3];
    for (int index = 0; index < pixels.length; index++) {
      System.arraycopy(pixels[index], 0, source, index * 3, 3);
    }
    return source;
  }

  @Test
  void fitsTwoColoursExactly() {
    final int[] dark = { 10, 20, 30 };
    final int[] light = { 200, 210, 220 };
    final int[] source = block(light, dark, dark, light, light, dark, light, light);
    final int[] colors = new int[6];
    final byte[] selectors = new byte[8];
    fit(source, 8, colors, selectors);
    assertArrayEquals(new int[] { 10, 20, 30, 200, 210, 220 }, colors);
    assertArrayEquals(new byte[] { 1, 0, 0, 1, 1, 0, 1, 1 }, selectors);
  }

  @Test
  void meansEachClusterAndRoundsHalvesUp() {
    final int[] source = block(new int[] { 0, 0, 0 }, new int[] { 1, 1, 1 }, new int[] { 250, 250, 250 }, new int[] { 255, 255, 255 });
    final int[] colors = new int[6];
    final byte[] selectors = new byte[4];
    fit(source, 4, colors, selectors);
    assertArrayEquals(new int[] { 1, 1, 1, 253, 253, 253 }, colors);
    assertArrayEquals(new byte[] { 0, 0, 1, 1 }, selectors);
  }

  @Test
  void keepsTheSeedOfAnEmptyCluster() {
    // equal luma everywhere: both endpoints start at the first pixel, so the first iteration puts every pixel in
    // cluster 0 and endpoint 1 keeps its seed; from the second iteration on, the first pixel has a cluster of its own
    final int[] source = block(new int[] { 100, 50, 0 }, new int[] { 0, 50, 100 }, new int[] { 50, 50, 50 }, new int[] { 50, 50, 50 });
    final int[] colors = new int[6];
    final byte[] selectors = new byte[4];
    fit(source, 4, colors, selectors);
    assertArrayEquals(new int[] { 33, 50, 67, 100, 50, 0 }, colors);
    assertArrayEquals(new byte[] { 1, 0, 0, 0 }, selectors);
  }

  @Test
  void fitsAUniformBlockWithOneColour() {
    final int[] colors = new int[6];
    final byte[] selectors = new byte[2];
    fit(block(new int[] { 7, 8, 9 }, new int[] { 7, 8, 9 }), 2, colors, selectors);
    assertArrayEquals(new int[] { 7, 8, 9, 7, 8, 9 }, colors);
    assertArrayEquals(new byte[] { 0, 0 }, selectors);
  }

  /** The pattern assignment agrees with the plain one whenever the selectors repeat along an axis, and says when not. */
  @Test
  void assignsPatternsAndStopsWhereNoneCanHold() {
    final int size = 8;
    final int[] dark = { 10, 20, 30 };
    final int[] light = { 200, 210, 220 };
    for (final Layout layout : Layout.values()) {
      final int[] source = new int[size * size * 3];
      for (int row = 0; row < size; row++) {
        for (int column = 0; column < size; column++) {
          final boolean bright = switch (layout) {
            case STRIPED_COLUMNS -> (column & 1) == 0;
            case STRIPED_ROWS -> (row & 2) == 0;
            case CHECKERBOARD -> ((column ^ row) & 1) == 0;
          };
          System.arraycopy(bright ? light : dark, 0, source, (row * size + column) * 3, 3);
        }
      }
      final int[] endpoints = new int[6];
      Mcv2Internals.javaKernels().cluster(source, size, endpoints);
      final int[] colors = new int[6];
      final byte[] selectors = new byte[size * size];
      final int[] plainColors = new int[6];
      final byte[] plain = new byte[size * size];
      Mcv2Internals.javaKernels().finishPalette(source, size * size, endpoints, plainColors, plain);
      final boolean pattern = Mcv2Internals.javaKernels().finishPattern(source, size, endpoints, colors, selectors);
      assertArrayEquals(plainColors, colors);
      if (layout == Layout.CHECKERBOARD) {
        assertEquals(false, pattern);
      } else {
        assertEquals(true, pattern);
        assertArrayEquals(plain, selectors);
      }
    }
  }

  private enum Layout {
    STRIPED_COLUMNS,
    STRIPED_ROWS,
    CHECKERBOARD,
  }
}
