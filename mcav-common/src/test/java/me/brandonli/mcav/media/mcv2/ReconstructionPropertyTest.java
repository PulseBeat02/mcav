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
}
