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

import java.util.Arrays;
import java.util.Random;
import me.brandonli.mcav.media.mcv2.CompactRecord;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One kernel, run by two sets of kernels on the same inputs: the differential test of the native kernels against the
 * Java ones, which the property test drives from seeds and the fuzz test from fuzzed bytes. Every kernel gets inputs
 * in the ranges the encoder gives it - channels 0 to 255, four-times predictions 0 to 1020, any record bytes, blocks of
 * every size, grids of every width, limits that stop a candidate part way, pictures whose edges a block or a motion
 * vector crosses - and now and then values far outside a picture's, whose sums overflow an int in the clusterings and
 * that are no bytes in the motion search; every output, integer or floating-point, must be identical to the bit.
 */
final class KernelDifferential {

  /** The kernels one comparison chooses from. */
  static final int KERNELS = 21;

  private static final int[] COMPACT_CLASSES = {
    CompactRecord.DC_Y,
    CompactRecord.GRID2_YC,
    CompactRecord.GRID4_N4_YC,
    CompactRecord.GRID4_N4_Y,
    CompactRecord.GRID4_YC,
    CompactRecord.LOW2,
    CompactRecord.GAIN_BIAS,
  };

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

  private static int[] ints(final Values v, final int count, final int low, final int high) {
    final int[] values = new int[count];
    for (int i = 0; i < count; i++) {
      values[i] = v.next(low, high);
    }
    return values;
  }

  /** Floats as the encoder's fits see them, now and then an extreme one. */
  private static float[] floats(final Values v, final int count) {
    final float[] values = new float[count];
    for (int i = 0; i < count; i++) {
      values[i] = switch (v.next(0, 15)) {
        case 0 -> -0.0f;
        case 1 -> Float.MIN_VALUE * v.next(1, 1000);
        case 2 -> 1e30f * v.next(-3, 3);
        default -> (v.next(-1_000_000, 1_000_000) / 3.0f) / 1000;
      };
    }
    return values;
  }

  /** Nothing when the outputs are equal, else what differed. */
  private static @Nullable String unless(final boolean equal, final String what) {
    return equal ? null : what;
  }

