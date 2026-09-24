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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.OrderedDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.OrderedDitherBuilderImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.OrderedDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.PixelMapper;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.ThresholdMatrix;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link OrderedDither}, {@link BayerDither}, {@link PixelMapper} and the ordered dither builder.
 */
final class OrderedDitherTest {

  /**
   * Asserts that a threshold matrix has the size its name promises, and that every one of its levels occupies the
   * same number of cells, so no threshold is missing or repeated by mistake.
   */
  private static void assertEvenRanking(
    final String name,
    final ThresholdMatrix thresholds,
    final int rows,
    final int columns,
    final int levels
  ) {
    final int[][] matrix = thresholds.toArray();
    assertEquals(rows, matrix.length, name + " rows");
    int minimum = Integer.MAX_VALUE;
    final Map<Integer, Integer> counts = new HashMap<>();
    for (final int[] row : matrix) {
      assertEquals(columns, row.length, name + " columns");
      for (final int value : row) {
        minimum = Math.min(minimum, value);
        counts.merge(value, 1, Integer::sum);
      }
    }

    final int repetitions = (rows * columns) / levels;
    final int distinct = counts.size();
    assertEquals(levels, distinct, name + " levels");
    for (int level = 0; level < levels; level++) {
      final int value = minimum + level;
      final int count = counts.getOrDefault(value, 0);
      assertEquals(repetitions, count, name + " occurrences of " + value);
    }
  }

  private static OrderedDither blackWhite4x4() {
    final PixelMapper mapper = PixelMapper.ofPixelMapper(BayerDither.NORMAL_4X4, BayerDither.NORMAL_4X4_MAX, PixelMapper.NORMAL_STRENGTH);
    final OrderedDitherBuilder<BayerDither, OrderedDitherBuilderImpl> builder = DitherAlgorithm.ordered();
    final OrderedDitherBuilderImpl blackWhite = builder.withPalette(DitherTestImages.BLACK_WHITE);
    final OrderedDitherBuilderImpl configured = blackWhite.withDitherMatrix(mapper);
    return configured.build();
  }

  private static byte[] ditherMidGray(final BayerDither dither, final int width, final int height) {
    final int[] gray = new int[width * height];
    Arrays.fill(gray, 0xFF808080);
    try (final ImageBuffer grayImage = ImageBuffer.buffer(gray, width, height)) {
      return dither.ditherIntoBytes(grayImage);
    }
  }

  @Test
  void turnsMidGrayIntoAPatternOfHalfBlackHalfWhite() {
    final OrderedDither dither = blackWhite4x4();
    final byte[] indices = ditherMidGray(dither, 64, 64);
    final double ratio = DitherTestImages.whiteRatio(indices);
    assertEquals(0.5, ratio, "every 4x4 tile of mid-gray is half black and half white");
  }

  @Test
  void parallelDitheringMatchesSerialDithering() {
    final int[] tall = DitherTestImages.gradient(64, 80);
    final int[] flat = DitherTestImages.gradient(64, 4);
    final OrderedDither dither = blackWhite4x4();
    final ForkJoinPool pool = new ForkJoinPool(3);
    try (final ImageBuffer tallImage = ImageBuffer.buffer(tall, 64, 80); final ImageBuffer shortImage = ImageBuffer.buffer(flat, 64, 4)) {
      final byte[] tallSerial = dither.ditherIntoBytes(tallImage);
      final byte[] tallParallel = dither.ditherIntoBytes(tallImage, pool);
      final byte[] shortSerial = dither.ditherIntoBytes(shortImage);
      final byte[] shortParallel = dither.ditherIntoBytes(shortImage, pool);
      assertArrayEquals(tallSerial, tallParallel);
      assertArrayEquals(shortSerial, shortParallel);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void ditherInPlaceMatchesTheIndices() {
    final int[] pixels = DitherTestImages.gradient(32, 8);
    final OrderedDither dither = blackWhite4x4();
    final byte[] indices;
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 32, 8)) {
      indices = dither.ditherIntoBytes(image);
    }

    final int[] inPlace = pixels.clone();
    dither.dither(inPlace, 32);
    final int[] colors = DitherTestImages.BLACK_WHITE.getPalette();
    for (int index = 0; index < inPlace.length; index++) {
      assertEquals(colors[indices[index]], inPlace[index]);
    }
  }

