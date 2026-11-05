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
 * A threshold pattern for ordered dithering, normalized to the range from -0.5 to 0.5 and scaled by a strength.
 * The pattern is tiled across the image, and the value under each pixel is added to its color before the closest
 * palette color is chosen.
 *
 * <p>Implementations in this library are immutable and thread-safe.
 *
 * @since 1.0.0
 */
public interface PixelMapper {
  /**
   * A subtle strength that only slightly breaks up color bands.
   *
   * @since 1.0.0
   */
  float LIGHT_STRENGTH = 0.5f;

  /**
   * The normal strength, which spans the distance between neighboring palette colors.
   *
   * @since 1.0.0
   */
  float NORMAL_STRENGTH = 1.0f;

  /**
   * A strong strength that produces a clearly visible pattern.
   *
   * @since 1.0.0
   */
  float HEAVY_STRENGTH = 2.0f;

  /**
   * Returns the normalized threshold pattern, indexed by row and then column.
   *
   * @return the pattern, which must not be modified
   * @since 1.0.0
   */
  float[][] getMatrix();

  /**
   * Returns the strength the pattern was scaled by.
   *
   * @return the strength
   * @since 1.0.0
   */
  float getStrength();

  /**
   * Creates a mapper from a threshold matrix, such as the matrices of {@link BayerDither}.
   *
   * @param matrix   the threshold matrix, whose entries rank the pixels of the pattern
   * @param maximum  the number of levels of the matrix, such as {@link BayerDither#NORMAL_2X2_MAXIMUM}; kept for
   *                 compatibility, the levels are derived from the matrix itself
   * @param strength the finite strength, not negative, see {@link #NORMAL_STRENGTH}
   * @return the mapper
   * @throws NullPointerException     if the matrix or any supplied row is null
   * @throws IllegalArgumentException if the strength is negative or nonfinite, or {@code maximum} is not positive
   * @since 1.0.0
   */
  static PixelMapper ofPixelMapper(final ThresholdMatrix matrix, final int maximum, final float strength) {
    Preconditions.checkNotNull(matrix, "Matrix must not be null!");

    final int[][] entries = matrix.toArray();
    return ofPixelMapper(entries, maximum, strength);
  }

  /**
   * Creates a mapper from a threshold matrix, such as the matrices of {@link BayerDither}.
   *
   * @param matrix   the threshold matrix, whose entries rank the pixels of the pattern
   * @param strength the finite strength, not negative, see {@link #NORMAL_STRENGTH}
   * @return the mapper
   * @throws NullPointerException     if the matrix or any supplied row is null
   * @throws IllegalArgumentException if the strength is negative or nonfinite
   * @since 1.0.0
   */
  static PixelMapper ofPixelMapper(final ThresholdMatrix matrix, final float strength) {
    Preconditions.checkNotNull(matrix, "Matrix must not be null!");

    final int[][] entries = matrix.toArray();
    return ofPixelMapper(entries, strength);
  }

  /**
   * Creates a mapper from an integer threshold matrix.
   *
   * @param matrix   the threshold matrix, whose entries rank the pixels of the pattern
   * @param maximum  the number of levels of the matrix; kept for compatibility, the levels are derived from the
   *                 matrix itself
   * @param strength the finite strength, not negative, see {@link #NORMAL_STRENGTH}
   * @return the mapper
   * @throws NullPointerException     if the matrix or any supplied row is null
   * @throws IllegalArgumentException if the strength is negative or nonfinite, or {@code maximum} is not positive
   *                                  or the matrix is empty or has unequal or empty rows
   * @since 1.0.0
   */
  static PixelMapper ofPixelMapper(final int[][] matrix, final int maximum, final float strength) {
    Preconditions.checkNotNull(matrix, "Matrix must not be null!");
    Preconditions.checkArgument(maximum > 0, "Max must be positive!");

    return new OrderedPixelMapper(matrix, strength);
  }

  /**
   * Creates a mapper from an integer threshold matrix.
   *
   * @param matrix   the threshold matrix, whose entries rank the pixels of the pattern
   * @param strength the finite strength, not negative, see {@link #NORMAL_STRENGTH}
   * @return the mapper
   * @throws NullPointerException     if the matrix or any supplied row is null
   * @throws IllegalArgumentException if the strength is negative or nonfinite
   *                                  or the matrix is empty or has unequal or empty rows
   * @since 1.0.0
   */
  static PixelMapper ofPixelMapper(final int[][] matrix, final float strength) {
    Preconditions.checkNotNull(matrix, "Matrix must not be null!");

    return new OrderedPixelMapper(matrix, strength);
  }
}
