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

import static me.brandonli.mcav.media.mcv2.Mcv2Format.ROOT_SIZE;

import me.brandonli.mcav.media.mcv2.Reconstruction;
import me.brandonli.mcav.media.mcv2.Workers;

/**
 * The frame-level choice of a live search, which encodes a frame once where the reference encodes it for every
 * candidate global vector and keeps the cheapest. The vector is chosen before the search from what SKIP would cost
 * with it: a block SKIP codes well costs its distortion and one bit, and a block it codes badly costs at least what
 * another leaf would, so each block counts at most {@link #CLIP} times lambda. The vector with the lowest sum wins,
 * the first on a tie. This rewards the area SKIP can take, which is where the reference's cheaper trial comes from.
 */
final class LiveAnalysis {

  /** The most a block counts, in units of lambda: about a local motion leaf with some distortion. */
  static final double CLIP = 64.0;

  /** The distance between the pixels sampled in each direction: one pixel in sixteen is measured. */
  private static final int STEP = 4;

  private LiveAnalysis() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * What the analysis found.
   *
   * @param vector   the index of the chosen vector
   * @param sceneCut whether the frame changes too much for any prediction: the reference's scene cut test, the mean
   *                 absolute luma change after prediction with the estimated vector above the threshold
   */
  record Result(int vector, boolean sceneCut) {}

  /**
   * Chooses the global vector of a live P frame, and tests for a scene cut on the same predictions.
   *
   * @param source    the picture, row-major RGB
   * @param reference the previous decoded picture
   * @param width     the width
   * @param height    the height
   * @param lambda    the rate-distortion trade
   * @param threshold the scene cut threshold, the mean absolute luma change
   * @param vectors   the candidate vectors, each {@code x << 16 | (y & 0xFFFF)} in half pixels; the last is the estimate
   * @param workers   the workers
   * @return the choice
   */
  static Result analyze(
    final byte[] source,
    final byte[] reference,
    final int width,
    final int height,
    final double lambda,
    final double threshold,
    final int[] vectors,
    final Workers workers
  ) {
    final int columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
    final int superblocks = columns * ((height + ROOT_SIZE - 1) / ROOT_SIZE);
    final double[][] costs = new double[vectors.length][superblocks];
    final long[] changes = new long[superblocks];
    final int estimate = vectors.length - 1;
    workers.forEach(
      superblocks,
      () -> changes,
      (_, index) -> {
        final int x = (index % columns) * ROOT_SIZE;
        final int y = (index / columns) * ROOT_SIZE;
        for (int v = 0; v < vectors.length; v++) {
          final int mx = vectors[v] >> 16;
          final int my = (short) vectors[v];
          long distortion = 0;
          long change = 0;
          for (int py = STEP / 2; py < ROOT_SIZE; py += STEP) {
            final int sy = Math.min(y + py, height - 1);
            final int hy = Math.min(Math.max(2 * (y + py) + my, 0), 2 * (height - 1));
            for (int px = STEP / 2; px < ROOT_SIZE; px += STEP) {
              final int sx = Math.min(x + px, width - 1);
              final int hx = Math.min(Math.max(2 * (x + px) + mx, 0), 2 * (width - 1));
              final int at = (sy * width + sx) * 3;
              final int r = source[at] & 0xFF;
              final int g = source[at + 1] & 0xFF;
              final int b = source[at + 2] & 0xFF;
              final int pr = sample(reference, width, height, hx, hy, 0);
              final int pg = sample(reference, width, height, hx, hy, 1);
              final int pb = sample(reference, width, height, hx, hy, 2);
              if (v == estimate) {
                change += Math.abs(4 * (r + 2 * g + b) - (pr + 2 * pg + pb));
              }
              final int dr = r - ((pr + 2) >> 2);
              final int dg = g - ((pg + 2) >> 2);
              final int db = b - ((pb + 2) >> 2);
              final int luma = dr + 2 * dg + db;
              final int co = dr - db;
              final int cg = 2 * dg - dr - db;
              distortion += 4L * luma * luma + 4L * co * co + (long) cg * cg;
            }
          }
          // every sample stands for STEP * STEP pixels
          costs[v][index] = Math.min((distortion * (STEP * STEP)) / 96.0 + lambda, CLIP * lambda);
          if (v == estimate) {
            changes[index] = change * (STEP * STEP);
          }
        }
      }
    );
    long change = 0;
    for (final long value : changes) {
      change += value;
    }
    final double pixels = (double) superblocks * ROOT_SIZE * ROOT_SIZE;
    int best = 0;
    double bestSum = Double.POSITIVE_INFINITY;
    for (int v = 0; v < vectors.length; v++) {
      double sum = 0;
      for (final double cost : costs[v]) {
        sum += cost;
      }
      if (sum < bestSum) {
        bestSum = sum;
        best = v;
      }
    }
    return new Result(best, change / 16.0 / pixels > threshold);
  }

  /** Four times the reference's channel at a position in half pixels, sampled as {@link Reconstruction#predict} does. */
  private static int sample(final byte[] reference, final int width, final int height, final int hx, final int hy, final int c) {
    final int x0 = hx >> 1;
    final int y0 = hy >> 1;
    final int x1 = Math.min(x0 + 1, width - 1);
    final int y1 = Math.min(y0 + 1, height - 1);
    final int a = reference[(y0 * width + x0) * 3 + c] & 0xFF;
    if ((hx & 1) == 0 && (hy & 1) == 0) {
      return 4 * a;
    }
    if ((hy & 1) == 0) {
      return 2 * (a + (reference[(y0 * width + x1) * 3 + c] & 0xFF));
    }
    if ((hx & 1) == 0) {
      return 2 * (a + (reference[(y1 * width + x0) * 3 + c] & 0xFF));
    }
    return (
      a +
      (reference[(y0 * width + x1) * 3 + c] & 0xFF) +
      (reference[(y1 * width + x0) * 3 + c] & 0xFF) +
      (reference[(y1 * width + x1) * 3 + c] & 0xFF)
    );
  }
}
