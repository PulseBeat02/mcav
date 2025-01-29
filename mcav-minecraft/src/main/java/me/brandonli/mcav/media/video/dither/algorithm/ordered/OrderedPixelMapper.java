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
package me.brandonli.mcav.media.video.dither.algorithm.ordered;

import org.checkerframework.checker.initialization.qual.UnderInitialization;

/**
 * Immutable implementation of the {@link PixelMapper} interface that uses an ordered
 * dithering algorithm to generate a matrix of precalculated float values based on the
 * input matrix, a maximum threshold value, and a strength factor.
 * <p>
 * This class processes a two-dimensional integer matrix and converts it into a
 * two-dimensional array of float values. The conversion involves scaling and
 * normalizing threshold values. The resulting matrix can be used in applications
 * where ordered dithering is required.
 *
 * <strong>Thread Safety:</strong> This class is thread-safe since it is immutable.
 */
public final class OrderedPixelMapper implements PixelMapper {

  private final float[][] matrix;

  private OrderedPixelMapper(final int[][] matrix, final int max, final float strength) {
    this.matrix = this.calculateMatrixArray(matrix, max, strength);
  }

  /**
   * Creates a new instance of a {@link PixelMapper} that processes the provided input matrix
   * using an ordered dithering algorithm. The method calculates a matrix of precomputed float
   * values derived from the input integer matrix, a maximum threshold value, and a strength
   * factor.
   *
   * @param matrix   a two-dimensional array of integers representing the input matrix to be
   *                 processed. Each element of the matrix contributes to the final computed
   *                 float values based on the dithering algorithm.
   * @param max      the maximum threshold value. This is used to normalize the input values
   *                 during the calculation of the resulting matrix.
   * @param strength a floating-point factor that adjusts the scaling strength of the dithering
   *                 algorithm. This affects the degree of pixel intensity modification.
   * @return an instance of {@link PixelMapper} containing the processed float matrix derived
   * from the input values, maximum threshold, and strength.
   */
  public static PixelMapper ofPixelMapper(final int[][] matrix, final int max, final float strength) {
    return new OrderedPixelMapper(matrix, max, strength);
  }

  private float convertThresholdToAddition(
    @UnderInitialization OrderedPixelMapper this,
    final float scale,
    final int value,
    final int max
  ) {
    return (float) (scale * ((value + 1.0) / max - 0.50000006));
  }

  private float[][] calculateMatrixArray(
    @UnderInitialization OrderedPixelMapper this,
    final int[][] matrix,
    final int max,
    final float strength
  ) {
    final int ydim = matrix.length;
    final int xdim = matrix[0].length;
    final float scale = 65535.0f * strength;
    final float[][] precalc = new float[ydim][xdim];
    for (int i = 0; i < ydim; i++) {
      for (int j = 0; j < xdim; j++) {
        precalc[i][j] = this.convertThresholdToAddition(scale, matrix[i][j], max);
      }
    }
    return precalc;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public float[][] getMatrix() {
    return this.matrix;
  }
}
