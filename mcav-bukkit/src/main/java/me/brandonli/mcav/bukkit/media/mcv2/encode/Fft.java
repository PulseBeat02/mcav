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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.brandonli.mcav.bukkit.media.mcv2.Workers;

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
   * @param real      the real parts
   * @param imaginary the imaginary parts
   * @param inverse   whether to apply the inverse transform, unnormalized
   */
  static void transform(final double[] real, final double[] imaginary, final boolean inverse) {
    final int length = real.length;
    if (length <= 1) {
      return;
    }
    int radix = 2;
    while (radix * radix <= length && length % radix != 0) {
      radix++;
    }
    if (length % radix != 0) {
      radix = length;
    }
    final int subLength = length / radix;
    final double[][] subReal = new double[radix][subLength];
    final double[][] subImaginary = new double[radix][subLength];
    for (int subsequence = 0; subsequence < radix; subsequence++) {
      for (int element = 0; element < subLength; element++) {
        subReal[subsequence][element] = real[element * radix + subsequence];
        subImaginary[subsequence][element] = imaginary[element * radix + subsequence];
      }
      if (subLength > 1) {
        transform(subReal[subsequence], subImaginary[subsequence], inverse);
      }
    }
    final double[][] twiddles = twiddles(length, inverse);
    final double[] cosines = twiddles[0];
    final double[] sines = twiddles[1];
    for (int element = 0; element < subLength; element++) {
      for (int outputBlock = 0; outputBlock < radix; outputBlock++) {
        final int index = element + outputBlock * subLength;
        double sumReal = 0;
        double sumImaginary = 0;
        for (int subsequence = 0; subsequence < radix; subsequence++) {
          final int turn = (int) (((long) subsequence * index) % length);
          final double cosine = cosines[turn];
          final double sine = sines[turn];
          sumReal += subReal[subsequence][element] * cosine - subImaginary[subsequence][element] * sine;
          sumImaginary += subReal[subsequence][element] * sine + subImaginary[subsequence][element] * cosine;
        }
        real[index] = sumReal;
        imaginary[index] = sumImaginary;
      }
    }
  }

  /**
   * The twiddle factors of one length and direction: entry j is the cosine and sine of {@code sign * 2 pi j / length},
   * computed exactly as the transform's inner loop would compute them.
   */
  private static double[][] twiddles(final int length, final boolean inverse) {
    return TWIDDLES.computeIfAbsent(inverse ? -length : length, _ -> {
      final double sign = inverse ? 1.0 : -1.0;
      final double[] cosines = new double[length];
      final double[] sines = new double[length];
      for (long turn = 0; turn < length; turn++) {
        final double angle = (sign * 2 * Math.PI * turn) / length;
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
    final double[] real = plane.clone();
    final double[] imaginary = new double[plane.length];
    transform2d(real, imaginary, rows, columns, false, workers);
    return new double[][] { real, imaginary };
  }

  /**
   * The real part of the 2D inverse transform, unnormalized, on the calling thread.
   *
   * @param real      the real parts, row-major; not modified
   * @param imaginary the imaginary parts, row-major; not modified
   * @param rows      the number of rows
   * @param columns   the number of columns
   * @return the real part of the result
   */
  static double[] inverse2dReal(final double[] real, final double[] imaginary, final int rows, final int columns) {
    return inverse2dReal(real, imaginary, rows, columns, Workers.SEQUENTIAL);
  }

  /**
   * The real part of the 2D inverse transform, unnormalized.
   *
   * @param real      the real parts, row-major; not modified
   * @param imaginary the imaginary parts, row-major; not modified
   * @param rows      the number of rows
   * @param columns   the number of columns
   * @param workers   the workers that transform the lines
   * @return the real part of the result
   */
  static double[] inverse2dReal(final double[] real, final double[] imaginary, final int rows, final int columns, final Workers workers) {
    final double[] outReal = real.clone();
    final double[] outImaginary = imaginary.clone();
    transform2d(outReal, outImaginary, rows, columns, true, workers);
    return outReal;
  }

  private static void transform2d(
    final double[] real,
    final double[] imaginary,
    final int rows,
    final int columns,
    final boolean inverse,
    final Workers workers
  ) {
    workers.forEach(
      rows,
      () -> new double[2][columns],
      (line, row) -> {
        System.arraycopy(real, row * columns, line[0], 0, columns);
        System.arraycopy(imaginary, row * columns, line[1], 0, columns);
        transform(line[0], line[1], inverse);
        System.arraycopy(line[0], 0, real, row * columns, columns);
        System.arraycopy(line[1], 0, imaginary, row * columns, columns);
      }
    );
    workers.forEach(
      columns,
      () -> new double[2][rows],
      (line, column) -> {
        for (int row = 0; row < rows; row++) {
          line[0][row] = real[row * columns + column];
          line[1][row] = imaginary[row * columns + column];
        }
        transform(line[0], line[1], inverse);
        for (int row = 0; row < rows; row++) {
          real[row * columns + column] = line[0][row];
          imaginary[row * columns + column] = line[1][row];
        }
      }
    );
  }
}
