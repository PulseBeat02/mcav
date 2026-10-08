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

/**
 * The integer kernels of {@link Mcv2Decoder} against their floating-point oracle, the form the conformance streams
 * verified: for every leaf size, kept compact class and every quantizer the format allows (0 to 7, beyond the 3 and 4
 * the encoder uses), and for records and predictions drawn with half of their values at the extremes of their range,
 * where a rounding float operation would show first, both must produce the same pixels.
 */
final class MCV2KernelPropertyTest {

  private static final String SEED = "20260925";

  private static final int[] SIZES = { 8, 16, 32 };

  /** A byte biased to the ends of its range. */
  private static byte extremeByte(final Random random) {
    return switch (random.nextInt(4)) {
      case 0 -> Byte.MIN_VALUE;
      case 1 -> Byte.MAX_VALUE;
      case 2 -> (byte) (random.nextBoolean() ? 0 : -1);
      default -> (byte) random.nextInt(256);
    };
  }

  private static byte[] record(final Random random, final int length) {
    final byte[] record = new byte[length];
    for (int index = 0; index < length; index++) {
      record[index] = extremeByte(random);
    }
    return record;
  }

  /** Four times a predicted channel: a whole channel from 0 to 255, biased to the ends. */
  private static int[] prediction(final Random random, final int size) {
    final int[] prediction = new int[size * size * 3];
    for (int index = 0; index < prediction.length; index++) {
      prediction[index] = switch (random.nextInt(3)) {
        case 0 -> 0;
        case 1 -> 1020;
        default -> 4 * random.nextInt(256);
      };
    }
    return prediction;
  }

  @Property(seed = SEED, tries = 1600)
  void compactRecordsMatchTheOracle(
    @ForAll @IntRange(min = 0, max = 2) final int sizeIndex,
    @ForAll @IntRange(min = 0, max = 2) final int kind,
    @ForAll @IntRange(min = 0, max = 7) final int quantizer,
    @ForAll final long seed
  ) throws Mcv2Exception {
    final int size = SIZES[sizeIndex];
    final Random random = new Random(seed);
    final byte[] record = record(random, 18);
    final int[] prediction = prediction(random, size);
    final int[] expected = new int[size * size * 3];
    final int[] actual = new int[size * size * 3];
    Mcv2Oracle.compact(prediction, record, 0, kind, quantizer, size, expected);
    final Kernels kernels = Mcv2Internals.javaKernels();
    kernels.start(new int[actual.length], 0, Double.POSITIVE_INFINITY);
    assertTrue(kernels.compact(prediction, record, 0, kind, quantizer, size, actual));
    final int bytes = kind == 0 ? 1 : kind == 1 ? 10 : 8;
    final byte[] wireRecord = new byte[1 + bytes];
    wireRecord[0] = (byte) kind;
    System.arraycopy(record, 0, wireRecord, 1, bytes);
    final byte[] reference = new byte[prediction.length];
    for (int index = 0; index < reference.length; index++) {
      reference[index] = (byte) (prediction[index] / 4);
      prediction[index] = (reference[index] & 255) * 4;
    }
    final int[] wholeExpected = new int[prediction.length];
    Mcv2Oracle.compact(prediction, record, 0, kind, quantizer, size, wholeExpected);
    final byte[] decoded = Mcv2Decoder.decode(Mcv2WireFrames.block(size, 5, quantizer, wireRecord, false), reference, 0);
    for (int index = 0; index < decoded.length; index++) {
      assertEquals(wholeExpected[index], decoded[index] & 255);
    }
    assertArrayEquals(expected, actual);
  }

  @Property(seed = SEED, tries = 200)
  void predictionsRoundLikeTheOracle(@ForAll @IntRange(min = 0, max = 2) final int sizeIndex, @ForAll final long seed) {
    final int size = SIZES[sizeIndex];
    final int[] prediction = prediction(new Random(seed), size);
    final int[] expected = new int[size * size * 3];
    final int[] actual = new int[size * size * 3];
    Mcv2Oracle.predicted(prediction, size, expected);
    final Kernels kernels = Mcv2Internals.javaKernels();
    kernels.start(new int[actual.length], 0, Double.POSITIVE_INFINITY);
    assertTrue(kernels.predicted(prediction, size, actual));
    assertArrayEquals(expected, actual);
  }

  /** One kernel call: reconstruct into out, measured by score when it is not null; returns whether it finished. */
  @FunctionalInterface
  private interface Kernel {
    boolean run(Kernels kernels, int[] out);
  }

  /** The source a measure compares with, and the same measure's plain distortion of a reconstruction. */
  private static long distortion(final int[] source, final int[] out) {
    long sum = 0;
    for (int offset = 0; offset < source.length; offset += 3) {
      final int redDifference = source[offset] - out[offset];
      final int greenDifference = source[offset + 1] - out[offset + 1];
      final int blueDifference = source[offset + 2] - out[offset + 2];
      final int luma = redDifference + 2 * greenDifference + blueDifference;
      final int chromaOrange = redDifference - blueDifference;
      final int chromaGreen = 2 * greenDifference - redDifference - blueDifference;
      sum += 4L * luma * luma + 4L * chromaOrange * chromaOrange + (long) chromaGreen * chromaGreen;
    }
    return sum;
  }

  /**
   * A measured kernel reconstructs exactly what the unmeasured one does, and its distortion is the whole block's; it
   * stops once its cost reaches the limit, and never before the whole block's cost does.
   */
  private static void assertMeasured(final Random random, final int size, final Kernel kernel) {
    final int[] source = new int[size * size * 3];
    for (int index = 0; index < source.length; index++) {
      source[index] = random.nextInt(256);
    }
    final double rate = random.nextInt(4000) / 7.0;
    final Kernels kernels = Mcv2Internals.javaKernels();
    final int[] plain = new int[source.length];
    final int[] measured = new int[source.length];
    kernels.start(source, rate, Double.POSITIVE_INFINITY);
    assertTrue(kernel.run(kernels, plain));
    kernels.start(source, rate, Double.POSITIVE_INFINITY);
    assertTrue(kernel.run(kernels, measured));
    assertArrayEquals(plain, measured);
    final long distortion = distortion(source, plain);
    assertEquals(distortion, kernels.distortion());
    final double cost = distortion / 96.0 + rate;
    kernels.start(source, rate, cost);
    assertFalse(kernel.run(kernels, new int[source.length]));
    kernels.start(source, rate, Math.nextUp(cost));
    assertTrue(kernel.run(kernels, new int[source.length]));
    assertEquals(distortion, kernels.distortion());
  }

  @Property(seed = SEED, tries = 300)
  void measuredKernelsStopOnlyWhenTheyCannotWin(
    @ForAll @IntRange(min = 0, max = 2) final int sizeIndex,
    @ForAll @IntRange(min = 0, max = 5) final int kernel,
    @ForAll @IntRange(min = 0, max = 3) final int quantizer,
    @ForAll final long seed
  ) {
    final int size = SIZES[sizeIndex];
    final Random random = new Random(seed);
    final byte[] record = record(random, 2 + 3 * 64);
    final int[] prediction = prediction(random, size);
    final int color = random.nextInt(1 << 24);
    final Kernel run = switch (kernel) {
      case 0 -> (kernels, out) -> kernels.predicted(prediction, size, out);
      case 1 -> (kernels, out) -> kernels.solid(color, size, out);
      case 2 -> (kernels, out) -> kernels.palette(record, 0, size, out);
      default -> (kernels, out) -> kernels.compact(prediction, record, 0, kernel - 3, quantizer, size, out);
    };
    assertMeasured(random, size, run);
  }
}
