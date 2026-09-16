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
 */
public interface PixelMapper {
  /**
   * A subtle strength that only slightly breaks up color bands.
   */
  float MIN_STRENGTH = 0.5f;

  /**
   * The normal strength, which spans the distance between neighboring palette colors.
   */
  float NORMAL_STRENGTH = 1.0f;

  /**
   * A strong strength that produces a clearly visible pattern.
   */
  float MAX_STRENGTH = 2.0f;

  /**
   * Gets the normalized threshold pattern, indexed by row and then column.
   *
   * @return the pattern, which must not be modified
   */
  float[][] getMatrix();

  /**
   * Gets the strength the pattern was scaled by.
   *
   * @return the strength
   */
  float getStrength();

  /**
   * Creates a mapper from a threshold matrix, such as the matrices of {@link BayerDither}.
   *
   * @param matrix   the threshold matrix, whose entries rank the pixels of the pattern
   * @param max      the number of levels of the matrix, such as {@link BayerDither#NORMAL_2X2_MAX}; kept for
   *                 compatibility, the levels are derived from the matrix itself
   * @param strength the strength, see {@link #NORMAL_STRENGTH}
   * @return the mapper
   * @throws NullPointerException     if the matrix is null
   * @throws IllegalArgumentException if the number of levels or the strength is not positive
   */
  static PixelMapper ofPixelMapper(final ThresholdMatrix matrix, final int max, final float strength) {
    Preconditions.checkNotNull(matrix, "Matrix must not be null");
    final int[][] entries = matrix.toArray();
    return ofPixelMapper(entries, max, strength);
  }

  /**
   * Creates a mapper from a threshold matrix, such as the matrices of {@link BayerDither}.
   *
   * @param matrix   the threshold matrix, whose entries rank the pixels of the pattern
   * @param strength the strength, see {@link #NORMAL_STRENGTH}
   * @return the mapper
   * @throws NullPointerException     if the matrix is null
   * @throws IllegalArgumentException if the strength is not positive
   */
  static PixelMapper ofPixelMapper(final ThresholdMatrix matrix, final float strength) {
    Preconditions.checkNotNull(matrix, "Matrix must not be null");
    final int[][] entries = matrix.toArray();
    return ofPixelMapper(entries, strength);
  }

  /**
   * Creates a mapper from an integer threshold matrix.
   *
   * @param matrix   the threshold matrix, whose entries rank the pixels of the pattern
   * @param max      the number of levels of the matrix; kept for compatibility, the levels are derived from the
   *                 matrix itself
   * @param strength the strength, see {@link #NORMAL_STRENGTH}
   * @return the mapper
   */
  static PixelMapper ofPixelMapper(final int[][] matrix, final int max, final float strength) {
    Preconditions.checkNotNull(matrix, "Matrix must not be null");
    Preconditions.checkArgument(max > 0, "Max must be positive");
    return new OrderedPixelMapper(matrix, strength);
  }

  /**
   * Creates a mapper from an integer threshold matrix.
   *
   * @param matrix   the threshold matrix, whose entries rank the pixels of the pattern
   * @param strength the strength, see {@link #NORMAL_STRENGTH}
   * @return the mapper
   */
  static PixelMapper ofPixelMapper(final int[][] matrix, final float strength) {
    Preconditions.checkNotNull(matrix, "Matrix must not be null");
    return new OrderedPixelMapper(matrix, strength);
  }
}
