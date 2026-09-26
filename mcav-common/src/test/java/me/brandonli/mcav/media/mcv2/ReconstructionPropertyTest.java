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
package me.brandonli.mcav.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * The integer kernels of {@link Reconstruction} against their floating-point oracle, the form the conformance streams
 * verified: for every leaf size, grid, compact class and every quantizer the format allows (0 to 7, beyond the 3 and 4
 * the encoder uses), and for records and predictions drawn with half of their values at the extremes of their range,
 * where a rounding float operation would show first, both must produce the same pixels.
 */
final class ReconstructionPropertyTest {

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
    for (int i = 0; i < length; i++) {
      record[i] = extremeByte(random);
    }
    return record;
  }

  /** Four times a predicted channel: a multiple of a quarter from 0 to 255, biased to the ends. */
  private static int[] prediction(final Random random, final int size) {
    final int[] prediction = new int[size * size * 3];
    for (int i = 0; i < prediction.length; i++) {
      prediction[i] = switch (random.nextInt(3)) {
        case 0 -> 0;
        case 1 -> 1020;
        default -> random.nextInt(1021);
      };
    }
    return prediction;
  }

  @Property(seed = SEED, tries = 400)
  void intraGridsMatchTheOracle(
    @ForAll @IntRange(min = 0, max = 2) final int sizeIndex,
    @ForAll @IntRange(min = 0, max = 3) final int gridIndex,
    @ForAll final long seed
  ) {
    final int size = SIZES[sizeIndex];
    final int grid = 1 << gridIndex;
    final byte[] record = record(new Random(seed), 3 * grid * grid);
    final int[] expected = new int[size * size * 3];
    final int[] actual = new int[size * size * 3];
    ReconstructionOracle.intraGrid(record, 0, grid, size, new float[3 * 64], expected);
    Reconstruction.intraGrid(record, 0, grid, size, new Reconstruction.Scratch(), actual);
    assertArrayEquals(expected, actual);
  }

  @Property(seed = SEED, tries = 800)
  void residualGridsMatchTheOracle(
    @ForAll @IntRange(min = 0, max = 2) final int sizeIndex,
    @ForAll @IntRange(min = 0, max = 3) final int gridIndex,
    @ForAll @IntRange(min = 0, max = 7) final int q,
    @ForAll final long seed
  ) {
    final int size = SIZES[sizeIndex];
    final int grid = 1 << gridIndex;
    final Random random = new Random(seed);
    final byte[] record = record(random, 2 + 3 * grid * grid);
    final int[] prediction = prediction(random, size);
    final int[] expected = new int[size * size * 3];
    final int[] actual = new int[size * size * 3];
    ReconstructionOracle.residualGrid(prediction, record, 2, grid, q, size, new float[3 * 64], expected);
    Reconstruction.residualGrid(prediction, record, 2, grid, q, size, new Reconstruction.Scratch(), actual);
    assertArrayEquals(expected, actual);
  }

  @Property(seed = SEED, tries = 800)
  void reducedRecordsMatchTheOracle(
    @ForAll @IntRange(min = 0, max = 2) final int sizeIndex,
    @ForAll final boolean fine,
    @ForAll final boolean residual,
    @ForAll @IntRange(min = 0, max = 7) final int q,
    @ForAll final long seed
  ) {
    final int size = SIZES[sizeIndex];
    final int luma = fine ? 8 : 4;
    final int chroma = fine ? 2 : 1;
    final Random random = new Random(seed);
    final byte[] record = record(random, 2 + luma * luma + 2 * chroma * chroma);
    final int[] prediction = residual ? prediction(random, size) : null;
    final int offset = residual ? 2 : 0;
    final int quantizer = residual ? q : 0;
    final int[] expected = new int[size * size * 3];
    final int[] actual = new int[size * size * 3];
    ReconstructionOracle.reduced(prediction, record, offset, luma, chroma, quantizer, size, new float[3 * 64], expected);
    Reconstruction.reduced(prediction, record, offset, luma, chroma, quantizer, size, new Reconstruction.Scratch(), actual);
    assertArrayEquals(expected, actual);
  }

  @Property(seed = SEED, tries = 1600)
  void compactRecordsMatchTheOracle(
    @ForAll @IntRange(min = 0, max = 2) final int sizeIndex,
    @ForAll @IntRange(min = 0, max = 8) final int kind,
    @ForAll @IntRange(min = 0, max = 7) final int q,
    @ForAll final long seed
  ) {
    final int size = SIZES[sizeIndex];
    final Random random = new Random(seed);
    final byte[] record = record(random, 18);
    if (kind == CompactRecord.VQ64) {
      // the parser refuses a vector index past the 64 of the book
      record[3] = (byte) random.nextInt(64);
    } else if (kind == CompactRecord.PQ64) {
      // and product indices with padding bits set: two six-bit indices in twelve bits
      record[4] = (byte) random.nextInt(16);
    }
    final int[] prediction = prediction(random, size);
    final int[] expected = new int[size * size * 3];
    final int[] actual = new int[size * size * 3];
    ReconstructionOracle.compact(prediction, record, 0, kind, q, size, new float[3 * 64], expected);
    Reconstruction.compact(prediction, record, 0, kind, q, size, new Reconstruction.Scratch(), actual);
    assertArrayEquals(expected, actual);
  }

  @Property(seed = SEED, tries = 200)
  void predictionsRoundLikeTheOracle(@ForAll @IntRange(min = 0, max = 2) final int sizeIndex, @ForAll final long seed) {
    final int size = SIZES[sizeIndex];
    final int[] prediction = prediction(new Random(seed), size);
    final int[] expected = new int[size * size * 3];
    final int[] actual = new int[size * size * 3];
    ReconstructionOracle.predicted(prediction, size, expected);
    Reconstruction.predicted(prediction, size, actual);
    assertArrayEquals(expected, actual);
  }

  /** One kernel call: reconstruct into out, measured by score when it is not null; returns whether it finished. */
  @FunctionalInterface
  private interface Kernel {
    boolean run(int[] out, Reconstruction.Score score);
  }

  /** The source a measure compares with, and the same measure's plain distortion of a reconstruction. */
  private static long distortion(final int[] source, final int[] out) {
    long sum = 0;
    for (int i = 0; i < source.length; i += 3) {
      final int dr = source[i] - out[i];
      final int dg = source[i + 1] - out[i + 1];
      final int db = source[i + 2] - out[i + 2];
      final int luma = dr + 2 * dg + db;
      final int co = dr - db;
      final int cg = 2 * dg - dr - db;
      sum += 4L * luma * luma + 4L * co * co + (long) cg * cg;
    }
    return sum;
  }

  /**
   * A measured kernel reconstructs exactly what the unmeasured one does, and its distortion is the whole block's; it
   * stops once its cost reaches the limit, and never before the whole block's cost does.
   */
  private static void assertMeasured(final Random random, final int size, final Kernel kernel) {
    final int[] source = new int[size * size * 3];
    for (int i = 0; i < source.length; i++) {
      source[i] = random.nextInt(256);
    }
    final double rate = random.nextInt(4000) / 7.0;
    final Reconstruction.Score score = new Reconstruction.Score();
    final int[] plain = new int[source.length];
    final int[] measured = new int[source.length];
    kernel.run(plain, null);
    score.start(source, rate, Double.POSITIVE_INFINITY);
    assertTrue(kernel.run(measured, score));
    assertArrayEquals(plain, measured);
    final long distortion = distortion(source, plain);
    assertEquals(distortion, score.distortion());
    final double cost = distortion / 96.0 + rate;
    score.start(source, rate, cost);
    assertFalse(kernel.run(new int[source.length], score));
    score.start(source, rate, Math.nextUp(cost));
    assertTrue(kernel.run(new int[source.length], score));
    assertEquals(distortion, score.distortion());
  }

  @Property(seed = SEED, tries = 300)
  void measuredKernelsStopOnlyWhenTheyCannotWin(
    @ForAll @IntRange(min = 0, max = 2) final int sizeIndex,
    @ForAll @IntRange(min = 0, max = 8) final int kernel,
    @ForAll @IntRange(min = 0, max = 3) final int q,
    @ForAll final long seed
  ) {
    final int size = SIZES[sizeIndex];
    final Random random = new Random(seed);
    final byte[] record = record(random, 2 + 3 * 64);
    final int[] prediction = prediction(random, size);
    final Reconstruction.Scratch scratch = new Reconstruction.Scratch();
    final int grid = 1 << (q & 3);
    final int compact = new int[] { 0, 1, 2, 3, 4, 7, 8, 5, 6 }[random.nextInt(9)];
    final int color = random.nextInt(1 << 24);
    if (compact == CompactRecord.VQ64) {
      record[3] = (byte) random.nextInt(64);
    } else if (compact == CompactRecord.PQ64) {
      record[4] = (byte) random.nextInt(16);
    }
    final Kernel run =
      switch (kernel) {
        case 0 -> (out, score) -> Reconstruction.predicted(prediction, size, out, score);
        case 1 -> (out, score) -> Reconstruction.solid(color, size, out, score);
        case 2 -> (out, score) -> Reconstruction.palette(record, 0, size, out, score);
        case 3 -> (out, score) -> Reconstruction.intraGrid(record, 0, grid, size, scratch, out, score);
        case 4 -> (out, score) -> Reconstruction.residualGrid(prediction, record, 2, grid, q, size, scratch, out, score);
        case 5 -> (out, score) -> Reconstruction.reduced(null, record, 0, 4, 1, 0, size, scratch, out, score);
        case 6 -> (out, score) -> Reconstruction.reduced(prediction, record, 2, 8, 2, q, size, scratch, out, score);
        default -> (out, score) ->
          Reconstruction.compact(prediction, record, 0, compact, compact == CompactRecord.GAIN_BIAS ? 0 : q, size, scratch, out, score);
      };
    assertMeasured(random, size, run);
  }
}
