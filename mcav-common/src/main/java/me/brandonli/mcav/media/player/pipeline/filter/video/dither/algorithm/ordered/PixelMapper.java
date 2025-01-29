/*
 * This file is part of mcav, a media playback library for Minecraft
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
 * An interface representing a pixel mapping strategy. Implementations of this interface
 * define methods to provide a two-dimensional float matrix that transforms or maps pixel
 * data according to a specific algorithm.
 */
public interface PixelMapper {
  float MIN_STRENGTH = 0.0f;
  float NORMAL_STRENGTH = 1.0f;
  float MAX_STRENGTH = 2.0f;

  /**
   * Retrieves a two-dimensional float matrix that defines a mapping or transformation
   * strategy for pixel data. The matrix encapsulates the algorithm used for pixel mapping.
   *
   * @return a two-dimensional array of floats representing the pixel mapping or transformation matrix
   */
  float[][] getMatrix();

  /**
   * Creates a new instance of {@link PixelMapper} using the provided pixel mapping matrix,
   * maximum value, and strength factor.
   *
   * @param matrix   the two-dimensional integer array representing the pixel mapping matrix
   * @param max      the maximum value for the pixel mapping
   * @param strength the strength factor for the pixel mapping
   * @return a new instance of {@link PixelMapper}
   */
  static PixelMapper ofPixelMapper(final int[][] matrix, final int max, final float strength) {
    Preconditions.checkNotNull(matrix, "Matrix cannot be null");
    Preconditions.checkArgument(matrix.length > 0, "Matrix must have at least one row");
    return new OrderedPixelMapper(matrix, max, strength);
  }
}
