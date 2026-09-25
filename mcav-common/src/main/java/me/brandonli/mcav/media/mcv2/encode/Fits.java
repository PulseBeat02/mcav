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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import me.brandonli.mcav.media.mcv2.Mcv2Resources;

/**
 * The least-squares grid fits of the encoder. A grid is fitted with the pseudo-inverse of the decoder's bilinear
 * interpolation matrix, separably along both axes, so the fit minimizes the error of what the decoder will actually
 * reconstruct rather than averaging cells.
 *
 * <p>The pseudo-inverses are the reference encoder's own float32 matrices ({@code pixels.fitting_matrix}), loaded
 * from a resource checked against its SHA-256, so the Java fits start from the same numbers.
 */
final class Fits {

  static final String SHA256 = "b575fd1ec9fc6ac77e12e89dc569d0b9386b929e185a12ba84dd35148adbaf0e";

  /** Matrices for block sizes 8, 16, 32 and grids 1, 2, 4, 8: {@code grid} rows of {@code size} weights. */
  private static final float[][][] MATRICES = load();

  private Fits() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static float[][][] load() {
    return parse(Mcv2Resources.load("fitting_matrices.bin", SHA256, 3360));
  }

  /** Splits the checked resource into its twelve matrices. */
  private static float[][][] parse(final byte[] bytes) {
    final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    final float[][][] matrices = new float[3][4][];
    for (int s = 0; s < 3; s++) {
      final int size = 8 << s;
      for (int g = 0; g < 4; g++) {
        final int grid = 1 << g;
        final float[] matrix = new float[grid * size];
        for (int i = 0; i < matrix.length; i++) {
          matrix[i] = buffer.getFloat();
        }
        matrices[s][g] = matrix;
      }
    }
    return matrices;
  }

  /**
   * Fits one channel of a block to a grid: {@code out[i][j] = sum over y, x of M[i][y] v[y][x] M[j][x]}.
   *
   * @param values  the block's values, row-major
   * @param offset  the index of pixel (0, 0)
   * @param stride  the distance between consecutive pixels
   * @param size    the block size
   * @param grid    the grid width
   * @param scratch scratch space for {@code size * grid} doubles
   * @param out     receives {@code grid * grid} nodes, row-major, at {@code outOffset} with {@code outStride}
   * @param outOffset the index of node (0, 0)
   * @param outStride the distance between consecutive nodes
   */
  static void fit(
    final float[] values,
    final int offset,
    final int stride,
    final int size,
    final int grid,
    final double[] scratch,
    final float[] out,
    final int outOffset,
    final int outStride
  ) {
    final float[] matrix = MATRICES[Integer.numberOfTrailingZeros(size) - 3][Integer.numberOfTrailingZeros(grid)];
    for (int y = 0; y < size; y++) {
      for (int j = 0; j < grid; j++) {
        double sum = 0;
        for (int x = 0; x < size; x++) {
          sum += (double) values[offset + (y * size + x) * stride] * matrix[j * size + x];
        }
        scratch[y * grid + j] = sum;
      }
    }
    for (int i = 0; i < grid; i++) {
      for (int j = 0; j < grid; j++) {
        double sum = 0;
        for (int y = 0; y < size; y++) {
          sum += matrix[i * size + y] * scratch[y * grid + j];
        }
        out[outOffset + (i * grid + j) * outStride] = (float) sum;
      }
    }
  }
}
