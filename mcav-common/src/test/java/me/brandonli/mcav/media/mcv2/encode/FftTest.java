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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.mcv2.Workers;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The mixed-radix FFT against a direct DFT, for composite, prime and prime-power lengths. */
final class FftTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Fft.class);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 2, 3, 4, 7, 9, 12, 45, 49, 64, 97, 270 })
  void matchesTheDirectTransform(final int n) {
    final Random random = new Random(n);
    final double[] re = new double[n];
    final double[] im = new double[n];
    for (int i = 0; i < n; i++) {
      re[i] = random.nextDouble() - 0.5;
      im[i] = random.nextDouble() - 0.5;
    }
    for (final boolean inverse : new boolean[] { false, true }) {
      final double[] expectedRe = new double[n];
      final double[] expectedIm = new double[n];
      final double sign = inverse ? 1 : -1;
      for (int k = 0; k < n; k++) {
        for (int t = 0; t < n; t++) {
          final double angle = (sign * 2 * Math.PI * (((long) k * t) % n)) / n;
          expectedRe[k] += re[t] * Math.cos(angle) - im[t] * Math.sin(angle);
          expectedIm[k] += re[t] * Math.sin(angle) + im[t] * Math.cos(angle);
        }
      }
      final double[] actualRe = re.clone();
      final double[] actualIm = im.clone();
      Fft.transform(actualRe, actualIm, inverse);
      assertArrayEquals(expectedRe, actualRe, 1e-9);
      assertArrayEquals(expectedIm, actualIm, 1e-9);
    }
  }

  @Test
  void invertsTheTwoDimensionalTransformUpToTheSampleCount() {
    final int rows = 6;
    final int columns = 10;
    final double[] plane = new double[rows * columns];
    final Random random = new Random(7);
    for (int i = 0; i < plane.length; i++) {
      plane[i] = random.nextInt(256);
    }
    final double[][] spectrum = Fft.forward2d(plane, rows, columns);
    final double[] sum = { 0 };
    for (final double value : plane) {
      sum[0] += value;
    }
    // the DC term is the plane's sum, and the inputs are not modified
    assertEquals(sum[0], spectrum[0][0], 1e-9);
    final double[] restored = Fft.inverse2dReal(spectrum[0], spectrum[1], rows, columns);
    for (int i = 0; i < plane.length; i++) {
      assertEquals(plane[i] * plane.length, restored[i], 1e-6);
    }
    assertEquals(sum[0], spectrum[0][0], 1e-9);
  }

  @Test
  void transformsTheSameOnAnyNumberOfWorkers() {
    final int rows = 45;
    final int columns = 64;
    final double[] plane = new double[rows * columns];
    final Random random = new Random(11);
    for (int i = 0; i < plane.length; i++) {
      plane[i] = random.nextInt(256);
    }
    final ForkJoinPool pool = new ForkJoinPool(4);
    try {
      final Workers workers = new Workers(pool, 4);
      final double[][] sequential = Fft.forward2d(plane, rows, columns);
      final double[][] parallel = Fft.forward2d(plane, rows, columns, workers);
      assertArrayEquals(sequential[0], parallel[0], 0.0);
      assertArrayEquals(sequential[1], parallel[1], 0.0);
      assertArrayEquals(
        Fft.inverse2dReal(sequential[0], sequential[1], rows, columns),
        Fft.inverse2dReal(parallel[0], parallel[1], rows, columns, workers),
        0.0
      );
    } finally {
      pool.shutdownNow();
    }
  }
}
