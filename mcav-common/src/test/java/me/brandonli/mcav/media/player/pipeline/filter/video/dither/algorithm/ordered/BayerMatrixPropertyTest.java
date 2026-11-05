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

// Checks the generator against an independent closed form, whose bit pairs pick base-4 digits from the 2x2 pattern [[0, 2], [3, 1]].
final class BayerMatrixPropertyTest {

  private static final String SEED = "20260925";

  private static int closedForm(final int column, final int row, final int bits) {
    int threshold = 0;
    int weight = 1;
    for (int bit = bits - 1; bit >= 0; bit--) {
      final int xBit = (column >> bit) & 1;
      final int yBit = (row >> bit) & 1;
      final int pattern = 2 * (xBit ^ yBit) + yBit;
      threshold += pattern * weight;
      weight *= 4;
    }

    return threshold;
  }

  @Property(seed = SEED, generation = GenerationMode.EXHAUSTIVE)
  void shouldMatchTheClosedFormAndRankEveryCellOnceWithTheGenerator(@ForAll @IntRange(min = 0, max = 9) final int bits) {
    final int size = 1 << bits;
    final int[][] matrix = BayerDither.createBayerMatrix(size);
    final boolean[] seen = new boolean[size * size];

    assertEquals(size, matrix.length, "rows");

    for (int row = 0; row < size; row++) {
      final int columnCount = matrix[row].length;
      assertEquals(size, columnCount, "columns");
      for (int column = 0; column < size; column++) {
        final int threshold = matrix[row][column];
        final int expected = closedForm(column, row, bits);
        final int cellColumn = column;
        final int cellRow = row;
        assertEquals(expected, threshold, () -> "threshold at %s,%s of the %sx%s matrix".formatted(cellColumn, cellRow, size, size));
        final boolean inRange = threshold >= 0 && threshold < size * size;
        assertTrue(inRange, "thresholds rank the cells from 0");
        final boolean seenBefore = seen[threshold];
        assertFalse(seenBefore, "every threshold appears once");
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
  void shouldMakeTheConstantsTheGeneratorsMatricesCountedFromOne(@ForAll("constantSizes") final int size) {
    final ThresholdMatrix constant = switch (size) {
      case 2 -> BayerDither.NORMAL_2X2;
      case 4 -> BayerDither.NORMAL_4X4;
      default -> BayerDither.NORMAL_8X8;
    };
    final int maximum = switch (size) {
      case 2 -> BayerDither.NORMAL_2X2_MAXIMUM;
      case 4 -> BayerDither.NORMAL_4X4_MAXIMUM;
      default -> BayerDither.NORMAL_8X8_MAXIMUM;
    };
    final int[][] generated = BayerDither.createBayerMatrix(size);
    final int[][] entries = constant.toArray();

    final int expectedMaximum = size * size;
    assertEquals(expectedMaximum, maximum, "the level count of the constant");

    for (int row = 0; row < size; row++) {
      final int[] expected = new int[size];
      for (int column = 0; column < size; column++) {
        expected[column] = generated[row][column] + 1;
      }
      final int[] entryRow = entries[row];
      assertArrayEquals(expected, entryRow, "row %s of the %sx%s constant".formatted(row, size, size));
    }
  }
}
