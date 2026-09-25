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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Properties of the Bayer matrices at every size. Pass 1 found {@link BayerDither#NORMAL_8X8} to be the transpose of the
 * canonical matrix while the class's own generator produced the canonical one; the examples then pinned the three
 * constants. These check the generator itself against an independent closed form at every power of two up to 512, and
 * the constants against the generator.
 *
 * <p>The closed form reads the coordinates bit by bit: the most significant bit pair of {@code (x, y)} picks the entry of
 * the 2x2 Bayer pattern that becomes the least significant base-4 digit of the threshold, the next pair the next digit,
 * and so on. The 2x2 pattern is {@code [[0, 2], [3, 1]]}, which is {@code 2 * (x ^ y) + y} for single bits.
 */
final class BayerMatrixPropertyTest {

  private static final String SEED = "20260925";

  private static int closedForm(final int x, final int y, final int bits) {
    int threshold = 0;
    int weight = 1;
    for (int bit = bits - 1; bit >= 0; bit--) {
      final int xBit = (x >> bit) & 1;
      final int yBit = (y >> bit) & 1;
      final int pattern = 2 * (xBit ^ yBit) + yBit;
      threshold += pattern * weight;
      weight *= 4;
    }
    return threshold;
  }

  @Property(seed = SEED, generation = GenerationMode.EXHAUSTIVE)
  void theGeneratorMatchesTheClosedFormAndRanksEveryCellOnce(@ForAll @IntRange(min = 0, max = 9) final int bits) {
    final int size = 1 << bits;
    final int[][] matrix = BayerDither.createBayerMatrix(size);
    final boolean[] seen = new boolean[size * size];

    assertEquals(size, matrix.length, "rows");
    for (int y = 0; y < size; y++) {
      assertEquals(size, matrix[y].length, "columns");
      for (int x = 0; x < size; x++) {
        final int threshold = matrix[y][x];
        final int expected = closedForm(x, y, bits);
        final int column = x;
        final int row = y;
        assertEquals(expected, threshold, () -> "threshold at " + column + "," + row + " of the " + size + "x" + size + " matrix");
        final boolean inRange = threshold >= 0 && threshold < size * size;
        assertTrue(inRange, "thresholds rank the cells from 0");
        assertFalse(seen[threshold], "every threshold appears once");
        seen[threshold] = true;
      }
    }
  }

  @Provide
  Arbitrary<Integer> constantSizes() {
    final List<Integer> sizes = List.of(2, 4, 8);
    return Arbitraries.of(sizes);
  }

  @Property(seed = SEED, generation = GenerationMode.EXHAUSTIVE)
  void theConstantsAreTheGeneratorsMatricesCountedFromOne(@ForAll("constantSizes") final int size) {
    final ThresholdMatrix constant =
      switch (size) {
        case 2 -> BayerDither.NORMAL_2X2;
        case 4 -> BayerDither.NORMAL_4X4;
        default -> BayerDither.NORMAL_8X8;
      };
    final int max =
      switch (size) {
        case 2 -> BayerDither.NORMAL_2X2_MAX;
        case 4 -> BayerDither.NORMAL_4X4_MAX;
        default -> BayerDither.NORMAL_8X8_MAX;
      };
    final int[][] generated = BayerDither.createBayerMatrix(size);
    final int[][] entries = constant.toArray();

    assertEquals(size * size, max, "the level count of the constant");
    for (int y = 0; y < size; y++) {
      final int[] expected = new int[size];
      for (int x = 0; x < size; x++) {
        expected[x] = generated[y][x] + 1;
      }
      assertArrayEquals(expected, entries[y], "row " + y + " of the " + size + "x" + size + " constant");
    }
  }
}
