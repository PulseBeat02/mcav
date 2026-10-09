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

/** The spec's floating-point equations independently check the production integer kernels. */
final class Mcv2Oracle {

  private Mcv2Oracle() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static int roundedChannel(final double value) {
    return (int) Math.min(255, Math.max(0, Math.floor(value + 0.5)));
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
    final double positionX = Math.min(grid - 1, Math.max(0, ((column + 0.5) * grid) / size - 0.5));
    final double positionY = Math.min(grid - 1, Math.max(0, ((row + 0.5) * grid) / size - 0.5));
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

  static void predicted(final int[] prediction, final int size, final int[] out) {
    for (int sample = 0; sample < size * size * 3; sample++) {
      out[sample] = roundedChannel(prediction[sample]);
    }
  }

  static void compact(final int[] prediction, final byte[] record, final int quantizer, final int size, final int[] out) {
    final float[] nodes = new float[16];
    for (int node = 0; node < 16; node++) {
      final int nibble = ((record[2 + node / 2] & 255) >> (4 * (node % 2))) & 15;
      nodes[node] = nibble >= 8 ? nibble - 16 : nibble;
    }

    final int step = 1 << quantizer;
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final double luma = interpolate(nodes, 0, 1, 4, size, column, row);
        final int at = (row * size + column) * 3;
        out[at] = roundedChannel(prediction[at] + step * luma);
        out[at + 1] = roundedChannel(prediction[at + 1] + step * luma);
        out[at + 2] = roundedChannel(prediction[at + 2] + step * luma);
      }
    }
  }
}
