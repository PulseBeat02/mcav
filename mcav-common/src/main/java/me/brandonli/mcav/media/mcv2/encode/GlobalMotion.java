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

import me.brandonli.mcav.media.mcv2.Workers;

/**
 * The frame-global translation of the reference encoder ({@code encoder.estimate_global}): phase correlation of the
 * quarter-resolution luma planes gives a coarse whole-pixel estimate, then a 7x7 neighbourhood around it and the zero
 * vector are scored by the mean absolute difference of samples every twelfth pixel. The refinement compares exact
 * integer sums, so given the same coarse estimate it picks exactly the reference's vector.
 */
final class GlobalMotion {

  /** The largest global displacement searched, in pixels. */
  static final int MAX_RANGE = 128;

  private static final int CHUNK = 4096;

  private GlobalMotion() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Estimates the global motion from the reference to the source, on the calling thread.
   *
   * @param source    the current picture, row-major RGB
   * @param reference the previous decoded picture, the same size
   * @param width     the width
   * @param height    the height
   * @return the vector as {@code x << 16 | (y & 0xFFFF)}, in half pixels
   */
  static int estimate(final byte[] source, final byte[] reference, final int width, final int height) {
    return estimate(source, reference, width, height, Workers.SEQUENTIAL);
  }

  /**
   * Estimates the global motion from the reference to the source. The transforms, the normalization and the scores of
   * the candidates run on the workers; the peak and the best candidate are chosen in the sequential order, so the
   * vector does not depend on the workers.
   *
   * @param source    the current picture, row-major RGB
   * @param reference the previous decoded picture, the same size
   * @param width     the width
   * @param height    the height
   * @param workers   the workers
   * @return the vector as {@code x << 16 | (y & 0xFFFF)}, in half pixels
   */
  static int estimate(final byte[] source, final byte[] reference, final int width, final int height, final Workers workers) {
    final int rows = (height + 3) / 4;
    final int columns = (width + 3) / 4;
    final double[] luma = quarterLuma(source, width, rows, columns);
    final double[] previous = quarterLuma(reference, width, rows, columns);
    final double[][] a = Fft.forward2d(previous, rows, columns, workers);
    final double[][] b = Fft.forward2d(luma, rows, columns, workers);
    final double[] re = new double[rows * columns];
    final double[] im = new double[rows * columns];
    final int chunks = (re.length + CHUNK - 1) / CHUNK;
    workers.forEach(
      chunks,
      () -> re,
      (_, chunk) -> {
        final int end = Math.min(re.length, (chunk + 1) * CHUNK);
        for (int i = chunk * CHUNK; i < end; i++) {
          // previous times the conjugate of the current frame, normalized to unit magnitude
          final double r = a[0][i] * b[0][i] + a[1][i] * b[1][i];
          final double m = a[1][i] * b[0][i] - a[0][i] * b[1][i];
          final double magnitude = Math.max(Math.hypot(r, m), 1e-8);
          re[i] = r / magnitude;
          im[i] = m / magnitude;
        }
      }
    );
    final double[] correlation = Fft.inverse2dReal(re, im, rows, columns, workers);
    int peak = 0;
    for (int i = 1; i < correlation.length; i++) {
      if (correlation[i] > correlation[peak]) {
        peak = i;
      }
    }
    final int peakY = peak / columns;
    final int peakX = peak % columns;
    final int coarseX = Math.min(Math.max((peakX <= columns / 2 ? peakX : peakX - columns) * 4, -MAX_RANGE), MAX_RANGE);
    final int coarseY = Math.min(Math.max((peakY <= rows / 2 ? peakY : peakY - rows) * 4, -MAX_RANGE), MAX_RANGE);
    // candidate 0 is the zero vector, candidates 1 to 49 the 7x7 neighbourhood of the coarse estimate
    final long[] errors = new long[50];
    workers.forEach(
      errors.length,
      () -> errors,
      (_, candidate) -> {
        final int dx = candidateX(candidate, coarseX);
        final int dy = candidateY(candidate, coarseY);
        errors[candidate] = Math.abs(dx) > MAX_RANGE || Math.abs(dy) > MAX_RANGE
          ? Long.MAX_VALUE
          : sampledError(source, reference, width, height, dx, dy);
      }
    );
    long best = Long.MAX_VALUE;
    int vector = 0;
    for (int candidate = 0; candidate < errors.length; candidate++) {
      if (errors[candidate] < best) {
        best = errors[candidate];
        vector = ((candidateX(candidate, coarseX) * 2) << 16) | ((candidateY(candidate, coarseY) * 2) & 0xFFFF);
      }
    }
    return vector;
  }

  private static int candidateX(final int candidate, final int coarseX) {
    return candidate == 0 ? 0 : coarseX + ((candidate - 1) % 7) - 3;
  }

  private static int candidateY(final int candidate, final int coarseY) {
    return candidate == 0 ? 0 : coarseY + (candidate - 1) / 7 - 3;
  }

  private static double[] quarterLuma(final byte[] image, final int width, final int rows, final int columns) {
    final double[] plane = new double[rows * columns];
    for (int y = 0; y < rows; y++) {
      for (int x = 0; x < columns; x++) {
        final int at = (y * 4 * width + x * 4) * 3;
        plane[y * columns + x] = ((image[at] & 0xFF) + 2 * (image[at + 1] & 0xFF) + (image[at + 2] & 0xFF)) * 0.25;
      }
    }
    return plane;
  }

  /** The sum of absolute differences between the source samples and the displaced reference, all channels. */
  private static long sampledError(
    final byte[] source,
    final byte[] reference,
    final int width,
    final int height,
    final int dx,
    final int dy
  ) {
    long sum = 0;
    for (int y = 0; y < height; y += 12) {
      final int ry = Math.min(Math.max(y + dy, 0), height - 1);
      for (int x = 0; x < width; x += 12) {
        final int rx = Math.min(Math.max(x + dx, 0), width - 1);
        final int s = (y * width + x) * 3;
        final int r = (ry * width + rx) * 3;
        for (int c = 0; c < 3; c++) {
          sum += Math.abs((reference[r + c] & 0xFF) - (source[s + c] & 0xFF));
        }
      }
    }
    return sum;
  }
}
