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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHANNELS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.PALETTE_COLORS;

import java.util.Arrays;

/**
 * The cheap fits of a live search. The reference fits grids by least squares against the decoder's interpolation and
 * clusters palettes with four float Lloyd iterations over every pixel, which is what makes its records good and its
 * search slow. A live search only needs valid records that are close: a grid node here is the mean of its cell, and a
 * palette is clustered with two integer iterations over every other pixel of every other row. The decoder reconstructs
 * whatever the records say, and the search still measures the reconstruction exactly, so a worse fit only costs
 * bandwidth or picture, never correctness.
 */
final class FastFits {

  /** The side of the grid of cells {@link #cellSums} sums: the finest grid the fast fits make. */
  static final int CELL_GRID = 4;

  /** The channel sums of the cells. */
  static final int CELL_SUMS = CHANNELS * CELL_GRID * CELL_GRID;

  /** The scratch of {@link #cluster}: the channel sums of both clusters, then their pixel counts. */
  static final int CLUSTER_SUMS = PALETTE_COLORS * (CHANNELS + 1);

  /** Where the pixel counts start in the cluster scratch. */
  private static final int COUNTS = PALETTE_COLORS * CHANNELS;

  private static final int ITERATIONS = 2;

  /** The luma of the compact grids is 16 times the difference: four times the channels against four-times predictions. */
  private static final float LUMA_SCALE = 16.0f;

  /** Blocks this large are clustered on every other pixel of every other row. */
  private static final int SAMPLED_SIZE = 16;

  private FastFits() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * The means of the RGB channels over a 4x4 grid of cells, the finest grid the live search fits; coarser grids are
   * sums of these cells.
   *
   * @param source the block's channels, 0..255, three per pixel in row-major order
   * @param size   the block size, 8, 16 or 32
   * @param sums   receives the channel sums of the 16 cells, cell-major: {@code (row * 4 + column) * 3 + channel}
   */
  static void cellSums(final int[] source, final int size, final int[] sums) {
    Arrays.fill(sums, 0, CELL_SUMS, 0);
    final int cell = size / CELL_GRID;
    for (int row = 0; row < size; row++) {
      final int cellRowStart = (row / cell) * CELL_GRID;
      for (int column = 0; column < size; column++) {
        final int at = (row * size + column) * CHANNELS;
        final int to = (cellRowStart + column / cell) * CHANNELS;
        sums[to] += source[at];
        sums[to + 1] += source[at + 1];
        sums[to + 2] += source[at + 2];
      }
    }
  }

  /**
   * The nodes of an RGB grid of width 1, 2 or 4 from the 4x4 cell sums: every node the mean of its cell.
   *
   * @param sums  the cell sums from {@link #cellSums}
   * @param size  the block size
   * @param grid  the grid width, 1, 2 or 4
   * @param nodes receives {@code grid * grid * 3} interleaved means
   */
  static void grid(final int[] sums, final int size, final int grid, final float[] nodes) {
    final int span = CELL_GRID / grid;
    // the grid divides the block exactly
    final int side = size / grid;
    final float pixels = side * side;
    for (int nodeRow = 0; nodeRow < grid; nodeRow++) {
      for (int nodeColumn = 0; nodeColumn < grid; nodeColumn++) {
        for (int channel = 0; channel < CHANNELS; channel++) {
          int sum = 0;
          for (int cellRow = nodeRow * span; cellRow < (nodeRow + 1) * span; cellRow++) {
            for (int cellColumn = nodeColumn * span; cellColumn < (nodeColumn + 1) * span; cellColumn++) {
              sum += sums[(cellRow * CELL_GRID + cellColumn) * CHANNELS + channel];
            }
          }
          nodes[(nodeRow * grid + nodeColumn) * CHANNELS + channel] = sum / pixels;
        }
      }
    }
  }

