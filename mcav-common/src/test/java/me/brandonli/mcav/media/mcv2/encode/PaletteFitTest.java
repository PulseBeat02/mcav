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

import me.brandonli.mcav.media.mcv2.Mcv2Format;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/** The two-colour palette fit. */
final class PaletteFitTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(PaletteFit.class);
  }

  private static int[] block(final int[]... pixels) {
    final int[] source = new int[pixels.length * 3];
    for (int i = 0; i < pixels.length; i++) {
      System.arraycopy(pixels[i], 0, source, i * 3, 3);
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
    PaletteFit.fit(source, 8, false, colors, selectors);
    assertArrayEquals(new int[] { 10, 20, 30, 200, 210, 220 }, colors);
    assertArrayEquals(new byte[] { 1, 0, 0, 1, 1, 0, 1, 1 }, selectors);
  }

  @Test
  void meansEachClusterAndRoundsHalvesUp() {
    final int[] source = block(new int[] { 0, 0, 0 }, new int[] { 1, 1, 1 }, new int[] { 250, 250, 250 }, new int[] { 255, 255, 255 });
    final int[] colors = new int[6];
    final byte[] selectors = new byte[4];
    PaletteFit.fit(source, 4, false, colors, selectors);
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
    PaletteFit.fit(source, 4, false, colors, selectors);
    assertArrayEquals(new int[] { 33, 50, 67, 100, 50, 0 }, colors);
    assertArrayEquals(new byte[] { 1, 0, 0, 0 }, selectors);
  }

  @Test
  void fitsAUniformBlockWithOneColour() {
    final int[] colors = new int[6];
    final byte[] selectors = new byte[2];
    PaletteFit.fit(block(new int[] { 7, 8, 9 }, new int[] { 7, 8, 9 }), 2, false, colors, selectors);
    assertArrayEquals(new int[] { 7, 8, 9, 7, 8, 9 }, colors);
    assertArrayEquals(new byte[] { 0, 0 }, selectors);
  }

  @Test
  void quantizesTheEndpointsToRgb565() {
    final int[] source = block(new int[] { 13, 130, 77 }, new int[] { 13, 130, 77 }, new int[] { 201, 7, 250 }, new int[] { 201, 7, 250 });
    final int[] colors = new int[6];
    final byte[] selectors = new byte[4];
    PaletteFit.fit(source, 4, true, colors, selectors);
    for (int e = 0; e < 2; e++) {
      final int r = colors[e * 3] >> 3;
      final int g = colors[e * 3 + 1] >> 2;
      final int b = colors[e * 3 + 2] >> 3;
      final int packed = (r << 11) | (g << 5) | b;
      assertEquals((colors[e * 3] << 16) | (colors[e * 3 + 1] << 8) | colors[e * 3 + 2], Mcv2Format.unpack565(packed & 0xFF, packed >> 8));
    }
    assertArrayEquals(new int[] { 8, 130, 74, 206, 4, 255 }, colors);
    assertArrayEquals(new byte[] { 0, 0, 1, 1 }, selectors);
  }
}