  @Test
  void defaultBuilderUsesTheMapPalette() {
    final OrderedDitherBuilder<BayerDither, OrderedDitherBuilderImpl> builder = DitherAlgorithm.ordered();
    final BayerDither dither = builder.build();
    final DitherPalette palette = dither.getPalette();
    assertSame(DitherPalette.DEFAULT_MAP_PALETTE, palette);
  }

  @Test
  void defaultBuilderUsesA2x2Matrix() {
    final OrderedDitherBuilder<BayerDither, OrderedDitherBuilderImpl> builder = DitherAlgorithm.ordered();
    final OrderedDitherBuilderImpl blackWhite = builder.withPalette(DitherTestImages.BLACK_WHITE);
    final BayerDither dither = blackWhite.build();
    final byte[] indices = ditherMidGray(dither, 8, 8);
    for (int y = 0; y < 6; y++) {
      for (int x = 0; x < 6; x++) {
        final int index = indices[y * 8 + x];
        final int right = indices[y * 8 + x + 2];
        final int below = indices[(y + 2) * 8 + x];
        assertEquals(index, right, "the default pattern repeats every two columns");
        assertEquals(index, below, "and every two rows");
      }
    }
  }

  @Test
  void builderMethodsReturnTheBuilderForChaining() {
    final OrderedDitherBuilder<BayerDither, OrderedDitherBuilderImpl> builder = DitherAlgorithm.ordered();
    final PixelMapper mapper = PixelMapper.ofPixelMapper(BayerDither.NORMAL_8X8, PixelMapper.MIN_STRENGTH);
    final OrderedDitherBuilderImpl withPalette = builder.withPalette(DitherTestImages.BLACK_WHITE);
    final OrderedDitherBuilderImpl withMatrix = builder.withDitherMatrix(mapper);
    assertSame(builder, withPalette);
    assertSame(builder, withMatrix);
  }

  @Test
  void builderRejectsMissingArguments() {
    final OrderedDitherBuilder<BayerDither, OrderedDitherBuilderImpl> builder = DitherAlgorithm.ordered();
    assertThrows(NullPointerException.class, () -> builder.withPalette(null));
    assertThrows(NullPointerException.class, () -> builder.withDitherMatrix(null));
    assertThrows(NullPointerException.class, () -> new OrderedDither(DitherTestImages.BLACK_WHITE, null));
  }

