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

import java.util.Arrays;
import java.util.Random;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.Kernels;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One kernel, run by two sets of kernels on the same inputs: the differential test of the native kernels against the
 * Java ones, which the property test drives from seeds and the fuzz test from fuzzed bytes. Every kernel gets inputs
 * in the ranges the encoder gives it - channels 0 to 255, four-times predictions 0 to 1020, any record bytes, blocks of
 * every size, the compact grid, limits that stop a candidate part way, pictures whose edges a block or a motion
 * vector crosses - and now and then values far outside a picture's, whose sums overflow an int in the clusterings and
 * that are no bytes in the motion search; every output, integer or floating-point, must be identical to the bit.
 */
final class KernelDifferential {

  /** The kernels one comparison chooses from. */
  static final int KERNELS = 14;

  private static final int[] COMPACT_CLASSES = { Mcv2Decoder.COMPACT_DC, Mcv2Decoder.COMPACT_GRID, Mcv2Decoder.COMPACT_GRID_Y };

  /** The widest values a {@link Values} can draw: the range's size must fit an int. */
  private static final int EXTREME_LOW = -(1 << 30) + 1;

  private static final int EXTREME_HIGH = (1 << 30) - 1;

  /** Where the inputs come from. */
  interface Values {
    /**
     * Gives an int in a range.
     *
     * @param low  the least value
     * @param high the greatest value
     * @return the value
     */
    int next(int low, int high);

    /**
     * Gives bytes.
     *
     * @param count how many
     * @return the bytes
     */
    byte[] bytes(int count);
  }

  private KernelDifferential() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Draws values from a random generator.
   *
   * @param random the generator
   * @return the values
   */
  static Values of(final Random random) {
    return new Values() {
      @Override
      public int next(final int low, final int high) {
        return low + random.nextInt(high - low + 1);
      }

      @Override
      public byte[] bytes(final int count) {
        final byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
      }
    };
  }

  private static int[] ints(final Values random, final int count, final int low, final int high) {
    final int[] values = new int[count];
    for (int index = 0; index < count; index++) {
      values[index] = random.next(low, high);
    }
    return values;
  }

  /** Floats as the encoder's fits see them, now and then an extreme one. */
  private static float[] floats(final Values random, final int count) {
    final float[] values = new float[count];
    for (int index = 0; index < count; index++) {
      values[index] = switch (random.next(0, 15)) {
        case 0 -> -0.0f;
        case 1 -> Float.MIN_VALUE * random.next(1, 1000);
        case 2 -> 1e30f * random.next(-3, 3);
        default -> random.next(-1_000_000, 1_000_000) / 3.0f / 1000;
      };
    }
    return values;
  }

  /** Nothing when the outputs are equal, else what differed. */
  private static @Nullable String unless(final boolean equal, final String what) {
    return equal ? null : what;
  }

  private static boolean same(final float[] expected, final float[] actual) {
    for (int index = 0; index < expected.length; index++) {
      if (Float.floatToRawIntBits(expected[index]) != Float.floatToRawIntBits(actual[index])) {
        return false;
      }
    }
    return true;
  }

  private static int size(final Values random) {
    return 8 << random.next(0, 2);
  }

  /**
   * Runs one kernel, chosen by the values, on inputs from them with both kernels.
   *
   * @param random the values
   * @param java   the kernels the others are compared with
   * @param other  the kernels compared
   * @return the name of what differed, or null when nothing did
   */
  static @Nullable String compare(final Values random, final Kernels java, final Kernels other) {
    return compare(random, java, other, random.next(0, KERNELS - 1));
  }

  /**
   * Runs one kernel on inputs from the values with both kernels.
   *
   * @param random the values
   * @param java   the kernels the others are compared with
   * @param other  the kernels compared
   * @param kernel the kernel, 0 to {@link #KERNELS} - 1
   * @return the name of what differed, or null when nothing did
   */
  static @Nullable String compare(final Values random, final Kernels java, final Kernels other, final int kernel) {
    final int size = size(random);
    final int channels = size * size * 3;
    return kernel < 4 ? scored(random, java, other, kernel, size, channels) : unscored(random, java, other, kernel, size, channels);
  }

