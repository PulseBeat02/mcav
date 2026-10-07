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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.bukkit.media.mcv2.Workers;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
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
  void matchesTheDirectTransform(final int length) {
    final Random random = new Random(length);
    final double[] real = new double[length];
    final double[] imaginary = new double[length];
    for (int index = 0; index < length; index++) {
      real[index] = random.nextDouble() - 0.5;
      imaginary[index] = random.nextDouble() - 0.5;
    }
    for (final boolean inverse : new boolean[] { false, true }) {
      final double[] expectedReal = new double[length];
      final double[] expectedImaginary = new double[length];
      final double sign = inverse ? 1 : -1;
      for (int frequency = 0; frequency < length; frequency++) {
        for (int sample = 0; sample < length; sample++) {
          final double angle = (sign * 2 * Math.PI * (((long) frequency * sample) % length)) / length;
          expectedReal[frequency] += real[sample] * Math.cos(angle) - imaginary[sample] * Math.sin(angle);
          expectedImaginary[frequency] += real[sample] * Math.sin(angle) + imaginary[sample] * Math.cos(angle);
        }
      }
      final double[] actualReal = real.clone();
      final double[] actualImaginary = imaginary.clone();
      Fft.transform(actualReal, actualImaginary, inverse);
      assertArrayEquals(expectedReal, actualReal, 1e-9);
      assertArrayEquals(expectedImaginary, actualImaginary, 1e-9);
    }
  }

  @Test
  void invertsTheTwoDimensionalTransformUpToTheSampleCount() {
    final int rows = 6;
    final int columns = 10;
    final double[] plane = new double[rows * columns];
    final Random random = new Random(7);
    for (int index = 0; index < plane.length; index++) {
      plane[index] = random.nextInt(256);
    }
    final double[] original = plane.clone();
    final double[][] spectrum = Fft.forward2d(plane, rows, columns);
    assertArrayEquals(original, plane);
    final double[] originalReal = spectrum[0].clone();
    final double[] originalImaginary = spectrum[1].clone();
    final double[] sum = { 0 };
    for (final double value : plane) {
      sum[0] += value;
    }
    // the DC term is the plane's sum, and the inputs are not modified
    assertEquals(sum[0], spectrum[0][0], 1e-9);
    final double[] restored = Fft.inverse2dReal(spectrum[0], spectrum[1], rows, columns);
    for (int index = 0; index < plane.length; index++) {
      assertEquals(plane[index] * plane.length, restored[index], 1e-6);
    }
    assertEquals(sum[0], spectrum[0][0], 1e-9);
    assertArrayEquals(originalReal, spectrum[0]);
    assertArrayEquals(originalImaginary, spectrum[1]);
  }

  @Test
  void transformsTheSameOnAnyNumberOfWorkers() {
    final int rows = 45;
    final int columns = 64;
    final double[] plane = new double[rows * columns];
    final Random random = new Random(11);
    for (int index = 0; index < plane.length; index++) {
      plane[index] = random.nextInt(256);
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
