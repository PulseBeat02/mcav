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

import me.brandonli.mcav.bukkit.media.mcv2.Workers;

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

  /** Rows one worker sums for the projections. */
  private static final int BAND = 64;

  /** The phase correlation runs on the luma at a quarter of the resolution. */
  private static final int DECIMATION = 4;

  /** The smallest cross-power magnitude normalized by, so a zero bin does not divide by zero. */
  private static final double MIN_MAGNITUDE = 1e-8;

  /** The side of the window of whole-pixel candidates around the phase correlation's estimate. */
  private static final int PHASE_WINDOW = 7;

  /** The side of the window of candidates around the projections' estimate, in half-resolution pixels. */
  private static final int PROJECTION_WINDOW = 5;

  /** The candidates' error samples every twelfth pixel of every twelfth row. */
  private static final int SAMPLE_STEP = 12;

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
    final int rows = (height + DECIMATION - 1) / DECIMATION;
    final int columns = (width + DECIMATION - 1) / DECIMATION;
    final double[] luma = quarterLuma(source, width, rows, columns);
    final double[] previous = quarterLuma(reference, width, rows, columns);
    final double[][] previousSpectrum = Fft.forward2d(previous, rows, columns, workers);
    final double[][] currentSpectrum = Fft.forward2d(luma, rows, columns, workers);
    final double[] real = new double[rows * columns];
    final double[] imaginary = new double[rows * columns];
    final int chunks = (real.length + CHUNK - 1) / CHUNK;
    workers.forEach(
      chunks,
      () -> real,
      (_, chunk) -> {
        final int end = Math.min(real.length, (chunk + 1) * CHUNK);
        for (int bin = chunk * CHUNK; bin < end; bin++) {
          // previous times the conjugate of the current frame, normalized to unit magnitude
          final double crossReal = previousSpectrum[0][bin] * currentSpectrum[0][bin] + previousSpectrum[1][bin] * currentSpectrum[1][bin];
          final double crossImaginary =
            previousSpectrum[1][bin] * currentSpectrum[0][bin] - previousSpectrum[0][bin] * currentSpectrum[1][bin];
          final double magnitude = Math.max(Math.hypot(crossReal, crossImaginary), MIN_MAGNITUDE);
          real[bin] = crossReal / magnitude;
          imaginary[bin] = crossImaginary / magnitude;
        }
      }
    );
    final double[] correlation = Fft.inverse2dReal(real, imaginary, rows, columns, workers);
    int peak = 0;
    for (int position = 1; position < correlation.length; position++) {
      if (correlation[position] > correlation[peak]) {
        peak = position;
      }
    }
    final int peakY = peak / columns;
    final int peakX = peak % columns;
    final int coarseX = Math.min(Math.max((peakX <= columns / 2 ? peakX : peakX - columns) * DECIMATION, -MAX_RANGE), MAX_RANGE);
    final int coarseY = Math.min(Math.max((peakY <= rows / 2 ? peakY : peakY - rows) * DECIMATION, -MAX_RANGE), MAX_RANGE);
    // candidate 0 is the zero vector, the others the neighbourhood of the coarse estimate
    final long[] errors = new long[1 + PHASE_WINDOW * PHASE_WINDOW];
    workers.forEach(
      errors.length,
      () -> errors,
      (_, candidate) -> {
        final int deltaX = windowX(candidate, coarseX, PHASE_WINDOW);
        final int deltaY = windowY(candidate, coarseY, PHASE_WINDOW);
        errors[candidate] =
          Math.abs(deltaX) > MAX_RANGE || Math.abs(deltaY) > MAX_RANGE
            ? Long.MAX_VALUE
            : sampledError(source, reference, width, height, deltaX, deltaY);
      }
    );
    long best = Long.MAX_VALUE;
    int vector = 0;
    for (int candidate = 0; candidate < errors.length; candidate++) {
      if (errors[candidate] < best) {
        best = errors[candidate];
        vector = MotionSearch.pack(windowX(candidate, coarseX, PHASE_WINDOW) * 2, windowY(candidate, coarseY, PHASE_WINDOW) * 2);
      }
    }
    return vector;
  }

  /**
   * The luma projections a live search estimates global motion from, at half resolution: the sums of every other
   * column over every other row, then of every other row over every other column, of four times the luma. Computed
   * once per source frame and kept for the next.
   *
   * @param source  the picture, row-major RGB
   * @param width   the width
   * @param height  the height
   * @param workers the workers
   * @return {@code (width + 1) / 2} column sums, then {@code (height + 1) / 2} row sums
   */
  static long[] projections(final byte[] source, final int width, final int height, final Workers workers) {
    final int columns = (width + 1) / 2;
    final int rows = (height + 1) / 2;
    final long[] sums = new long[columns + rows];
    final int bands = (rows + BAND - 1) / BAND;
    final long[][] partial = new long[bands][];
    workers.forEach(
      bands,
      () -> sums,
      (_, band) -> {
        final long[] columnSums = new long[columns];
        for (int row = band * BAND; row < Math.min(rows, (band + 1) * BAND); row++) {
          final int line = 2 * row * width;
          long rowSum = 0;
          for (int column = 0; column < columns; column++) {
            final int at = (line + 2 * column) * CHANNELS;
            final int luma = (source[at] & 0xFF) + 2 * (source[at + 1] & 0xFF) + (source[at + 2] & 0xFF);
            columnSums[column] += luma;
            rowSum += luma;
          }
          sums[columns + row] = rowSum;
        }
        partial[band] = columnSums;
      }
    );
    for (final long[] columnSums : partial) {
      for (int column = 0; column < columns; column++) {
        sums[column] += columnSums[column];
      }
    }
    return sums;
  }

  /**
   * Estimates the global motion of a live frame: the shifts that best align the luma projections of the previous
   * source frame with this one's, on each axis, refined like the reference's estimate by the sampled error against the
   * reference picture in a 5x5 neighbourhood, with the zero vector as the first candidate.
   *
   * @param source      the current picture, row-major RGB
   * @param reference   the previous decoded picture, the same size
   * @param width       the width
   * @param height      the height
   * @param previous    the previous source frame's projections
   * @param current     this frame's projections
   * @param workers     the workers
   * @return the vector as {@code x << 16 | (y & 0xFFFF)}, in half pixels
   */
  static int estimateLive(
    final byte[] source,
    final byte[] reference,
    final int width,
    final int height,
    final long[] previous,
    final long[] current,
    final Workers workers
  ) {
    final int columns = (width + 1) / 2;
    final int coarseX = 2 * align(previous, current, 0, columns);
    final int coarseY = 2 * align(previous, current, columns, (height + 1) / 2);
    final long[] errors = new long[1 + PROJECTION_WINDOW * PROJECTION_WINDOW];
    workers.forEach(
      errors.length,
      () -> errors,
      (_, candidate) -> {
        final int deltaX = windowX(candidate, coarseX, PROJECTION_WINDOW);
        final int deltaY = windowY(candidate, coarseY, PROJECTION_WINDOW);
        errors[candidate] =
          Math.abs(deltaX) > MAX_RANGE || Math.abs(deltaY) > MAX_RANGE
            ? Long.MAX_VALUE
            : sampledError(source, reference, width, height, deltaX, deltaY);
      }
    );
    long best = Long.MAX_VALUE;
    int vector = 0;
    for (int candidate = 0; candidate < errors.length; candidate++) {
      if (errors[candidate] < best) {
        best = errors[candidate];
        vector = MotionSearch.pack(windowX(candidate, coarseX, PROJECTION_WINDOW) * 2, windowY(candidate, coarseY, PROJECTION_WINDOW) * 2);
      }
    }
    return vector;
  }

  /**
   * The shift, in projection samples within half the largest displacement, that minimizes the mean absolute difference
   * between {@code current[index]} and {@code previous[index + shift]} where both exist and at least half the axis
   * overlaps; zero first.
   */
  static int align(final long[] previous, final long[] current, final int offset, final int length) {
    final int range = Math.min(MAX_RANGE / 2, length / 2);
    int best = 0;
    double bestError = Double.POSITIVE_INFINITY;
    for (int step = 0; step <= 2 * range; step++) {
      // 0, 1, -1, 2, -2, ...: nearer shifts first, so a tie keeps the smaller motion
      final int shift = ((step + 1) / 2) * ((step & 1) == 0 ? -1 : 1);
      final int from = Math.max(0, -shift);
      final int to = Math.min(length, length - shift);
      long sum = 0;
      for (int index = from; index < to; index++) {
        sum += Math.abs(current[offset + index] - previous[offset + index + shift]);
      }
      final double error = sum / (double) (to - from);
      if (error < bestError) {
        bestError = error;
        best = shift;
      }
    }
    return best;
  }

  /** The horizontal displacement of a candidate: 0 for candidate 0, else a column of the window around the estimate. */
  private static int windowX(final int candidate, final int coarseX, final int window) {
    return candidate == 0 ? 0 : coarseX + ((candidate - 1) % window) - window / 2;
  }

  /** The vertical displacement of a candidate: 0 for candidate 0, else a row of the window around the estimate. */
  private static int windowY(final int candidate, final int coarseY, final int window) {
    return candidate == 0 ? 0 : coarseY + (candidate - 1) / window - window / 2;
  }

  private static double[] quarterLuma(final byte[] image, final int width, final int rows, final int columns) {
    final double[] plane = new double[rows * columns];
    for (int row = 0; row < rows; row++) {
      for (int column = 0; column < columns; column++) {
        final int at = (row * DECIMATION * width + column * DECIMATION) * CHANNELS;
        plane[row * columns + column] = ((image[at] & 0xFF) + 2 * (image[at + 1] & 0xFF) + (image[at + 2] & 0xFF)) * 0.25;
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
    final int deltaX,
    final int deltaY
  ) {
    long sum = 0;
    for (int row = 0; row < height; row += SAMPLE_STEP) {
      final int referenceRow = Math.min(Math.max(row + deltaY, 0), height - 1);
      for (int column = 0; column < width; column += SAMPLE_STEP) {
        final int referenceColumn = Math.min(Math.max(column + deltaX, 0), width - 1);
        final int sourceOffset = (row * width + column) * CHANNELS;
        final int referenceOffset = (referenceRow * width + referenceColumn) * CHANNELS;
        for (int channel = 0; channel < CHANNELS; channel++) {
          sum += Math.abs((reference[referenceOffset + channel] & 0xFF) - (source[sourceOffset + channel] & 0xFF));
        }
      }
    }
    return sum;
  }
}
