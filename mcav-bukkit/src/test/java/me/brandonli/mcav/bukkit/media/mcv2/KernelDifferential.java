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

final class KernelDifferential {

  static final int KERNELS = 13;
  private static final int SCORED_KERNELS = 4;

  private static final int PREDICTED_KERNEL = 0;
  private static final int SOLID_KERNEL = 1;
  private static final int PALETTE_KERNEL = 2;
  private static final int PREDICT_KERNEL = 4;
  private static final int FIT_KERNEL = 5;
  private static final int CLUSTER_KERNEL = 6;
  private static final int FINISH_PALETTE_KERNEL = 7;
  private static final int FINISH_PATTERN_KERNEL = 8;
  private static final int SEEDED_KERNEL = 9;
  private static final int LOAD_SOURCE_KERNEL = 10;
  private static final int HALVE_KERNEL = 11;

  private static final int MAXIMUM_CHANNEL = 255;
  private static final int NARROW_SPREAD = 8;

  private static final int MAXIMUM_MOTION = 40;

  // the widest a Values draws: the size of its range must fit an int
  private static final int EXTREME_LOW = -(1 << 30) + 1;
  private static final int EXTREME_HIGH = (1 << 30) - 1;

  private KernelDifferential() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

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

  private static int[] drawIntegers(final Values random, final int count, final int low, final int high) {
    final int[] values = new int[count];
    for (int index = 0; index < count; index++) {
      values[index] = random.next(low, high);
    }

    return values;
  }