  /**
   * The 4x4 luma nodes of a compact record: the mean over each cell of the source's luma less the prediction's, the
   * YCoCg luma the decoder adds, {@code (r + 2 g + b) / 4}.
   *
   * @param source     the block's channels, 0..255
   * @param prediction four times the predicted channels
   * @param size       the block size
   * @param sums       scratch space for 16 sums
   * @param nodes      receives 16 nodes, row-major
   */
  static void lumaResidual(final int[] source, final int[] prediction, final int size, final int[] sums, final float[] nodes) {
    final int cell = size / CELL_GRID;
    final int cells = CELL_GRID * CELL_GRID;
    Arrays.fill(sums, 0, cells, 0);
    for (int row = 0; row < size; row++) {
      final int cellRowStart = (row / cell) * CELL_GRID;
      for (int column = 0; column < size; column++) {
        final int at = (row * size + column) * CHANNELS;
        sums[cellRowStart + column / cell] +=
          4 * (source[at] + 2 * source[at + 1] + source[at + 2]) - (prediction[at] + 2 * prediction[at + 1] + prediction[at + 2]);
      }
    }
    final float scale = LUMA_SCALE * cell * cell;
    for (int cellIndex = 0; cellIndex < cells; cellIndex++) {
      nodes[cellIndex] = sums[cellIndex] / scale;
    }
  }

  /**
   * Clusters a block into two colours: luma extrema of the sampled pixels as seeds, then two Lloyd iterations over
   * every other pixel of every other row with integer squared distances, a tie going to the first endpoint, and an
   * endpoint without pixels keeping its place.
   *
   * @param source    the block's channels, 0..255
   * @param size      the block size
   * @param sums      scratch space for 8 values
   * @param endpoints receives the endpoints, whole numbers: R, G, B of endpoint 0, then of endpoint 1
   */
  static void cluster(final int[] source, final int size, final long[] sums, final float[] endpoints) {
    final int step = size >= SAMPLED_SIZE ? 2 : 1;
    int low = 0;
    int high = 0;
    int lowLuma = Integer.MAX_VALUE;
    int highLuma = Integer.MIN_VALUE;
    for (int row = 0; row < size; row += step) {
      for (int column = 0; column < size; column += step) {
        final int at = (row * size + column) * CHANNELS;
        final int luma = source[at] + 2 * source[at + 1] + source[at + 2];
        if (luma < lowLuma) {
          lowLuma = luma;
          low = at;
        }
        if (luma > highLuma) {
          highLuma = luma;
          high = at;
        }
      }
    }
    for (int channel = 0; channel < CHANNELS; channel++) {
      endpoints[channel] = source[low + channel];
      endpoints[CHANNELS + channel] = source[high + channel];
    }
    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
      final int firstRed = (int) endpoints[0];
      final int firstGreen = (int) endpoints[1];
      final int firstBlue = (int) endpoints[2];
      final int secondRed = (int) endpoints[3];
      final int secondGreen = (int) endpoints[4];
      final int secondBlue = (int) endpoints[5];
      Arrays.fill(sums, 0, CLUSTER_SUMS, 0);
      for (int row = 0; row < size; row += step) {
        for (int column = 0; column < size; column += step) {
          final int at = (row * size + column) * CHANNELS;
          final int red = source[at];
          final int green = source[at + 1];
          final int blue = source[at + 2];
          final int firstDistance =
            (red - firstRed) * (red - firstRed) + (green - firstGreen) * (green - firstGreen) + (blue - firstBlue) * (blue - firstBlue);
          final int secondDistance =
            (red - secondRed) * (red - secondRed) +
            (green - secondGreen) * (green - secondGreen) +
            (blue - secondBlue) * (blue - secondBlue);
          final int nearest = secondDistance < firstDistance ? 1 : 0;
          sums[nearest * CHANNELS] += red;
          sums[nearest * CHANNELS + 1] += green;
          sums[nearest * CHANNELS + 2] += blue;
          sums[COUNTS + nearest]++;
        }
      }
      for (int endpoint = 0; endpoint < PALETTE_COLORS; endpoint++) {
        final long members = sums[COUNTS + endpoint];
        if (members > 0) {
          for (int channel = 0; channel < CHANNELS; channel++) {
            // the rounded integer mean: the endpoints stay whole colours
            final long mean = (sums[endpoint * CHANNELS + channel] + members / 2) / members;
            endpoints[endpoint * CHANNELS + channel] = mean;
          }
        }
      }
    }
  }
}
