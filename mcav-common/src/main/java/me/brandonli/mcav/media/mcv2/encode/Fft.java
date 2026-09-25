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

/**
 * A mixed-radix complex FFT of any length, recursive Cooley-Tukey over the length's prime factors, and the 2D
 * transforms the global motion estimate needs. Lengths of video planes factor into small primes, so the cost stays
 * close to {@code n log n}; a large prime factor degrades gracefully to a direct DFT of that factor.
 */
final class Fft {

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
    final double sign = inverse ? 1.0 : -1.0;
    for (int k = 0; k < m; k++) {
      for (int q = 0; q < p; q++) {
        final int index = k + q * m;
        double sumRe = 0;
        double sumIm = 0;
        for (int r = 0; r < p; r++) {
          final double angle = (sign * 2 * Math.PI * (((long) r * index) % n)) / n;
          final double c = Math.cos(angle);
          final double s = Math.sin(angle);
          sumRe += subRe[r][k] * c - subIm[r][k] * s;
          sumIm += subRe[r][k] * s + subIm[r][k] * c;
        }
        re[index] = sumRe;
        im[index] = sumIm;
      }
    }
  }

  /**
   * The 2D forward transform of a real plane.
   *
   * @param plane   the values, row-major
   * @param rows    the number of rows
   * @param columns the number of columns
   * @return the real and imaginary parts, row-major
   */
  static double[][] forward2d(final double[] plane, final int rows, final int columns) {
    final double[] re = plane.clone();
    final double[] im = new double[plane.length];
    transform2d(re, im, rows, columns, false);
    return new double[][] { re, im };
  }

  /**
   * The real part of the 2D inverse transform, unnormalized.
   *
   * @param re      the real parts, row-major; not modified
   * @param im      the imaginary parts, row-major; not modified
   * @param rows    the number of rows
   * @param columns the number of columns
   * @return the real part of the result
   */
  static double[] inverse2dReal(final double[] re, final double[] im, final int rows, final int columns) {
    final double[] outRe = re.clone();
    final double[] outIm = im.clone();
    transform2d(outRe, outIm, rows, columns, true);
    return outRe;
  }

  private static void transform2d(final double[] re, final double[] im, final int rows, final int columns, final boolean inverse) {
    final double[] rowRe = new double[columns];
    final double[] rowIm = new double[columns];
    for (int y = 0; y < rows; y++) {
      System.arraycopy(re, y * columns, rowRe, 0, columns);
      System.arraycopy(im, y * columns, rowIm, 0, columns);
      transform(rowRe, rowIm, inverse);
      System.arraycopy(rowRe, 0, re, y * columns, columns);
      System.arraycopy(rowIm, 0, im, y * columns, columns);
    }
    final double[] columnRe = new double[rows];
    final double[] columnIm = new double[rows];
    for (int x = 0; x < columns; x++) {
      for (int y = 0; y < rows; y++) {
        columnRe[y] = re[y * columns + x];
        columnIm[y] = im[y * columns + x];
      }
      transform(columnRe, columnIm, inverse);
      for (int y = 0; y < rows; y++) {
        re[y * columns + x] = columnRe[y];
        im[y * columns + x] = columnIm[y];
      }
    }
  }
}