  /** A scored reconstruction: whether it finished, its distortion and every value it wrote must agree. */
  private static @Nullable String scored(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    final int[] source = ints(random, channels, 0, 255);
    final int[] prediction = ints(random, channels, 0, 1020);
    final double rate = random.next(0, 100_000) / 7.0;
    // an infinite limit, or one some candidates cross part way through the block
    final double limit = random.next(0, 3) == 0 ? Double.POSITIVE_INFINITY : rate + random.next(0, size * size * 3000);
    final int[] expected = new int[channels];
    final int[] actual = new int[channels];
    java.start(source, rate, limit);
    other.start(source, rate, limit);
    final boolean finishedJava;
    final boolean finishedOther;
    final String name;
    switch (kernel) {
      case 0 -> {
        name = "predicted";
        finishedJava = java.predicted(prediction, size, expected);
        finishedOther = other.predicted(prediction, size, actual);
      }
      case 1 -> {
        name = "solid";
        final int color = random.next(0, 0xFFFFFF);
        finishedJava = java.solid(color, size, expected);
        finishedOther = other.solid(color, size, actual);
      }
      case 2 -> {
        name = "palette";
        final int offset = random.next(0, 3);
        final byte[] record = random.bytes(offset + 6 + (size * size) / 8 + random.next(0, 2));
        finishedJava = java.palette(record, offset, size, expected);
        finishedOther = other.palette(record, offset, size, actual);
      }
      default -> {
        final int kind = COMPACT_CLASSES[random.next(0, COMPACT_CLASSES.length - 1)];
        name = "compact " + kind;
        final int quantizer = random.next(0, 7);
        final int body = random.next(0, 2);
        final byte[] record = random.bytes(body + (kind == Mcv2Decoder.COMPACT_DC ? 1 : kind == Mcv2Decoder.COMPACT_GRID ? 10 : 8));
        finishedJava = java.compact(prediction, record, body, kind, quantizer, size, expected);
        finishedOther = other.compact(prediction, record, body, kind, quantizer, size, actual);
      }
    }
    if (finishedJava != finishedOther) {
      return name + " finished";
    }
    if (finishedJava && java.distortion() != other.distortion()) {
      return name + " distortion";
    }
    return unless(Arrays.equals(expected, actual), name + " reconstruction");
  }

  private static @Nullable String unscored(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    return switch (kernel) {
      case 4 -> predict(random, java, other, size, channels);
      case 5 -> fit(random, java, other, size);
      case 6 -> cluster(random, java, other, size, channels);
      case 7, 8 -> palettes(random, java, other, kernel, size, channels);
      case 9 -> seeded(random, java, other, size, channels);
      default -> helpers(random, java, other, kernel, size, channels);
    };
  }

  private static @Nullable String predict(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int size,
    final int channels
  ) {
    final int width = random.next(1, 80);
    final int height = random.next(1, 60);
    final byte[] reference = random.bytes(width * height * 3);
    final int blockLeft = random.next(-10, width + 10);
    final int blockTop = random.next(-10, height + 10);
    final int motionX = random.next(-40, 40);
    final int motionY = random.next(-40, 40);
    final int[] expected = new int[channels];
    final int[] actual = new int[channels];
    java.predict(reference, width, height, blockLeft, blockTop, size, motionX, motionY, expected);
    other.predict(reference, width, height, blockLeft, blockTop, size, motionX, motionY, actual);
    return unless(Arrays.equals(expected, actual), "predict");
  }

  private static @Nullable String fit(final Values random, final Kernels java, final Kernels other, final int size) {
    final int grid = 4;
    final int stride = random.next(0, 1) == 0 ? 1 : 3;
    final int offset = stride == 3 ? random.next(0, 2) : 0;
    final float[] values = floats(random, size * size * stride + offset);
    final int outOffset = random.next(0, 2);
    final int outStride = random.next(1, 2);
    final float[] expected = new float[outOffset + grid * grid * outStride];
    final float[] actual = new float[expected.length];
    java.fit(values, offset, stride, size, expected, outOffset, outStride);
    other.fit(values, offset, stride, size, actual, outOffset, outStride);
    return unless(same(expected, actual), "fit");
  }

  private static @Nullable String cluster(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int size,
    final int channels
  ) {
    final int low = random.next(0, 255);
    final int[] source =
      random.next(0, 7) == 0
        ? ints(random, channels, EXTREME_LOW, EXTREME_HIGH)
        : ints(random, channels, low, Math.min(255, low + (random.next(0, 1) == 0 ? 8 : 255)));
    final float[] expected = new float[6];
    final float[] actual = new float[6];
    java.cluster(source, size, expected);
    other.cluster(source, size, actual);
    return unless(same(expected, actual), "cluster");
  }

