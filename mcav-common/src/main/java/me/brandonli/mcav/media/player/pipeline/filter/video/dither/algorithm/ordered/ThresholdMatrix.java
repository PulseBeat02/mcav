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

import com.google.common.base.Preconditions;

/**
 * An immutable matrix of thresholds for ordered dithering, whose entries rank the pixels of a pattern that is tiled
 * across the image. The matrices of {@link BayerDither} are threshold matrices; pass one to
 * {@link PixelMapper#ofPixelMapper(ThresholdMatrix, int, float)}.
 *
 * <p>A matrix copies the rows it is created from and hands out copies, so nobody can change a matrix once it exists,
 * including the shared matrices of {@link BayerDither}.
 */
public final class ThresholdMatrix {

  private final int[][] rows;

  private ThresholdMatrix(final int[][] rows) {
    this.rows = rows;
  }

  /**
   * Creates a matrix from its rows, which are copied.
   *
   * @param rows the rows of the matrix, which must all have the same, positive length
   * @return the matrix
   * @throws NullPointerException     if the rows or one of them is null
   * @throws IllegalArgumentException if there are no rows, or the rows are empty or differ in length
   */
  public static ThresholdMatrix of(final int[]... rows) {
    Preconditions.checkNotNull(rows, "Rows must not be null");
    Preconditions.checkArgument(rows.length > 0, "A matrix needs at least one row");
    final int[][] copy = copyRows(rows);
    checkRectangular(copy);
    return new ThresholdMatrix(copy);
  }

  private static int[][] copyRows(final int[][] rows) {
    final int[][] copy = new int[rows.length][];
    for (int index = 0; index < rows.length; index++) {
      final int[] row = rows[index];
      Preconditions.checkNotNull(row, "Row %s must not be null", index);
      copy[index] = row.clone();
    }
    return copy;
  }

  private static void checkRectangular(final int[][] rows) {
    final int columns = rows[0].length;
    Preconditions.checkArgument(columns > 0, "The rows of a matrix must not be empty");
    for (final int[] row : rows) {
      Preconditions.checkArgument(row.length == columns, "Every row of a matrix must have %s entries", columns);
    }
  }

  /**
   * Gets the number of rows, which is the height of the pattern.
   *
   * @return the number of rows
   */
  public int getRowCount() {
    return this.rows.length;
  }

  /**
   * Gets the number of entries of every row, which is the width of the pattern.
   *
   * @return the number of columns
   */
  public int getColumnCount() {
    return this.rows[0].length;
  }

  /**
   * Gets a copy of the entries, indexed by row and then column. Changing the copy does not change this matrix.
   *
   * @return a new array with the entries
   */
  public int[][] toArray() {
    return copyRows(this.rows);
  }
}
