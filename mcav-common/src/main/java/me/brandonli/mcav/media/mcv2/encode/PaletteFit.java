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

import me.brandonli.mcav.media.mcv2.Mcv2Format;
import me.brandonli.mcav.media.mcv2.Reconstruction;

/**
 * The two-colour palette fit of the reference encoder ({@code encoder.palette_candidate}), reproduced operation for
 * operation: luma extrema seed two endpoints, four Lloyd iterations move them to their clusters' means in float32
 * (means divided in float64 and rounded to float32, as numpy promotes them), the endpoints are rounded to RGB8 and
 * optionally to RGB565, and every pixel finally takes the nearer endpoint, the first one on a tie.
 */
final class PaletteFit {

  private PaletteFit() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Fits a palette to a block.
   *
   * @param source    the block's channels, 0..255, {@code count * 3} values
   * @param count     the number of pixels
   * @param quantize  whether the endpoints are rounded to RGB565 before the final assignment
   * @param colors    receives the endpoints: R, G, B of endpoint 0, then of endpoint 1
   * @param selectors receives one selector per pixel, 0 or 1
   */
  static void fit(final int[] source, final int count, final boolean quantize, final int[] colors, final byte[] selectors) {
    int low = 0;
    int high = 0;
    int lowLuma = Integer.MAX_VALUE;
    int highLuma = Integer.MIN_VALUE;
    for (int i = 0; i < count; i++) {
      final int luma = source[i * 3] + 2 * source[i * 3 + 1] + source[i * 3 + 2];
      if (luma < lowLuma) {
        lowLuma = luma;
        low = i;
      }
      if (luma > highLuma) {
        highLuma = luma;
        high = i;
      }
    }
    final float[] c = {
      source[low * 3],
      source[low * 3 + 1],
      source[low * 3 + 2],
      source[high * 3],
      source[high * 3 + 1],
      source[high * 3 + 2],
    };
    final long[] sums = new long[6];
    final int[] weights = new int[2];
    for (int iteration = 0; iteration < 4; iteration++) {
      java.util.Arrays.fill(sums, 0);
      weights[0] = 0;
      weights[1] = 0;
      for (int i = 0; i < count; i++) {
        final int r = source[i * 3];
        final int g = source[i * 3 + 1];
        final int b = source[i * 3 + 2];
        final int index = nearer(r, g, b, c) ? 1 : 0;
        weights[index]++;
        sums[index * 3] += r;
        sums[index * 3 + 1] += g;
        sums[index * 3 + 2] += b;
      }
      for (int index = 0; index < 2; index++) {
        if (weights[index] > 0) {
          for (int ch = 0; ch < 3; ch++) {
            c[index * 3 + ch] = (float) ((double) sums[index * 3 + ch] / weights[index]);
          }
        }
      }
    }
    for (int i = 0; i < 6; i++) {
      colors[i] = Reconstruction.rgb8(c[i]);
    }
    if (quantize) {
      for (int e = 0; e < 2; e++) {
        final int r = colors[e * 3] >> 3;
        final int g = colors[e * 3 + 1] >> 2;
        final int b = colors[e * 3 + 2] >> 3;
        final int packed = Mcv2Format.unpack565(((r << 11) | (g << 5) | b) & 0xFF, ((r << 11) | (g << 5) | b) >> 8);
        colors[e * 3] = (packed >> 16) & 0xFF;
        colors[e * 3 + 1] = (packed >> 8) & 0xFF;
        colors[e * 3 + 2] = packed & 0xFF;
      }
    }
    for (int i = 0; i < count; i++) {
      final int dr0 = source[i * 3] - colors[0];
      final int dg0 = source[i * 3 + 1] - colors[1];
      final int db0 = source[i * 3 + 2] - colors[2];
      final int dr1 = source[i * 3] - colors[3];
      final int dg1 = source[i * 3 + 1] - colors[4];
      final int db1 = source[i * 3 + 2] - colors[5];
      final int e0 = dr0 * dr0 + dg0 * dg0 + db0 * db0;
      final int e1 = dr1 * dr1 + dg1 * dg1 + db1 * db1;
      selectors[i] = (byte) (e1 < e0 ? 1 : 0);
    }
  }

  /** Whether endpoint 1 is strictly nearer than endpoint 0, with the reference's float32 squared distances. */
  private static boolean nearer(final int r, final int g, final int b, final float[] c) {
    final float dr0 = r - c[0];
    final float dg0 = g - c[1];
    final float db0 = b - c[2];
    final float dr1 = r - c[3];
    final float dg1 = g - c[4];
    final float db1 = b - c[5];
    final float e0 = (dr0 * dr0 + dg0 * dg0) + db0 * db0;
    final float e1 = (dr1 * dr1 + dg1 * dg1) + db1 * db1;
    return e1 < e0;
  }
}
