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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.BLOCK_SIZES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.GRID_WIDTHS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SMALLEST_BLOCK;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.sizeIndex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Resources;

/**
 * The least-squares grid fits of the encoder. A grid is fitted with the pseudo-inverse of the decoder's bilinear
 * interpolation matrix, separably along both axes, so the fit minimizes the error of what the decoder will actually
 * reconstruct rather than averaging cells.
 *
 * <p>The pseudo-inverses are the reference encoder's own float32 matrices ({@code pixels.fitting_matrix}), loaded
 * from a resource checked against its SHA-256, so the Java fits start from the same numbers.
 */
final class Fits {

  /** The size of the matrices: a float per grid node and pixel of each size and grid width. */
  private static final int BYTES = 3360;

  private static final String SHA256 = "b575fd1ec9fc6ac77e12e89dc569d0b9386b929e185a12ba84dd35148adbaf0e";

  /** Matrices for block sizes 8, 16, 32 and grids 1, 2, 4, 8: {@code grid} rows of {@code size} weights. */
  private static final float[][][] MATRICES = load();

  private Fits() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static float[][][] load() {
    return parse(Mcv2Resources.load("fitting_matrices.bin", SHA256, BYTES));
  }

  /** Splits the checked resource into its twelve matrices. */
  private static float[][][] parse(final byte[] bytes) {
    final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    final float[][][] matrices = new float[BLOCK_SIZES][GRID_WIDTHS][];
    for (int sizeIndex = 0; sizeIndex < BLOCK_SIZES; sizeIndex++) {
      final int size = SMALLEST_BLOCK << sizeIndex;
      for (int gridIndex = 0; gridIndex < GRID_WIDTHS; gridIndex++) {
        final int grid = 1 << gridIndex;
        final float[] matrix = new float[grid * size];
        for (int index = 0; index < matrix.length; index++) {
          matrix[index] = buffer.getFloat();
        }
        matrices[sizeIndex][gridIndex] = matrix;
      }
    }
    return matrices;
  }

  /**
   * Gets the pseudo-inverse of a block size and grid width: {@code grid} rows of {@code size} weights.
   *
   * @param size the block size
   * @param grid the grid width
   * @return the matrix, which the caller must not change
   */
  static float[] matrix(final int size, final int grid) {
    return MATRICES[sizeIndex(size)][Integer.numberOfTrailingZeros(grid)];
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
    final float[] matrix = matrix(size, grid);
    for (int row = 0; row < size; row++) {
      for (int nodeColumn = 0; nodeColumn < grid; nodeColumn++) {
        double sum = 0;
        for (int column = 0; column < size; column++) {
          sum += (double) values[offset + (row * size + column) * stride] * matrix[nodeColumn * size + column];
        }
        scratch[row * grid + nodeColumn] = sum;
      }
    }
    for (int nodeRow = 0; nodeRow < grid; nodeRow++) {
      for (int nodeColumn = 0; nodeColumn < grid; nodeColumn++) {
        double sum = 0;
        for (int row = 0; row < size; row++) {
          sum += matrix[nodeRow * size + row] * scratch[row * grid + nodeColumn];
        }
        out[outOffset + (nodeRow * grid + nodeColumn) * outStride] = (float) sum;
      }
    }
  }
}
