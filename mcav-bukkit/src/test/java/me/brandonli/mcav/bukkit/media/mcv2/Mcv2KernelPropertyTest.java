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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.Kernels;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

final class Mcv2KernelPropertyTest {

  private static final int MAXIMUM_CHANNEL_VALUE = 255;

  private static final String SEED = "20260925";

  private static final int[] SIZES = { 8, 16, 32 };

  private static final int BYTE_VALUE_COUNT = 256;

  private static byte extremeByte(final Random random) {
    return switch (random.nextInt(4)) {
      case 0 -> Byte.MIN_VALUE;
      case 1 -> Byte.MAX_VALUE;
      case 2 -> (byte) (random.nextBoolean() ? 0 : -1);
      default -> (byte) random.nextInt(BYTE_VALUE_COUNT);
    };
  }

  private static byte[] record(final Random random, final int length) {
    final byte[] record = new byte[length];
    for (int index = 0; index < length; index++) {
      record[index] = extremeByte(random);
    }

    return record;
  }

  private int[] prediction(final Random random, final int size) {
    final int[] prediction = new int[size * size * 3];
    for (int index = 0; index < prediction.length; index++) {
      prediction[index] = switch (random.nextInt(3)) {
        case 0 -> 0;
        case 1 -> MAXIMUM_CHANNEL_VALUE;
        default -> random.nextInt(BYTE_VALUE_COUNT);
      };
    }

    return prediction;
  }

  @Property(seed = SEED, tries = 1600)
  void shouldMatchTheOracleForCompactRecords(
    @ForAll @IntRange(min = 0, max = 2) final int sizeIndex,
    @ForAll @IntRange(min = 0, max = 2) final int quantizer,
    @ForAll final long seed
  ) {
    final int size = SIZES[sizeIndex];
    final Random random = new Random(seed);
    final byte[] record = record(random, 10);
    final int[] prediction = this.prediction(random, size);
    final int[] expected = new int[size * size * 3];
    final int[] actual = new int[size * size * 3];
    Mcv2Oracle.compact(prediction, record, quantizer, size, expected);
    final Kernels kernels = Mcv2Internals.javaKernels();
    kernels.start(new int[actual.length], 0, Double.POSITIVE_INFINITY);
    final boolean finished = kernels.compact(prediction, record, quantizer, size, actual);
    assertTrue(finished);

    record[0] = 0;
    record[1] = 0;
    final byte[] reference = new byte[prediction.length];
    for (int index = 0; index < reference.length; index++) {
      reference[index] = (byte) prediction[index];
      prediction[index] = reference[index] & MAXIMUM_CHANNEL_VALUE;
    }

    final int[] wholeExpected = new int[prediction.length];
    Mcv2Oracle.compact(prediction, record, quantizer, size, wholeExpected);
    final byte[] decoded = Mcv2Decoder.decode(Mcv2WireFrames.block(size, 5, quantizer, record, Mcv2FrameKind.PREDICTED), reference, 0);
    for (int index = 0; index < decoded.length; index++) {
      final int expectedChannel = wholeExpected[index];
      final int decodedChannel = decoded[index] & MAXIMUM_CHANNEL_VALUE;
      assertEquals(expectedChannel, decodedChannel);
    }

    assertArrayEquals(expected, actual);
  }

  @Property(seed = SEED, tries = 200)
  void shouldMatchTheOracleForPredictions(@ForAll @IntRange(min = 0, max = 2) final int sizeIndex, @ForAll final long seed) {
    final int size = SIZES[sizeIndex];
    final int[] prediction = this.prediction(new Random(seed), size);
    final int[] expected = new int[size * size * 3];
    final int[] actual = new int[size * size * 3];
    Mcv2Oracle.predicted(prediction, size, expected);
    final Kernels kernels = Mcv2Internals.javaKernels();
    kernels.start(new int[actual.length], 0, Double.POSITIVE_INFINITY);
    final boolean finished = kernels.predicted(prediction, size, actual);
    assertTrue(finished);
    assertArrayEquals(expected, actual);
  }

  private long distortion(final int[] source, final int[] output) {
    long sum = 0;
    for (int offset = 0; offset < source.length; offset += 3) {
      final int redDifference = source[offset] - output[offset];
      final int greenDifference = source[offset + 1] - output[offset + 1];
      final int blueDifference = source[offset + 2] - output[offset + 2];
      final int luma = redDifference + 2 * greenDifference + blueDifference;
      final int chromaOrange = redDifference - blueDifference;
      final int chromaGreen = 2 * greenDifference - redDifference - blueDifference;
      sum += 4L * luma * luma + 4L * chromaOrange * chromaOrange + (long) chromaGreen * chromaGreen;
    }

    return sum;
  }

  private void assertMeasured(final Random random, final int size, final Kernel kernel) {
    final int[] source = new int[size * size * 3];
    for (int index = 0; index < source.length; index++) {
      source[index] = random.nextInt(BYTE_VALUE_COUNT);
    }

    final double rate = random.nextInt(4000) / 7.0;
    final Kernels kernels = Mcv2Internals.javaKernels();
    final int[] reconstruction = new int[source.length];
    final int[] repeated = new int[source.length];
    kernels.start(source, rate, Double.POSITIVE_INFINITY);
    final boolean finished = kernel.run(kernels, reconstruction);
    assertTrue(finished);

    kernels.start(source, rate, Double.POSITIVE_INFINITY);
    final boolean finishedAgain = kernel.run(kernels, repeated);
    assertTrue(finishedAgain);
    assertArrayEquals(reconstruction, repeated);

    final long distortion = this.distortion(source, reconstruction);
    final long measured = kernels.getDistortion();
    assertEquals(distortion, measured);

    final double cost = distortion / 96.0 + rate;
    kernels.start(source, rate, cost);
    final boolean finishedAtTheLimit = kernel.run(kernels, new int[source.length]);
    assertFalse(finishedAtTheLimit);

    kernels.start(source, rate, Math.nextUp(cost));
    final boolean finishedPastTheLimit = kernel.run(kernels, new int[source.length]);
    assertTrue(finishedPastTheLimit);

    final long measuredPastTheLimit = kernels.getDistortion();
    assertEquals(distortion, measuredPastTheLimit);
  }

  @Property(seed = SEED, tries = 300)
  void shouldStopMeasuredKernelsOnlyWhenTheyCannotWin(
    @ForAll @IntRange(min = 0, max = 2) final int sizeIndex,
    @ForAll @IntRange(min = 0, max = 3) final int kernel,
    @ForAll @IntRange(min = 0, max = 2) final int quantizer,
    @ForAll final long seed
  ) {
    final int size = SIZES[sizeIndex];
    final Random random = new Random(seed);
    final byte[] record = record(random, 2 + 3 * 64);
    final int[] prediction = this.prediction(random, size);
    final int color = random.nextInt(1 << 24);
    final Kernel run = switch (kernel) {
      case 0 -> (kernels, output) -> kernels.predicted(prediction, size, output);
      case 1 -> (kernels, output) -> kernels.solid(color, size, output);
      case 2 -> (kernels, output) -> kernels.palette(record, size, output);
      default -> (kernels, output) -> kernels.compact(prediction, record, quantizer, size, output);
    };
    this.assertMeasured(random, size, run);
  }

  @FunctionalInterface
  private interface Kernel {
    boolean run(Kernels kernels, int[] output);
  }
}