  @Test
  void rejectsInvalidInput() {
    final OrderedDither dither = blackWhite4x4();
    assertThrows(NullPointerException.class, () -> dither.ditherIntoBytes(null));
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      assertThrows(NullPointerException.class, () -> dither.ditherIntoBytes(null, pool));
    }
    try (final ImageBuffer image = ImageBuffer.buffer(new int[1], 1, 1)) {
      assertThrows(NullPointerException.class, () -> dither.ditherIntoBytes(image, null));
    }
    assertThrows(IllegalArgumentException.class, () -> dither.dither(new int[3], 2));
  }

  @Test
  void normalizesThresholdMatricesAroundZero() {
    final PixelMapper mapper = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, BayerDither.NORMAL_2X2_MAX, 1.0f);
    final PixelMapper doubled = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, 2.0f);
    final float[][] matrix = mapper.getMatrix();
    final float[][] doubledMatrix = doubled.getMatrix();
    final float strength = doubled.getStrength();
    assertArrayEquals(new float[] { -0.375f, 0.125f }, matrix[0]);
    assertArrayEquals(new float[] { 0.375f, -0.125f }, matrix[1]);
    assertArrayEquals(new float[] { -0.75f, 0.25f }, doubledMatrix[0]);
    assertEquals(2.0f, strength);
  }

  @Test
  void normalizesThresholdsAcrossTheEntireIntegerRange() {
    final int[][] thresholds = { { Integer.MIN_VALUE, 0, Integer.MAX_VALUE } };
    final PixelMapper mapper = PixelMapper.ofPixelMapper(thresholds, 1.0f);
    final float[][] matrix = mapper.getMatrix();
    // There are 2^32 ranks. The middle rank is 2^31, so its centered offset is exactly 2^-33.
    assertArrayEquals(new float[] { -0.5f, 0x1.0p-33f, 0.5f }, matrix[0]);
  }

  @Test
  void rejectsNonFiniteStrengths() {
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, Float.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, Float.NEGATIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, Float.NaN));
  }

  @Test
  void pixelMapperAcceptsAStrengthOfZero() {
    final PixelMapper flat = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, 0.0f);
    final float[][] matrix = flat.getMatrix();
    final float strength = flat.getStrength();
    assertEquals(0.0f, strength, "a strength of 0 is allowed");
    // every threshold is (value / max - 0.5) * strength, so the entries below the middle of the pattern come out as
    // negative zero; the tolerance compares the numbers rather than the sign of a zero
    final float exactly = 0.0f;
    assertArrayEquals(new float[] { 0.0f, 0.0f }, matrix[0], exactly, "and flattens every threshold of the pattern");
  }

  @Test
  void pixelMapperRejectsInvalidMatrices() {
    assertThrows(NullPointerException.class, () -> PixelMapper.ofPixelMapper((int[][]) null, 1, 1.0f));
    assertThrows(NullPointerException.class, () -> PixelMapper.ofPixelMapper((int[][]) null, 1.0f));
    assertThrows(NullPointerException.class, () -> PixelMapper.ofPixelMapper((ThresholdMatrix) null, 1, 1.0f));
    assertThrows(NullPointerException.class, () -> PixelMapper.ofPixelMapper((ThresholdMatrix) null, 1.0f));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, 0, 1.0f));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(new int[0][], 1.0f));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(new int[][] { {} }, 1.0f));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(new int[][] { { 1, 2 }, { 3 } }, 1.0f));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, -1.0f));
    assertThrows(NullPointerException.class, () -> PixelMapper.ofPixelMapper(new int[][] { null }, 1.0f));
    assertThrows(NullPointerException.class, () -> PixelMapper.ofPixelMapper(new int[][] { { 1 }, null }, 1.0f));
  }

  @Test
  void generatesBayerMatricesOfAnyPowerOfTwo() {
    final int[][] one = BayerDither.createBayerMatrix(1);
    final int[][] four = BayerDither.createBayerMatrix(4);
    final int[][] eight = BayerDither.createBayerMatrix(8);
    final boolean[] seen = new boolean[64];
    for (final int[] row : eight) {
      for (final int value : row) {
        seen[value] = true;
      }
    }

    assertArrayEquals(new int[][] { { 0 } }, one);
    assertArrayEquals(new int[] { 0, 8, 2, 10 }, four[0]);
    for (final boolean value : seen) {
      assertTrue(value, "every threshold appears exactly once");
    }
    assertThrows(IllegalArgumentException.class, () -> BayerDither.createBayerMatrix(0));
    assertThrows(IllegalArgumentException.class, () -> BayerDither.createBayerMatrix(3));
  }

  @Test
  void orderedSpreadDoesNotOverBrightenADarkGray() {
    final PixelMapper mapper = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, 1.0f);
    final OrderedDither dither = new OrderedDither(DitherTestImages.BLACK_WHITE, mapper);
    final int[] pixels = { 0xFF5A5A5A, 0xFF5A5A5A, 0xFF5A5A5A, 0xFF5A5A5A };
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 2, 2)) {
      final byte[] actual = dither.ditherIntoBytes(image);
      // Gray90 plus [-76,25,76,-25] gives [14,115,166,65]. Only the third is white.
      assertArrayEquals(new byte[] { 0, 0, 1, 0 }, actual);
    }
  }

  @Test
  void addsTheCenteredPatternScaledToThePaletteSpacing() {
    final PixelMapper mapper = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, PixelMapper.NORMAL_STRENGTH);
    final OrderedDither dither = new OrderedDither(DitherTestImages.BLACK_WHITE, mapper);
    final int[] pixels = new int[4];
    Arrays.fill(pixels, 0xFF6E6E6E);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 2, 2)) {
      final byte[] indices = dither.ditherIntoBytes(image);
      // two colors are 256 / cbrt(2) = 203 apart, so the offsets of the 2x2 matrix are -76, 25, 76 and -25, which
      // turn a gray of 110 into 34, 135, 186 and 85
      assertArrayEquals(new byte[] { 0, 1, 1, 0 }, indices);
    }
  }

  @Test
  void bayerMatricesHaveTheSizeOfTheirNamesAndRankTheirCellsEvenly() {
    assertEvenRanking("NORMAL_2X2", BayerDither.NORMAL_2X2, 2, 2, BayerDither.NORMAL_2X2_MAX);
    assertEvenRanking("NORMAL_4X4", BayerDither.NORMAL_4X4, 4, 4, BayerDither.NORMAL_4X4_MAX);
    assertEvenRanking("NORMAL_8X8", BayerDither.NORMAL_8X8, 8, 8, BayerDither.NORMAL_8X8_MAX);
  }

  @Test
  void theBayerConstantsAreExactlyTheMatricesOfTheRecursion() {
    // an even ranking is not enough to identify a Bayer matrix: a transpose, a rotation or any permutation of the
    // cells ranks just as evenly. The published matrix is the one the recursion M2n = [[4M, 4M+2], [4M+3, 4M+1]]
    // builds, so the constants are pinned to this class's own generator, one-based to match their MAX values.
    final ThresholdMatrix generatedTwo = oneBasedBayerMatrix(2);
    final ThresholdMatrix generatedFour = oneBasedBayerMatrix(4);
    final ThresholdMatrix generatedEight = oneBasedBayerMatrix(8);

    assertArrayEquals(generatedTwo.toArray(), BayerDither.NORMAL_2X2.toArray(), "NORMAL_2X2");
    assertArrayEquals(generatedFour.toArray(), BayerDither.NORMAL_4X4.toArray(), "NORMAL_4X4");
    assertArrayEquals(generatedEight.toArray(), BayerDither.NORMAL_8X8.toArray(), "NORMAL_8X8");
  }

  @Test
  void theEightByEightMatrixIsTheOneOfTheLiterature() {
    // the canonical 8x8 Bayer matrix, one-based; see https://en.wikipedia.org/wiki/Ordered_dithering
    final int[][] published = {
      { 1, 33, 9, 41, 3, 35, 11, 43 },
      { 49, 17, 57, 25, 51, 19, 59, 27 },
      { 13, 45, 5, 37, 15, 47, 7, 39 },
      { 61, 29, 53, 21, 63, 31, 55, 23 },
      { 4, 36, 12, 44, 2, 34, 10, 42 },
      { 52, 20, 60, 28, 50, 18, 58, 26 },
      { 16, 48, 8, 40, 14, 46, 6, 38 },
      { 64, 32, 56, 24, 62, 30, 54, 22 },
    };
    final int[][] actual = BayerDither.NORMAL_8X8.toArray();
    assertArrayEquals(published, actual);
  }

  private static ThresholdMatrix oneBasedBayerMatrix(final int size) {
    final int[][] generated = BayerDither.createBayerMatrix(size);
    for (final int[] row : generated) {
      for (int column = 0; column < row.length; column++) {
        row[column] = row[column] + 1;
      }
    }
    return ThresholdMatrix.of(generated);
  }

  @Test
  void clusteredDotMatricesHaveTheSizeOfTheirNamesAndRankTheirCellsEvenly() {
    assertEvenRanking("CLUSTERED_DOT_4X4", BayerDither.CLUSTERED_DOT_4X4, 4, 4, BayerDither.CLUSTERED_DOT_4X4_MAX);
    assertEvenRanking("CLUSTERED_DOT_6X6", BayerDither.CLUSTERED_DOT_6X6, 6, 6, BayerDither.CLUSTERED_DOT_6X6_MAX);
    assertEvenRanking("CLUSTERED_DOT_6X6_2", BayerDither.CLUSTERED_DOT_6X6_2, 6, 6, BayerDither.CLUSTERED_DOT_6X6_2_MAX);
    assertEvenRanking("CLUSTERED_DOT_6X6_3", BayerDither.CLUSTERED_DOT_6X6_3, 6, 6, BayerDither.CLUSTERED_DOT_6X6_3_MAX);
    assertEvenRanking("CLUSTERED_DOT_SPIRAL_5X5", BayerDither.CLUSTERED_DOT_SPIRAL_5X5, 5, 5, BayerDither.CLUSTERED_DOT_SPIRAL_5X5_MAX);
    assertEvenRanking("CLUSTERED_DOT_8X8", BayerDither.CLUSTERED_DOT_8X8, 8, 8, BayerDither.CLUSTERED_DOT_8X8_MAX);
  }

  @Test
  void lineMatricesHaveTheSizeOfTheirNamesAndRankTheirCellsEvenly() {
    assertEvenRanking("VERTICAL_5X3", BayerDither.VERTICAL_5X3, 3, 5, BayerDither.VERTICAL_5X3_MAX);
    assertEvenRanking("HORIZONTAL_3X5", BayerDither.HORIZONTAL_3X5, 5, 3, BayerDither.HORIZONTAL_3X5_MAX);
    assertEvenRanking(
      "CLUSTERED_DOT_HORIZONTAL_LINE",
      BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE,
      6,
      6,
      BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE_MAX
    );
    assertEvenRanking(
      "CLUSTERED_DOT_VERTICAL_LINE",
      BayerDither.CLUSTERED_DOT_VERTICAL_LINE,
      6,
      6,
      BayerDither.CLUSTERED_DOT_VERTICAL_LINE_MAX
    );
  }

  @Test
  void diagonalClusteredDotMatricesHaveTheSizeOfTheirNamesAndRankTheirCellsEvenly() {
    assertEvenRanking(
      "CLUSTERED_DOT_DIAGONAL_6X6",
      BayerDither.CLUSTERED_DOT_DIAGONAL_6X6,
      6,
      6,
      BayerDither.CLUSTERED_DOT_DIAGONAL_6X6_MAX
    );
    assertEvenRanking(
      "CLUSTERED_DOT_DIAGONAL_8X8",
      BayerDither.CLUSTERED_DOT_DIAGONAL_8X8,
      8,
      8,
      BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_MAX
    );
    assertEvenRanking(
      "CLUSTERED_DOT_DIAGONAL_8X8_2",
      BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2,
      8,
      8,
      BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2_MAX
    );
  }

  @Test
  void largerDiagonalClusteredDotMatricesHaveTheSizeOfTheirNamesAndRankTheirCellsEvenly() {
    assertEvenRanking(
      "CLUSTERED_DOT_DIAGONAL_8X8_3",
      BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3,
      8,
      8,
      BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3_MAX
    );
    assertEvenRanking(
      "CLUSTERED_DOT_DIAGONAL_16X16",
      BayerDither.CLUSTERED_DOT_DIAGONAL_16X16,
      16,
      16,
      BayerDither.CLUSTERED_DOT_DIAGONAL_16X16_MAX
    );
  }
}
