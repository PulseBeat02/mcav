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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The cheap fits of the live search: cell means for grids, a short integer clustering for palettes. */
final class FastFitsTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(FastFits.class);
  }

  /** A block whose channels are its cell's index (0 to 15) times one, two and three. */
  private static int[] cells(final int size) {
    final int[] source = new int[size * size * 3];
    final int cell = size / 4;
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        final int index = (y / cell) * 4 + x / cell;
        for (int c = 0; c < 3; c++) {
          source[(y * size + x) * 3 + c] = index * (c + 1);
        }
      }
    }
    return source;
  }

  @ParameterizedTest
  @ValueSource(ints = { 8, 16, 32 })
  void fitsGridsOfCellMeans(final int size) {
    final int[] sums = new int[48];
    FastFits.cellSums(cells(size), size, sums);
    final int area = (size / 4) * (size / 4);
    for (int i = 0; i < 16; i++) {
      assertEquals(i * area, sums[i * 3]);
      assertEquals(3 * i * area, sums[i * 3 + 2]);
    }
    final float[] four = new float[48];
    FastFits.grid(sums, size, 4, four);
    assertEquals(5.0f, four[5 * 3]);
    assertEquals(15.0f, four[15 * 3 + 1] / 2);
    final float[] two = new float[12];
    FastFits.grid(sums, size, 2, two);
    // the top-left 2x2 node averages cells 0, 1, 4 and 5
    assertEquals(2.5f, two[0]);
    assertEquals(12.5f * 3, two[3 * 3 + 2]);
    final float[] one = new float[3];
    FastFits.grid(sums, size, 1, one);
    assertArrayEquals(new float[] { 7.5f, 15.0f, 22.5f }, one);
  }

  @ParameterizedTest
  @ValueSource(ints = { 8, 16, 32 })
  void fitsTheLumaResidualOfCompactRecords(final int size) {
    // the source is the prediction plus the cell index on every channel: luma (r + 2 g + b) / 4 rises by the index
    final int[] prediction = new int[size * size * 3];
    final int[] source = cells(size);
    for (int i = 0; i < source.length; i++) {
      source[i] = 100 + source[i] / ((i % 3) + 1);
      prediction[i] = 400;
    }
    final float[] nodes = new float[16];
    FastFits.lumaResidual(source, prediction, size, new int[48], nodes);
    for (int i = 0; i < 16; i++) {
      assertEquals(i, nodes[i], 1e-6);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = { 8, 16, 32 })
  void clustersTwoColours(final int size) {
    final int[] source = new int[size * size * 3];
    for (int i = 0; i < size * size; i++) {
      final boolean bright = (i % size) >= size / 2;
      source[i * 3] = bright ? 200 : 10;
      source[i * 3 + 1] = bright ? 210 : 20;
      source[i * 3 + 2] = bright ? 220 : 30;
    }
    final float[] endpoints = new float[6];
    FastFits.cluster(source, size, new long[8], endpoints);
    assertArrayEquals(new float[] { 10, 20, 30, 200, 210, 220 }, endpoints);
  }

  @Test
  void keepsTheSeedsOfAnEmptyCluster() {
    // one colour everywhere: both seeds are that colour, every pixel joins the first, the second keeps its seed
    final int[] source = new int[8 * 8 * 3];
    java.util.Arrays.fill(source, 77);
    final float[] endpoints = new float[6];
    FastFits.cluster(source, 8, new long[8], endpoints);
    assertArrayEquals(new float[] { 77, 77, 77, 77, 77, 77 }, endpoints);
  }
}