  private static boolean same(final float[] a, final float[] b) {
    for (int i = 0; i < a.length; i++) {
      if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) {
        return false;
      }
    }
    return true;
  }

  private static int size(final Values v) {
    return 8 << v.next(0, 2);
  }

  /**
   * Runs one kernel, chosen by the values, on inputs from them with both kernels.
   *
   * @param v     the values
   * @param java  the kernels the others are compared with
   * @param other the kernels compared
   * @return the name of what differed, or null when nothing did
   */
  static @Nullable String compare(final Values v, final Kernels java, final Kernels other) {
    return compare(v, java, other, v.next(0, KERNELS - 1));
  }

  /**
   * Runs one kernel on inputs from the values with both kernels.
   *
   * @param v      the values
   * @param java   the kernels the others are compared with
   * @param other  the kernels compared
   * @param kernel the kernel, 0 to {@link #KERNELS} - 1
   * @return the name of what differed, or null when nothing did
   */
  static @Nullable String compare(final Values v, final Kernels java, final Kernels other, final int kernel) {
    final int size = size(v);
    final int channels = size * size * 3;
    return kernel < 7 ? scored(v, java, other, kernel, size, channels) : unscored(v, java, other, kernel, size, channels);
  }

  /** A scored reconstruction: whether it finished, its distortion and every value it wrote must agree. */
  private static @Nullable String scored(
    final Values v,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    final int[] source = ints(v, channels, 0, 255);
    final int[] prediction = ints(v, channels, 0, 1020);
    final double rate = v.next(0, 100_000) / 7.0;
    // an infinite limit, or one some candidates cross part way through the block
    final double limit = v.next(0, 3) == 0 ? Double.POSITIVE_INFINITY : rate + v.next(0, size * size * 3000);
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
        final int color = v.next(0, 0xFFFFFF);
        finishedJava = java.solid(color, size, expected);
        finishedOther = other.solid(color, size, actual);
      }
      case 2 -> {
        name = "palette";
        final int offset = v.next(0, 3);
        final byte[] record = v.bytes(offset + 6 + (size * size) / 8 + v.next(0, 2));
        finishedJava = java.palette(record, offset, size, expected);
        finishedOther = other.palette(record, offset, size, actual);
      }
      case 3 -> {
        name = "intraGrid";
        final int grid = 1 << v.next(0, 3);
        final int offset = v.next(0, 2);
        final byte[] record = v.bytes(offset + 3 * grid * grid);
        finishedJava = java.intraGrid(record, offset, grid, size, expected);
        finishedOther = other.intraGrid(record, offset, grid, size, actual);
      }
      case 4 -> {
        name = "residualGrid";
        final int grid = 1 << v.next(0, 3);
        final int q = v.next(0, 4);
        final int offset = v.next(0, 2);
        final byte[] record = v.bytes(offset + 3 * grid * grid);
        finishedJava = java.residualGrid(prediction, record, offset, grid, q, size, expected);
        finishedOther = other.residualGrid(prediction, record, offset, grid, q, size, actual);
      }
      case 5 -> {
        name = "reduced";
        final int luma = 1 << v.next(0, 3);
        final int chroma = 1 << v.next(0, 2);
        final int q = v.next(0, 4);
        final int offset = v.next(0, 2);
        final byte[] record = v.bytes(offset + luma * luma + 2 * chroma * chroma);
        final int@Nullable[] predicted = v.next(0, 1) == 0 ? null : prediction;
        finishedJava = java.reduced(predicted, record, offset, luma, chroma, q, size, expected);
        finishedOther = other.reduced(predicted, record, offset, luma, chroma, q, size, actual);
      }
      default -> {
        final int kind = COMPACT_CLASSES[v.next(0, COMPACT_CLASSES.length - 1)];
        name = "compact " + kind;
        final int q = v.next(0, 4);
        final int body = v.next(0, 2);
        final byte[] record = v.bytes(body + CompactRecord.bodyBytes(kind));
        finishedJava = java.compact(prediction, record, body, kind, q, size, expected);
        finishedOther = other.compact(prediction, record, body, kind, q, size, actual);
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
    final Values v,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    return switch (kernel) {
      case 7 -> predict(v, java, other, size, channels);
      case 8 -> fit(v, java, other, size);
      case 9, 10, 11, 12 -> blockFits(v, java, other, kernel, size, channels);
      case 13, 14 -> palettes(v, java, other, kernel, size, channels);
      case 15 -> seeded(v, java, other, size, channels);
      default -> helpers(v, java, other, kernel, size, channels);
    };
  }

  private static @Nullable String predict(final Values v, final Kernels java, final Kernels other, final int size, final int channels) {
    final int width = v.next(1, 80);
    final int height = v.next(1, 60);
    final byte[] reference = v.bytes(width * height * 3);
    final int x = v.next(-10, width + 10);
    final int y = v.next(-10, height + 10);
    final int mx = v.next(-40, 40);
    final int my = v.next(-40, 40);
    final int[] expected = new int[channels];
    final int[] actual = new int[channels];
    java.predict(reference, width, height, x, y, size, mx, my, expected);
    other.predict(reference, width, height, x, y, size, mx, my, actual);
    return unless(Arrays.equals(expected, actual), "predict");
  }

  private static @Nullable String fit(final Values v, final Kernels java, final Kernels other, final int size) {
    final int grid = 1 << v.next(0, 3);
    final int stride = v.next(0, 1) == 0 ? 1 : 3;
    final int offset = stride == 3 ? v.next(0, 2) : 0;
    final float[] values = floats(v, size * size * stride + offset);
    final int outOffset = v.next(0, 2);
    final int outStride = v.next(1, 2);
    final float[] expected = new float[outOffset + grid * grid * outStride];
    final float[] actual = new float[expected.length];
    java.fit(values, offset, stride, size, grid, expected, outOffset, outStride);
    other.fit(values, offset, stride, size, grid, actual, outOffset, outStride);
    return unless(same(expected, actual), "fit");
  }

  private static @Nullable String blockFits(
    final Values v,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    // sometimes nearly flat, so the clusters meet ties and empty sides; the clusterings now and then on values far
    // outside a picture's, whose sums overflow an int
    final int low = v.next(0, 255);
    final int[] source = kernel >= 11 && v.next(0, 7) == 0
      ? ints(v, channels, EXTREME_LOW, EXTREME_HIGH)
      : ints(v, channels, low, Math.min(255, low + (v.next(0, 1) == 0 ? 8 : 255)));
    return switch (kernel) {
      case 9 -> {
        final int[] expected = new int[FastFits.CELL_SUMS];
        final int[] actual = new int[FastFits.CELL_SUMS];
        java.cellSums(source, size, expected);
        other.cellSums(source, size, actual);
        yield unless(Arrays.equals(expected, actual), "cellSums");
      }
      case 10 -> {
        final int[] prediction = ints(v, channels, 0, 1020);
        final float[] expected = new float[16];
        final float[] actual = new float[16];
        java.lumaResidual(source, prediction, size, expected);
        other.lumaResidual(source, prediction, size, actual);
        yield unless(same(expected, actual), "lumaResidual");
      }
      case 11 -> {
        final float[] expected = new float[6];
        final float[] actual = new float[6];
        java.cluster(source, size, expected);
        other.cluster(source, size, actual);
        yield unless(same(expected, actual), "cluster");
      }
      default -> {
        final float[] expected = new float[6];
        final float[] actual = new float[6];
        java.paletteCluster(source, size * size, expected);
        other.paletteCluster(source, size * size, actual);
        yield unless(same(expected, actual), "paletteCluster");
      }
    };
  }

  private static @Nullable String palettes(
    final Values v,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    final int[] source = ints(v, channels, 0, 255);
    if (v.next(0, 1) == 0) {
      // every row the first, so a pattern holds
      for (int y = 1; y < size; y++) {
        System.arraycopy(source, 0, source, y * size * 3, size * 3);
      }
    }
    final float[] endpoints = new float[6];
    for (int i = 0; i < endpoints.length; i++) {
      endpoints[i] = v.next(-2000, 257_000) / 1000.0f;
    }
    final boolean quantize = v.next(0, 1) == 0;
    final int[] expectedColors = new int[6];
    final int[] actualColors = new int[6];
    final byte[] expected = new byte[size * size];
    final byte[] actual = new byte[size * size];
    if (kernel == 13) {
      java.finish(source, size * size, endpoints, quantize, expectedColors, expected);
      other.finish(source, size * size, endpoints, quantize, actualColors, actual);
      return unless(Arrays.equals(expectedColors, actualColors) && Arrays.equals(expected, actual), "finish");
    }
    final boolean held = java.finishPattern(source, size, endpoints, quantize, expectedColors, expected);
    final boolean holds = other.finishPattern(source, size, endpoints, quantize, actualColors, actual);
    return unless(held == holds && Arrays.equals(expectedColors, actualColors) && Arrays.equals(expected, actual), "finishPattern");
  }

  private static @Nullable String seeded(final Values v, final Kernels java, final Kernels other, final int size, final int channels) {
    final int width = v.next(1, 120);
    final int height = v.next(1, 90);
    final byte[] reference = v.bytes(width * height * 3);
    final int x = v.next(0, width - 1);
    final int y = v.next(0, height - 1);
    final int[] source = ints(v, channels, 0, 255);
    if (v.next(0, 1) == 0) {
      // the block itself, so the search has a place to go
      java.loadSource(reference, width, height, x, y, size, source);
    }
    if (v.next(0, 7) == 0) {
      // a channel that is no byte, which the search must cost as an int
      source[v.next(0, channels - 1)] = v.next(EXTREME_LOW, EXTREME_HIGH);
    }
    final int[] seeds = new int[v.next(0, 6)];
    for (int k = 0; k < seeds.length; k++) {
      seeds[k] = MotionSearch.pack(v.next(-40, 40), v.next(-40, 40));
    }
    final int globalX = v.next(-10, 10);
    final int globalY = v.next(-10, 10);
    final int range = v.next(0, 24);
    final boolean halfPixel = v.next(0, 1) == 0;
    final int expected = java.seeded(reference, width, height, source, x, y, size, globalX, globalY, range, halfPixel, seeds);
    final int actual = other.seeded(reference, width, height, source, x, y, size, globalX, globalY, range, halfPixel, seeds);
    return unless(expected == actual, "seeded");
  }

  private static @Nullable String helpers(
    final Values v,
    final Kernels java,
    final Kernels other,
    final int kernel,
    final int size,
    final int channels
  ) {
    final int count = size * size;
    final int[] source = ints(v, channels, 0, 255);
    final boolean chroma = v.next(0, 1) == 0;
    return switch (kernel) {
      case 16 -> {
        final int width = v.next(1, 70);
        final int height = v.next(1, 70);
        final byte[] image = v.bytes(width * height * 3);
        final int x = v.next(0, width - 1);
        final int y = v.next(0, height - 1);
        final int[] expected = new int[channels];
        final int[] actual = new int[channels];
        java.loadSource(image, width, height, x, y, size, expected);
        other.loadSource(image, width, height, x, y, size, actual);
        yield unless(Arrays.equals(expected, actual), "loadSource");
      }
      case 17 -> {
        final int[] expected = new int[channels / 4];
        final int[] actual = new int[channels / 4];
        java.halve(source, size, expected);
        other.halve(source, size, actual);
        yield unless(Arrays.equals(expected, actual), "halve");
      }
      case 18 -> {
        // the chroma a luma-only conversion leaves alone must stay as it was
        final float[] expected = floats(v, channels);
        final float[] actual = expected.clone();
        java.ycocg(source, count, chroma, expected);
        other.ycocg(source, count, chroma, actual);
        yield unless(same(expected, actual), "ycocg");
      }
      case 19 -> {
        final float[] ycocg = floats(v, channels);
        final int[] prediction = ints(v, channels, 0, 1020);
        final float[] expected = floats(v, channels);
        final float[] actual = expected.clone();
        java.residualTarget(ycocg, prediction, count, chroma, expected);
        other.residualTarget(ycocg, prediction, count, chroma, actual);
        yield unless(same(expected, actual), "residualTarget");
      }
      default -> {
        final float[] target = floats(v, channels);
        final int grid = 1 << v.next(0, 3);
        final int channel = v.next(0, 2);
        final float[] expected = new float[1 + grid * grid * 2];
        final float[] actual = new float[expected.length];
        java.cellMeans(target, size, channel, grid, expected, 1, 2);
        other.cellMeans(target, size, channel, grid, actual, 1, 2);
        yield unless(same(expected, actual), "cellMeans");
      }
    };
  }
}
