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

  static int rgb8(final double value) {
    return (int) Math.min(255, Math.max(0, Math.floor(value + 0.5)));
  }

  static double interpolate(
    final float[] nodes,
    final int offset,
    final int stride,
    final int grid,
    final int size,
    final int x,
    final int y
  ) {
    final double tx = Math.min(grid - 1, Math.max(0, ((x + 0.5) * grid) / size - 0.5));
    final double ty = Math.min(grid - 1, Math.max(0, ((y + 0.5) * grid) / size - 0.5));
    final int x0 = (int) tx;
    final int y0 = (int) ty;
    final int x1 = Math.min(x0 + 1, grid - 1);
    final int y1 = Math.min(y0 + 1, grid - 1);
    final double fx = tx - x0;
    final double fy = ty - y0;
    return (
      (1 - fy) * ((1 - fx) * nodes[offset + (y0 * grid + x0) * stride] + fx * nodes[offset + (y0 * grid + x1) * stride]) +
      fy * ((1 - fx) * nodes[offset + (y1 * grid + x0) * stride] + fx * nodes[offset + (y1 * grid + x1) * stride])
    );
  }

  static void predicted(final int[] prediction, final int size, final int[] out) {
    for (int sample = 0; sample < size * size * 3; sample++) {
      out[sample] = rgb8(prediction[sample] * 0.25);
    }
  }

  static void compact(
    final int[] prediction,
    final byte[] record,
    final int body,
    final int kind,
    final int quantizer,
    final int size,
    final int[] out
  ) {
    final float[] nodes = new float[16];
    if (kind != 0) {
      for (int node = 0; node < 16; node++) {
        final int nibble = ((record[body + node / 2] & 255) >> (4 * (node % 2))) & 15;
        nodes[node] = nibble >= 8 ? nibble - 16 : nibble;
      }
    }
    final int co = kind == 1 ? record[body + 8] : 0;
    final int cg = kind == 1 ? record[body + 9] : 0;
    final int step = 1 << quantizer;
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        final double luma = kind == 0 ? record[body] : interpolate(nodes, 0, 1, 4, size, x, y);
        final int at = (y * size + x) * 3;
        out[at] = rgb8(prediction[at] * 0.25 + step * (luma + co - cg));
        out[at + 1] = rgb8(prediction[at + 1] * 0.25 + step * (luma + cg));
        out[at + 2] = rgb8(prediction[at + 2] * 0.25 + step * (luma - co - cg));
      }
    }
  }
}