  private static @Nullable String palettes(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    final int[] source = ints(random, channels, 0, 255);
    if (random.next(0, 1) == 0) {
      // every row the first, so a pattern holds
      for (int row = 1; row < size; row++) {
        System.arraycopy(source, 0, source, row * size * 3, size * 3);
      }
    }
    final float[] endpoints = new float[6];
    for (int index = 0; index < endpoints.length; index++) {
      endpoints[index] = random.next(-2000, 257_000) / 1000.0f;
    }
    final int[] expectedColors = new int[6];
    final int[] actualColors = new int[6];
    final byte[] expected = new byte[size * size];
    final byte[] actual = new byte[size * size];
    if (kernel == 7) {
      java.finish(source, size * size, endpoints, expectedColors, expected);
      other.finish(source, size * size, endpoints, actualColors, actual);
      return unless(Arrays.equals(expectedColors, actualColors) && Arrays.equals(expected, actual), "finish");
    }
    final boolean held = java.finishPattern(source, size, endpoints, expectedColors, expected);
    final boolean holds = other.finishPattern(source, size, endpoints, actualColors, actual);
    return unless(held == holds && Arrays.equals(expectedColors, actualColors) && Arrays.equals(expected, actual), "finishPattern");
  }

  private static @Nullable String seeded(final Values random, final Kernels java, final Kernels other, final int size, final int channels) {
    final int width = random.next(1, 120);
    final int height = random.next(1, 90);
    final byte[] reference = random.bytes(width * height * 3);
    final int blockLeft = random.next(0, width - 1);
    final int blockTop = random.next(0, height - 1);
    final int[] source = ints(random, channels, 0, 255);
    if (random.next(0, 1) == 0) {
      // the block itself, so the search has a place to go
      java.loadSource(reference, width, height, blockLeft, blockTop, size, source);
    }
    if (random.next(0, 7) == 0) {
      // a channel that is no byte, which the search must cost as an int
      source[random.next(0, channels - 1)] = random.next(EXTREME_LOW, EXTREME_HIGH);
    }
    final int[] seeds = new int[random.next(0, 6)];
    for (int seedIndex = 0; seedIndex < seeds.length; seedIndex++) {
      seeds[seedIndex] = (random.next(-40, 40) << 16) | (random.next(-40, 40) & 0xFFFF);
    }
    final int range = random.next(0, 24);
    final int expected = java.seeded(reference, width, height, source, blockLeft, blockTop, size, range, seeds);
    final int actual = other.seeded(reference, width, height, source, blockLeft, blockTop, size, range, seeds);
    return unless(expected == actual, "seeded");
  }

  private static @Nullable String helpers(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    final int count = size * size;
    final int[] source = ints(random, channels, 0, 255);
    return switch (kernel) {
      case 10 -> {
        final int width = random.next(1, 70);
        final int height = random.next(1, 70);
        final byte[] image = random.bytes(width * height * 3);
        final int blockLeft = random.next(0, width - 1);
        final int blockTop = random.next(0, height - 1);
        final int[] expected = new int[channels];
        final int[] actual = new int[channels];
        java.loadSource(image, width, height, blockLeft, blockTop, size, expected);
        other.loadSource(image, width, height, blockLeft, blockTop, size, actual);
        yield unless(Arrays.equals(expected, actual), "loadSource");
      }
      case 11 -> {
        final int[] expected = new int[channels / 4];
        final int[] actual = new int[channels / 4];
        java.halve(source, size, expected);
        other.halve(source, size, actual);
        yield unless(Arrays.equals(expected, actual), "halve");
      }
      case 12 -> {
        final float[] expected = floats(random, channels);
        final float[] actual = expected.clone();
        java.ycocg(source, count, expected);
        other.ycocg(source, count, actual);
        yield unless(same(expected, actual), "ycocg");
      }
      default -> {
        final float[] ycocg = floats(random, channels);
        final int[] prediction = ints(random, channels, 0, 1020);
        final float[] expected = floats(random, channels);
        final float[] actual = expected.clone();
        java.residualTarget(ycocg, prediction, count, expected);
        other.residualTarget(ycocg, prediction, count, actual);
        yield unless(same(expected, actual), "residualTarget");
      }
    };
  }
}
