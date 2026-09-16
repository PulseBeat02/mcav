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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.ThresholdMatrix;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ThresholdMatrix}.
 */
final class ThresholdMatrixTest {

  @Test
  void keepsItsOwnCopyOfTheRows() {
    final int[][] rows = { { 1, 2, 3 }, { 4, 5, 6 } };
    final ThresholdMatrix matrix = ThresholdMatrix.of(rows);
    rows[0][0] = 99;
    final int[][] entries = matrix.toArray();
    final int[][] expected = { { 1, 2, 3 }, { 4, 5, 6 } };
    assertArrayEquals(expected, entries, "changing the rows it was created from does not change the matrix");
  }

  @Test
  void handsOutCopiesOfItsEntries() {
    final int[][] first = BayerDither.NORMAL_2X2.toArray();
    first[0][0] = 99;
    final int[][] second = BayerDither.NORMAL_2X2.toArray();
    final int[][] expected = { { 1, 3 }, { 4, 2 } };
    assertArrayEquals(expected, second, "the shared matrices of BayerDither cannot be changed through a copy");
  }

  @Test
  void reportsItsSize() {
    final ThresholdMatrix matrix = ThresholdMatrix.of(new int[] { 0, 1, 2 }, new int[] { 3, 4, 5 });
    final int rows = matrix.getRowCount();
    final int columns = matrix.getColumnCount();
    assertEquals(2, rows);
    assertEquals(3, columns);
  }

  @Test
  void rejectsMissingRows() {
    final int[] row = { 1 };
    assertThrows(NullPointerException.class, () -> ThresholdMatrix.of((int[][]) null));
    assertThrows(NullPointerException.class, () -> ThresholdMatrix.of(row, null));
  }

  @Test
  void rejectsMatricesThatAreNotRectangular() {
    final int[] empty = {};
    final int[] shortRow = { 1 };
    final int[] longRow = { 1, 2 };
    assertThrows(IllegalArgumentException.class, ThresholdMatrix::of);
    assertThrows(IllegalArgumentException.class, () -> ThresholdMatrix.of(empty));
    assertThrows(IllegalArgumentException.class, () -> ThresholdMatrix.of(longRow, shortRow));
  }
}