  private static float[] drawFloats(final Values random, final int count) {
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

  private static @Nullable String describeMismatch(final boolean equal, final String kernelName) {
    return equal ? null : kernelName;
  }

  private static boolean isBitwiseEqual(final float[] expected, final float[] actual) {
    for (int index = 0; index < expected.length; index++) {
      if (Float.floatToRawIntBits(expected[index]) != Float.floatToRawIntBits(actual[index])) {
        return false;
      }
    }

    return true;
  }

  private static int drawBlockSize(final Values random) {
    return 8 << random.next(0, 2);
  }

  static @Nullable String compare(final Values random, final Kernels java, final Kernels other) {
    final int kernel = random.next(0, KERNELS - 1);
    return compare(random, java, other, kernel);
  }

  static @Nullable String compare(final Values random, final Kernels java, final Kernels other, final int kernel) {
    final int size = drawBlockSize(random);
    final int channels = size * size * 3;
    if (kernel < SCORED_KERNELS) {
      return compareScored(random, java, other, kernel, size, channels);
    }

    return compareUnscored(random, java, other, kernel, size, channels);
  }

  private static @Nullable String compareScored(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    final int[] source = drawIntegers(random, channels, 0, MAXIMUM_CHANNEL);
    final int[] prediction = drawIntegers(random, channels, 0, MAXIMUM_CHANNEL);
    final double rate = random.next(0, 100_000) / 7.0;
    // an infinite limit, or one some candidates cross part way through the block
    final double limit = drawLimit(random, rate, size);
    final int[] expected = new int[channels];
    final int[] actual = new int[channels];
    java.start(source, rate, limit);
    other.start(source, rate, limit);
    final boolean finishedJava;
    final boolean finishedOther;
    final String name;
    switch (kernel) {
      case PREDICTED_KERNEL -> {
        name = "predicted";
        finishedJava = java.predicted(prediction, size, expected);
        finishedOther = other.predicted(prediction, size, actual);
      }
      case SOLID_KERNEL -> {
        name = "solid";
        final int color = random.next(0, 0xFFFFFF);
        finishedJava = java.solid(color, size, expected);
        finishedOther = other.solid(color, size, actual);
      }
      case PALETTE_KERNEL -> {
        name = "palette";
        final int recordLength = 6 + (size * size) / 8 + random.next(0, 2);
        final byte[] record = random.bytes(recordLength);
        finishedJava = java.palette(record, size, expected);
        finishedOther = other.palette(record, size, actual);
      }
      default -> {
        name = "compact";
        final int quantizer = random.next(0, 2);
        final byte[] record = random.bytes(Mcv2Decoder.COMPACT_BYTES);
        finishedJava = java.compact(prediction, record, quantizer, size, expected);
        finishedOther = other.compact(prediction, record, quantizer, size, actual);
      }
    }

    if (finishedJava != finishedOther) {
      return "%s finished".formatted(name);
    }

    if (finishedJava && java.getDistortion() != other.getDistortion()) {
      return "%s distortion".formatted(name);
    }

    return describeMismatch(Arrays.equals(expected, actual), "%s reconstruction".formatted(name));
  }

  private static double drawLimit(final Values random, final double rate, final int size) {
    if (random.next(0, 3) == 0) {
      return Double.POSITIVE_INFINITY;
    }

    return rate + random.next(0, size * size * 3000);
  }

  private static @Nullable String compareUnscored(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    return switch (kernel) {
      case PREDICT_KERNEL -> comparePredict(random, java, other, size, channels);
      case FIT_KERNEL -> compareFit(random, java, other, size);
      case CLUSTER_KERNEL -> compareCluster(random, java, other, size, channels);
      case FINISH_PALETTE_KERNEL, FINISH_PATTERN_KERNEL -> comparePalettes(random, java, other, kernel, size, channels);
      case SEEDED_KERNEL -> compareSeeded(random, java, other, size, channels);
      default -> compareHelpers(random, java, other, kernel, size, channels);
    };
  }

  private static @Nullable String comparePredict(
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
    final int motionX = random.next(-MAXIMUM_MOTION, MAXIMUM_MOTION);
    final int motionY = random.next(-MAXIMUM_MOTION, MAXIMUM_MOTION);
    final int[] expected = new int[channels];
    final int[] actual = new int[channels];
    java.predict(reference, width, height, blockLeft, blockTop, size, motionX, motionY, expected);
    other.predict(reference, width, height, blockLeft, blockTop, size, motionX, motionY, actual);
    return describeMismatch(Arrays.equals(expected, actual), "predict");
  }

  private static @Nullable String compareFit(final Values random, final Kernels java, final Kernels other, final int size) {
    final float[] values = drawFloats(random, size * size);
    final float[] expected = drawFloats(random, 18);
    final float[] actual = expected.clone();
    java.fit(values, size, expected);
    other.fit(values, size, actual);
    return describeMismatch(isBitwiseEqual(expected, actual), "fit");
  }

  private static @Nullable String compareCluster(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int size,
    final int channels
  ) {
    final int low = random.next(0, MAXIMUM_CHANNEL);
    final int[] source = drawClusterSource(random, channels, low);
    final int[] expected = new int[6];
    final int[] actual = new int[6];
    java.cluster(source, size, expected);
    other.cluster(source, size, actual);
    return describeMismatch(Arrays.equals(expected, actual), "cluster");
  }

  private static int[] drawClusterSource(final Values random, final int channels, final int low) {
    if (random.next(0, 7) == 0) {
      return drawIntegers(random, channels, EXTREME_LOW, EXTREME_HIGH);
    }

    final boolean narrow = random.next(0, 1) == 0;
    final int spread = narrow ? NARROW_SPREAD : MAXIMUM_CHANNEL;
    final int high = Math.min(MAXIMUM_CHANNEL, low + spread);
    return drawIntegers(random, channels, low, high);
  }

  private static @Nullable String comparePalettes(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    final int[] source = drawIntegers(random, channels, 0, MAXIMUM_CHANNEL);
    if (random.next(0, 1) == 0) {
      // every row the first, so a pattern holds
      for (int row = 1; row < size; row++) {
        System.arraycopy(source, 0, source, row * size * 3, size * 3);
      }
    }

    final int[] endpoints = new int[6];
    for (int index = 0; index < endpoints.length; index++) {
      endpoints[index] = random.next(0, MAXIMUM_CHANNEL);
    }

    final int[] expectedColors = new int[6];
    final int[] actualColors = new int[6];
    final byte[] expected = new byte[size * size];
    final byte[] actual = new byte[size * size];
    if (kernel == FINISH_PALETTE_KERNEL) {
      java.finishPalette(source, size * size, endpoints, expectedColors, expected);
      other.finishPalette(source, size * size, endpoints, actualColors, actual);
      return describeMismatch(Arrays.equals(expectedColors, actualColors) && Arrays.equals(expected, actual), "finishPalette");
    }

    final boolean heldJava = java.finishPattern(source, size, endpoints, expectedColors, expected);
    final boolean heldOther = other.finishPattern(source, size, endpoints, actualColors, actual);
    return describeMismatch(
      heldJava == heldOther && Arrays.equals(expectedColors, actualColors) && Arrays.equals(expected, actual),
      "finishPattern"
    );
  }

  private static @Nullable String compareSeeded(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int size,
    final int channels
  ) {
    final int width = random.next(1, 120);
    final int height = random.next(1, 90);
    final byte[] reference = random.bytes(width * height * 3);
    final int blockLeft = random.next(0, width - 1);
    final int blockTop = random.next(0, height - 1);
    final int[] source = drawIntegers(random, channels, 0, MAXIMUM_CHANNEL);
    if (random.next(0, 1) == 0) {
      // the block itself, so the search has a place to go
      java.loadSource(reference, width, height, blockLeft, blockTop, size, source);
    }

    if (random.next(0, 7) == 0) {
      // a channel that is no byte, which the search must cost as an int
      final int channel = random.next(0, channels - 1);
      final int value = random.next(EXTREME_LOW, EXTREME_HIGH);
      source[channel] = value;
    }

    final int seedCount = random.next(0, 6);
    final int[] seeds = new int[seedCount];
    for (int seedIndex = 0; seedIndex < seeds.length; seedIndex++) {
      final int motionX = random.next(-MAXIMUM_MOTION, MAXIMUM_MOTION);
      final int motionY = random.next(-MAXIMUM_MOTION, MAXIMUM_MOTION);
      seeds[seedIndex] = (motionX << 16) | (motionY & 0xFFFF);
    }

    final int range = random.next(0, 24);
    final int expected = java.seeded(reference, width, height, source, blockLeft, blockTop, size, range, seeds);
    final int actual = other.seeded(reference, width, height, source, blockLeft, blockTop, size, range, seeds);
    return describeMismatch(expected == actual, "seeded");
  }

  private static @Nullable String compareHelpers(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    final int count = size * size;
    final int[] source = drawIntegers(random, channels, 0, MAXIMUM_CHANNEL);
    return switch (kernel) {
      case LOAD_SOURCE_KERNEL -> {
        final int width = random.next(1, 70);
        final int height = random.next(1, 70);
        final byte[] image = random.bytes(width * height * 3);
        final int blockLeft = random.next(0, width - 1);
        final int blockTop = random.next(0, height - 1);
        final int[] expected = new int[channels];
        final int[] actual = new int[channels];
        java.loadSource(image, width, height, blockLeft, blockTop, size, expected);
        other.loadSource(image, width, height, blockLeft, blockTop, size, actual);
        yield describeMismatch(Arrays.equals(expected, actual), "loadSource");
      }
      case HALVE_KERNEL -> {
        final int[] expected = new int[channels / 4];
        final int[] actual = new int[channels / 4];
        java.halve(source, size, expected);
        other.halve(source, size, actual);
        yield describeMismatch(Arrays.equals(expected, actual), "halve");
      }
      default -> {
        final int pixels = random.next(0, count);
        final int[] prediction = drawIntegers(random, channels, 0, MAXIMUM_CHANNEL);
        final float[] expected = drawFloats(random, count + 2);
        final float[] actual = expected.clone();
        java.residualTarget(source, prediction, pixels, expected);
        other.residualTarget(source, prediction, pixels, actual);
        yield describeMismatch(isBitwiseEqual(expected, actual), "residualTarget");
      }
    };
  }

  interface Values {
    int next(int low, int high);

    byte[] bytes(int count);
  }
}
