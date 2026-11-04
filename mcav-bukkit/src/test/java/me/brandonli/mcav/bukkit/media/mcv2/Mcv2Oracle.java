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
package me.brandonli.mcav.bukkit.media.mcv2;

final class Mcv2Oracle {

  private static final int MAXIMUM_CHANNEL = 255;

  private static final int CHANNELS = 3;

  private static final double ROUNDING_OFFSET = 0.5;

  private static final double PIXEL_CENTER = 0.5;

  private static final int NODES = 16;
  private static final int GRID = 4;

  private static final int NODES_OFFSET = 2;

  private static final int BYTE_MASK = 0xFF;
  private static final int NIBBLE_BITS = 4;
  private static final int NIBBLE_MASK = 0xF;
  private static final int NIBBLES_PER_BYTE = 2;
  private static final int NIBBLE_SIGN = 8;
  private static final int NIBBLE_RANGE = 16;

  private static final int NO_OFFSET = 0;
  private static final int UNIT_STRIDE = 1;

  private Mcv2Oracle() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  private static int roundedChannel(final double value) {
    return (int) Math.min(MAXIMUM_CHANNEL, Math.max(0, Math.floor(value + ROUNDING_OFFSET)));
  }

  static double interpolate(
    final float[] nodes,
    final int offset,
    final int stride,
    final int grid,
    final int size,
    final int column,
    final int row
  ) {
    final double positionX = Math.min(grid - 1, Math.max(0, ((column + PIXEL_CENTER) * grid) / size - PIXEL_CENTER));
    final double positionY = Math.min(grid - 1, Math.max(0, ((row + PIXEL_CENTER) * grid) / size - PIXEL_CENTER));
    final int lowerX = (int) positionX;
    final int lowerY = (int) positionY;
    final int upperX = Math.min(lowerX + 1, grid - 1);
    final int upperY = Math.min(lowerY + 1, grid - 1);
    final double fractionX = positionX - lowerX;
    final double fractionY = positionY - lowerY;
    return (
      (1 - fractionY) *
        ((1 - fractionX) * nodes[offset + (lowerY * grid + lowerX) * stride] +
          fractionX * nodes[offset + (lowerY * grid + upperX) * stride]) +
      fractionY *
        ((1 - fractionX) * nodes[offset + (upperY * grid + lowerX) * stride] +
          fractionX * nodes[offset + (upperY * grid + upperX) * stride])
    );
  }

  static void predicted(final int[] prediction, final int size, final int[] output) {
    for (int sample = 0; sample < size * size * CHANNELS; sample++) {
      output[sample] = roundedChannel(prediction[sample]);
    }
  }

  static void compact(final int[] prediction, final byte[] record, final int quantizer, final int size, final int[] output) {
    final float[] nodes = new float[NODES];
    for (int node = 0; node < NODES; node++) {
      final int nibble =
        ((record[NODES_OFFSET + node / NIBBLES_PER_BYTE] & BYTE_MASK) >> (NIBBLE_BITS * (node % NIBBLES_PER_BYTE))) & NIBBLE_MASK;
      nodes[node] = nibble >= NIBBLE_SIGN ? nibble - NIBBLE_RANGE : nibble;
    }

    final int step = 1 << quantizer;
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final double luma = interpolate(nodes, NO_OFFSET, UNIT_STRIDE, GRID, size, column, row);
        final int offset = (row * size + column) * CHANNELS;
        output[offset] = roundedChannel(prediction[offset] + step * luma);
        output[offset + 1] = roundedChannel(prediction[offset + 1] + step * luma);
        output[offset + 2] = roundedChannel(prediction[offset + 2] + step * luma);
      }
    }
  }
}
