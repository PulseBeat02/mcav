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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.brandonli.mcav.media.mcv2.Workers;

/**
 * A mixed-radix complex FFT of any length, recursive Cooley-Tukey over the length's prime factors, and the 2D
 * transforms the global motion estimate needs. Lengths of video planes factor into small primes, so the cost stays
 * close to {@code n log n}; a large prime factor degrades gracefully to a direct DFT of that factor.
 *
 * <p>The twiddle factors come from a table per length and direction, each entry computed by the same expression the
 * transform would evaluate in its inner loop, so the table changes the cost and not one bit of the result. The 2D
 * transforms run their rows, then their columns, on the given workers: every line is transformed on its own, exactly
 * as in a sequential run.
 */
final class Fft {

  private static final Map<Integer, double[][]> TWIDDLES = new ConcurrentHashMap<>();

  private Fft() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Transforms a complex sequence in place.
   *
   * @param re      the real parts
   * @param im      the imaginary parts
   * @param inverse whether to apply the inverse transform, unnormalized
   */
  static void transform(final double[] re, final double[] im, final boolean inverse) {
    final int n = re.length;
    if (n <= 1) {
      return;
    }
    int p = 2;
    while (p * p <= n && n % p != 0) {
      p++;
    }
    if (n % p != 0) {
      p = n;
    }
    final int m = n / p;
    final double[][] subRe = new double[p][m];
    final double[][] subIm = new double[p][m];
    for (int r = 0; r < p; r++) {
      for (int k = 0; k < m; k++) {
        subRe[r][k] = re[k * p + r];
        subIm[r][k] = im[k * p + r];
      }
      if (m > 1) {
        transform(subRe[r], subIm[r], inverse);
      }
    }
    final double[][] twiddles = twiddles(n, inverse);
    final double[] cosines = twiddles[0];
    final double[] sines = twiddles[1];
    for (int k = 0; k < m; k++) {
      for (int q = 0; q < p; q++) {
        final int index = k + q * m;
        double sumRe = 0;
        double sumIm = 0;
        for (int r = 0; r < p; r++) {
          final int turn = (int) (((long) r * index) % n);
          final double c = cosines[turn];
          final double s = sines[turn];
          sumRe += subRe[r][k] * c - subIm[r][k] * s;
          sumIm += subRe[r][k] * s + subIm[r][k] * c;
        }
        re[index] = sumRe;
        im[index] = sumIm;
      }
    }
  }

  /**
   * The twiddle factors of one length and direction: entry j is the cosine and sine of {@code sign * 2 pi j / n},
   * computed exactly as the transform's inner loop would compute them.
   */
  private static double[][] twiddles(final int n, final boolean inverse) {
    return TWIDDLES.computeIfAbsent(inverse ? -n : n, _ -> {
      final double sign = inverse ? 1.0 : -1.0;
      final double[] cosines = new double[n];
      final double[] sines = new double[n];
      for (long turn = 0; turn < n; turn++) {
        final double angle = (sign * 2 * Math.PI * turn) / n;
        cosines[(int) turn] = Math.cos(angle);
        sines[(int) turn] = Math.sin(angle);
      }
      return new double[][] { cosines, sines };
    });
  }

  /**
   * The 2D forward transform of a real plane, on the calling thread.
   *
   * @param plane   the values, row-major
   * @param rows    the number of rows
   * @param columns the number of columns
   * @return the real and imaginary parts, row-major
   */
  static double[][] forward2d(final double[] plane, final int rows, final int columns) {
    return forward2d(plane, rows, columns, Workers.SEQUENTIAL);
  }

  /**
   * The 2D forward transform of a real plane.
   *
   * @param plane   the values, row-major
   * @param rows    the number of rows
   * @param columns the number of columns
   * @param workers the workers that transform the lines
   * @return the real and imaginary parts, row-major
   */
  static double[][] forward2d(final double[] plane, final int rows, final int columns, final Workers workers) {
    final double[] re = plane.clone();
    final double[] im = new double[plane.length];
    transform2d(re, im, rows, columns, false, workers);
    return new double[][] { re, im };
  }

  /**
   * The real part of the 2D inverse transform, unnormalized, on the calling thread.
   *
   * @param re      the real parts, row-major; not modified
   * @param im      the imaginary parts, row-major; not modified
   * @param rows    the number of rows
   * @param columns the number of columns
   * @return the real part of the result
   */
  static double[] inverse2dReal(final double[] re, final double[] im, final int rows, final int columns) {
    return inverse2dReal(re, im, rows, columns, Workers.SEQUENTIAL);
  }

  /**
   * The real part of the 2D inverse transform, unnormalized.
   *
   * @param re      the real parts, row-major; not modified
   * @param im      the imaginary parts, row-major; not modified
   * @param rows    the number of rows
   * @param columns the number of columns
   * @param workers the workers that transform the lines
   * @return the real part of the result
   */
  static double[] inverse2dReal(final double[] re, final double[] im, final int rows, final int columns, final Workers workers) {
    final double[] outRe = re.clone();
    final double[] outIm = im.clone();
    transform2d(outRe, outIm, rows, columns, true, workers);
    return outRe;
  }

  private static void transform2d(
    final double[] re,
    final double[] im,
    final int rows,
    final int columns,
    final boolean inverse,
    final Workers workers
  ) {
    workers.forEach(
      rows,
      () -> new double[2][columns],
      (line, y) -> {
        System.arraycopy(re, y * columns, line[0], 0, columns);
        System.arraycopy(im, y * columns, line[1], 0, columns);
        transform(line[0], line[1], inverse);
        System.arraycopy(line[0], 0, re, y * columns, columns);
        System.arraycopy(line[1], 0, im, y * columns, columns);
      }
    );
    workers.forEach(
      columns,
      () -> new double[2][rows],
      (line, x) -> {
        for (int y = 0; y < rows; y++) {
          line[0][y] = re[y * columns + x];
          line[1][y] = im[y * columns + x];
        }
        transform(line[0], line[1], inverse);
        for (int y = 0; y < rows; y++) {
          re[y * columns + x] = line[0][y];
          im[y * columns + x] = line[1][y];
        }
      }
    );
  }
}
