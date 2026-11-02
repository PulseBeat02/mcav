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
    return compare(random, java, other, random.next(0, KERNELS - 1));
  }

  static @Nullable String compare(final Values random, final Kernels java, final Kernels other, final int kernel) {
    final int size = drawBlockSize(random);
    final int channels = size * size * 3;
    return kernel < 4
      ? compareScored(random, java, other, kernel, size, channels)
      : compareUnscored(random, java, other, kernel, size, channels);
  }

  private static @Nullable String compareScored(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    final int[] source = drawIntegers(random, channels, 0, 255);
    final int[] prediction = drawIntegers(random, channels, 0, 255);
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
        final byte[] record = random.bytes(6 + (size * size) / 8 + random.next(0, 2));
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

  private static @Nullable String compareUnscored(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    return switch (kernel) {
      case 4 -> comparePredict(random, java, other, size, channels);
      case 5 -> compareFit(random, java, other, size);
      case 6 -> compareCluster(random, java, other, size, channels);
      case 7, 8 -> comparePalettes(random, java, other, kernel, size, channels);
      case 9 -> compareSeeded(random, java, other, size, channels);
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
    final int motionX = random.next(-40, 40);
    final int motionY = random.next(-40, 40);
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
    final int low = random.next(0, 255);
    final int[] source =
      random.next(0, 7) == 0
        ? drawIntegers(random, channels, EXTREME_LOW, EXTREME_HIGH)
        : drawIntegers(random, channels, low, Math.min(255, low + (random.next(0, 1) == 0 ? 8 : 255)));
    final int[] expected = new int[6];
    final int[] actual = new int[6];
    java.cluster(source, size, expected);
    other.cluster(source, size, actual);
    return describeMismatch(Arrays.equals(expected, actual), "cluster");
  }

  private static @Nullable String comparePalettes(
    final Values random,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    final int[] source = drawIntegers(random, channels, 0, 255);
    if (random.next(0, 1) == 0) {
      // every row the first, so a pattern holds
      for (int row = 1; row < size; row++) {
        System.arraycopy(source, 0, source, row * size * 3, size * 3);
      }
    }

    final int[] endpoints = new int[6];
    for (int index = 0; index < endpoints.length; index++) {
      endpoints[index] = random.next(0, 255);
    }

    final int[] expectedColors = new int[6];
    final int[] actualColors = new int[6];
    final byte[] expected = new byte[size * size];
    final byte[] actual = new byte[size * size];
    if (kernel == 7) {
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
    final int[] source = drawIntegers(random, channels, 0, 255);
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
    final int[] source = drawIntegers(random, channels, 0, 255);
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
        yield describeMismatch(Arrays.equals(expected, actual), "loadSource");
      }
      case 11 -> {
        final int[] expected = new int[channels / 4];
        final int[] actual = new int[channels / 4];
        java.halve(source, size, expected);
        other.halve(source, size, actual);
        yield describeMismatch(Arrays.equals(expected, actual), "halve");
      }
      default -> {
        final int pixels = random.next(0, count);
        final int[] prediction = drawIntegers(random, channels, 0, 255);
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
