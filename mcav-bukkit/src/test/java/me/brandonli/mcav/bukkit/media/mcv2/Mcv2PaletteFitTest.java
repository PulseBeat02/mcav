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

final class Mcv2PaletteFitTest {

  private static final int ENDPOINTS_LENGTH = 6;

  private static final int DARK_RED = 10;
  private static final int DARK_GREEN = 20;
  private static final int DARK_BLUE = 30;

  private static final int LIGHT_RED = 200;
  private static final int LIGHT_GREEN = 210;
  private static final int LIGHT_BLUE = 220;

  private static void fit(final int[] source, final int count, final int[] colors, final byte[] selectors) {
    final int[] tile = new int[8 * 8 * 3];
    for (int pixel = 0; pixel < 64; pixel++) {
      System.arraycopy(source, (pixel % count) * 3, tile, pixel * 3, 3);
    }

    final int[] endpoints = new int[ENDPOINTS_LENGTH];
    Mcv2Internals.javaKernels().cluster(tile, 8, endpoints);
    Mcv2Internals.javaKernels().finishPalette(source, count, endpoints, colors, selectors);
  }

  private int[] block(final int[]... pixels) {
    final int[] source = new int[pixels.length * 3];
    for (int index = 0; index < pixels.length; index++) {
      System.arraycopy(pixels[index], 0, source, index * 3, 3);
    }

    return source;
  }

  @Test
  void shouldFitTwoColorsExactly() {
    final int[] dark = { DARK_RED, DARK_GREEN, DARK_BLUE };
    final int[] light = { LIGHT_RED, LIGHT_GREEN, LIGHT_BLUE };
    final int[] source = this.block(light, dark, dark, light, light, dark, light, light);
    final int[] colors = new int[ENDPOINTS_LENGTH];
    final byte[] selectors = new byte[8];
    fit(source, 8, colors, selectors);
    final int[] expectedColors = { DARK_RED, DARK_GREEN, DARK_BLUE, LIGHT_RED, LIGHT_GREEN, LIGHT_BLUE };
    assertArrayEquals(expectedColors, colors);

    final byte[] expectedSelectors = { 1, 0, 0, 1, 1, 0, 1, 1 };
    assertArrayEquals(expectedSelectors, selectors);
  }

  @Test
  void shouldAverageEachClusterAndRoundHalvesUp() {
    final int[] source = this.block(new int[] { 0, 0, 0 }, new int[] { 1, 1, 1 }, new int[] { 250, 250, 250 }, new int[] { 255, 255, 255 });
    final int[] colors = new int[ENDPOINTS_LENGTH];
    final byte[] selectors = new byte[4];
    fit(source, 4, colors, selectors);
    final int[] expectedColors = { 1, 1, 1, 253, 253, 253 };
    assertArrayEquals(expectedColors, colors);

    final byte[] expectedSelectors = { 0, 0, 1, 1 };
    assertArrayEquals(expectedSelectors, selectors);
  }

  @Test
  void shouldKeepTheSeedOfAnEmptyCluster() {
    // equal luma everywhere: both endpoints start at the first pixel, so the first iteration puts every pixel in
    // cluster 0 and endpoint 1 keeps its seed; from the second iteration on, the first pixel has a cluster of its own
    final int[] source = this.block(new int[] { 100, 50, 0 }, new int[] { 0, 50, 100 }, new int[] { 50, 50, 50 }, new int[] { 50, 50, 50 });
    final int[] colors = new int[ENDPOINTS_LENGTH];
    final byte[] selectors = new byte[4];
    fit(source, 4, colors, selectors);
    final int[] expectedColors = { 33, 50, 67, 100, 50, 0 };
    assertArrayEquals(expectedColors, colors);

    final byte[] expectedSelectors = { 1, 0, 0, 0 };
    assertArrayEquals(expectedSelectors, selectors);
  }

  @Test
  void shouldFitAUniformBlockWithOneColor() {
    final int[] colors = new int[ENDPOINTS_LENGTH];
    final byte[] selectors = new byte[2];
    fit(this.block(new int[] { 7, 8, 9 }, new int[] { 7, 8, 9 }), 2, colors, selectors);
    final int[] expectedColors = { 7, 8, 9, 7, 8, 9 };
    assertArrayEquals(expectedColors, colors);

    final byte[] expectedSelectors = { 0, 0 };
    assertArrayEquals(expectedSelectors, selectors);
  }

  @Test
  void shouldAssignPatternsAndStopWhereNoneCanHold() {
    final int size = 8;
    final int[] dark = { DARK_RED, DARK_GREEN, DARK_BLUE };
    final int[] light = { LIGHT_RED, LIGHT_GREEN, LIGHT_BLUE };
    for (final Layout layout : Layout.values()) {
      final int[] source = this.layoutSource(layout, size, dark, light);
      final int[] endpoints = new int[ENDPOINTS_LENGTH];
      Mcv2Internals.javaKernels().cluster(source, size, endpoints);
      final int[] colors = new int[ENDPOINTS_LENGTH];
      final byte[] selectors = new byte[size * size];
      final int[] plainColors = new int[ENDPOINTS_LENGTH];
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

  private int[] layoutSource(final Layout layout, final int size, final int[] dark, final int[] light) {
    final int[] source = new int[size * size * 3];
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final boolean bright = this.isBright(layout, row, column);
        System.arraycopy(bright ? light : dark, 0, source, (row * size + column) * 3, 3);
      }
    }

    return source;
  }

  private boolean isBright(final Layout layout, final int row, final int column) {
    return switch (layout) {
      case STRIPED_COLUMNS -> (column & 1) == 0;
      case STRIPED_ROWS -> (row & 2) == 0;
      case CHECKERBOARD -> ((column ^ row) & 1) == 0;
    };
  }

  private enum Layout {
    STRIPED_COLUMNS,
    STRIPED_ROWS,
    CHECKERBOARD,
  }
}
