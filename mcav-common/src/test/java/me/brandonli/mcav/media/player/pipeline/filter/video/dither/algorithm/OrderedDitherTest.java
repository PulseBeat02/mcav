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
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.OrderedDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.PixelMapper;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.ThresholdMatrix;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.junit.jupiter.api.Test;

final class OrderedDitherTest {

  private static final int MID_GRAY = 0xFF808080;

  private static final int GRADIENT_WIDTH = 64;
  private static final int TALL_GRADIENT_HEIGHT = 80;

  private static final int SMALL_IMAGE_WIDTH = 2;
  private static final int SMALL_IMAGE_HEIGHT = 2;

  private static final float UNIT_STRENGTH = 1.0f;

  // Asserts that a threshold matrix has the size its name promises, and that every one of its levels occupies the same
  // number of cells, so no threshold is missing or repeated by mistake.
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
    final PixelMapper mapper = PixelMapper.ofPixelMapper(
      BayerDither.NORMAL_4X4,
      BayerDither.NORMAL_4X4_MAXIMUM,
      PixelMapper.NORMAL_STRENGTH
    );
    final OrderedDitherBuilder builder = DitherAlgorithm.ordered();
    final OrderedDitherBuilder blackWhite = builder.withPalette(DitherTestImages.BLACK_WHITE);
    final OrderedDitherBuilder configured = blackWhite.withDitherMatrix(mapper);
    return configured.build();
  }

  private static byte[] ditherMidGray(final BayerDither dither, final int width, final int height) {
    final int[] gray = new int[width * height];
    Arrays.fill(gray, MID_GRAY);
    try (final ImageBuffer grayImage = ImageBuffer.buffer(gray, width, height)) {
      return dither.ditherIntoBytes(grayImage);
    }
  }

  @Test
  void shouldTurnMidGrayIntoAPatternOfHalfBlackHalfWhite() {
    final OrderedDither dither = blackWhite4x4();
    final byte[] indices = ditherMidGray(dither, 64, 64);
    final double ratio = DitherTestImages.whiteRatio(indices);
    assertEquals(0.5, ratio, "every 4x4 tile of mid-gray is half black and half white");
  }

  @Test
  void shouldMatchSerialDitheringWhenDitheringInParallel() {
    final int[] tall = DitherTestImages.gradient(GRADIENT_WIDTH, TALL_GRADIENT_HEIGHT);
    final int[] flat = DitherTestImages.gradient(GRADIENT_WIDTH, 4);
    final OrderedDither dither = blackWhite4x4();
    final ForkJoinPool pool = new ForkJoinPool(3);
    try (
      final ImageBuffer tallImage = ImageBuffer.buffer(tall, GRADIENT_WIDTH, TALL_GRADIENT_HEIGHT);
      final ImageBuffer shortImage = ImageBuffer.buffer(flat, GRADIENT_WIDTH, 4)
    ) {
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
  void shouldMatchSerialDitheringWhenTheRowsDoNotSplitEvenlyAcrossTasks() {
    final int height = TALL_GRADIENT_HEIGHT + 1;
    final int[] pixels = DitherTestImages.gradient(GRADIENT_WIDTH, height);
    final OrderedDither dither = blackWhite4x4();
    try (final ForkJoinPool pool = new ForkJoinPool(3); final ImageBuffer image = ImageBuffer.buffer(pixels, GRADIENT_WIDTH, height)) {
      final byte[] serial = dither.ditherIntoBytes(image);
      final byte[] parallel = dither.ditherIntoBytes(image, pool);
      assertArrayEquals(serial, parallel);
    }
  }

  @Test
  void shouldMatchSerialDitheringWhenThePixelCountTimesTheWidthOverflowsAnInt() {
    final int width = 2048;
    final int height = 512;
    final int[] pixels = DitherTestImages.gradient(width, height);
    final OrderedDither dither = blackWhite4x4();
    try (final ForkJoinPool pool = new ForkJoinPool(3); final ImageBuffer image = ImageBuffer.buffer(pixels, width, height)) {
      final byte[] serial = dither.ditherIntoBytes(image);
      final byte[] parallel = dither.ditherIntoBytes(image, pool);
      assertArrayEquals(serial, parallel);
    }
  }

  @Test
  void shouldMatchSerialDitheringForAVeryTallImageOnManyThreads() {
    final int height = 600_000;
    final int[] pixels = DitherTestImages.gradient(1, height);
    final OrderedDither dither = blackWhite4x4();
    try (final ForkJoinPool pool = new ForkJoinPool(32); final ImageBuffer image = ImageBuffer.buffer(pixels, 1, height)) {
      final byte[] serial = dither.ditherIntoBytes(image);
      final byte[] parallel = dither.ditherIntoBytes(image, pool);
      assertArrayEquals(serial, parallel);
    }
  }

  @Test
  void shouldMatchSerialDitheringWhenDitheringOnASingleThread() {
    final int[] pixels = DitherTestImages.gradient(GRADIENT_WIDTH, TALL_GRADIENT_HEIGHT);
    final OrderedDither dither = blackWhite4x4();
    try (
      final ForkJoinPool pool = new ForkJoinPool(1);
      final ImageBuffer image = ImageBuffer.buffer(pixels, GRADIENT_WIDTH, TALL_GRADIENT_HEIGHT)
    ) {
      final byte[] serial = dither.ditherIntoBytes(image);
      final byte[] parallel = dither.ditherIntoBytes(image, pool);
      assertArrayEquals(serial, parallel);
    }
  }

  @Test
  void shouldMatchTheIndicesWhenDitheringInPlace() {
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
      final int ditheredColor = inPlace[index];
      assertEquals(colors[indices[index]], ditheredColor);
    }
  }

  @Test
  void shouldUseTheMapPaletteInTheDefaultBuilder() {
    final OrderedDitherBuilder builder = DitherAlgorithm.ordered();
    final BayerDither dither = builder.build();
    final DitherPalette palette = dither.getPalette();
    assertSame(DitherPalette.DEFAULT_MAP_PALETTE, palette);
  }

  @Test
  void shouldUseA2x2MatrixInTheDefaultBuilder() {
    final OrderedDitherBuilder builder = DitherAlgorithm.ordered();
    final OrderedDitherBuilder blackWhite = builder.withPalette(DitherTestImages.BLACK_WHITE);
    final BayerDither dither = blackWhite.build();
    final byte[] indices = ditherMidGray(dither, 8, 8);
    final byte[] expected = new byte[64];
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        expected[row * 8 + column] = (byte) ((row + column) % 2);
      }
    }

    assertArrayEquals(expected, indices, "the default 2x2 ranks darken the diagonal and brighten the other cells");

    for (int row = 0; row < 6; row++) {
      for (int column = 0; column < 6; column++) {
        final int index = indices[row * 8 + column];
        final int right = indices[row * 8 + column + 2];
        final int below = indices[(row + 2) * 8 + column];
        assertEquals(index, right, "the default pattern repeats every two columns");
        assertEquals(index, below, "and every two rows");
      }
    }
  }

  @Test
  void shouldReturnTheBuilderFromBuilderMethodsForChaining() {
    final OrderedDitherBuilder builder = DitherAlgorithm.ordered();
    final PixelMapper mapper = PixelMapper.ofPixelMapper(BayerDither.NORMAL_8X8, PixelMapper.MINIMUM_STRENGTH);
    final OrderedDitherBuilder withPalette = builder.withPalette(DitherTestImages.BLACK_WHITE);
    final OrderedDitherBuilder withMatrix = builder.withDitherMatrix(mapper);
    assertSame(builder, withPalette);
    assertSame(builder, withMatrix);
  }

  @Test
  void shouldRejectMissingArgumentsInTheBuilder() {
    final OrderedDitherBuilder builder = DitherAlgorithm.ordered();
    assertThrows(NullPointerException.class, () -> builder.withPalette(null));
    assertThrows(NullPointerException.class, () -> builder.withDitherMatrix(null));
    assertThrows(NullPointerException.class, () -> new OrderedDither(DitherTestImages.BLACK_WHITE, null));
  }

  @Test
  void shouldRejectInvalidInput() {
    final OrderedDither dither = blackWhite4x4();
    final NullPointerException missingImage = assertThrows(NullPointerException.class, () -> dither.ditherIntoBytes(null));
    final String missingImageMessage = missingImage.getMessage();
    assertEquals("Image must not be null!", missingImageMessage);

    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final NullPointerException missingParallelImage = assertThrows(NullPointerException.class, () -> dither.ditherIntoBytes(null, pool));
      final String missingParallelImageMessage = missingParallelImage.getMessage();
      assertEquals("Image must not be null!", missingParallelImageMessage);
    }

    try (final ImageBuffer image = ImageBuffer.buffer(new int[1], 1, 1)) {
      assertThrows(NullPointerException.class, () -> dither.ditherIntoBytes(image, null));
    }

    assertThrows(IllegalArgumentException.class, () -> dither.dither(new int[3], 2));
  }

  @Test
  void shouldNormalizeThresholdMatricesAroundZero() {
    final PixelMapper mapper = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, BayerDither.NORMAL_2X2_MAXIMUM, UNIT_STRENGTH);
    final PixelMapper doubled = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, 2.0f);
    final float[][] matrix = mapper.getMatrix();
    final float[][] doubledMatrix = doubled.getMatrix();
    final float strength = doubled.getStrength();
    final float[] firstRow = matrix[0];
    assertArrayEquals(new float[] { -0.375f, 0.125f }, firstRow);

    final float[] secondRow = matrix[1];
    assertArrayEquals(new float[] { 0.375f, -0.125f }, secondRow);

    final float[] doubledFirstRow = doubledMatrix[0];
    assertArrayEquals(new float[] { -0.75f, 0.25f }, doubledFirstRow);
    assertEquals(2.0f, strength);
  }

  @Test
  void shouldNormalizeThresholdsAcrossTheEntireIntegerRange() {
    final int[][] thresholds = { { Integer.MIN_VALUE, 0, Integer.MAX_VALUE } };
    final PixelMapper mapper = PixelMapper.ofPixelMapper(thresholds, UNIT_STRENGTH);
    final float[][] matrix = mapper.getMatrix();
    final float[] firstRow = matrix[0];
    assertArrayEquals(new float[] { -0.5f, 0x1.0p-33f, 0.5f }, firstRow);
  }

  @Test
  void shouldPushEveryPixelAllTheWayItsOffsetPointsWithALargeStrength() {
    final PixelMapper strong = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, 1e30f);
    final OrderedDither dither = new OrderedDither(DitherPalette.colors(0x000000, 0xFFFFFF), strong);
    final int[] gray = new int[SMALL_IMAGE_WIDTH * SMALL_IMAGE_HEIGHT];
    Arrays.fill(gray, MID_GRAY);
    try (final ImageBuffer image = ImageBuffer.buffer(gray, SMALL_IMAGE_WIDTH, SMALL_IMAGE_HEIGHT)) {
      final byte[] indices = dither.ditherIntoBytes(image);
      assertArrayEquals(new byte[] { 0, 1, 1, 0 }, indices, "a positive offset brightens to white, a negative one darkens to black");
    }
  }

  @Test
  void shouldRejectNonFiniteStrengths() {
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, Float.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, Float.NEGATIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, Float.NaN));
  }

  @Test
  void shouldAcceptAStrengthOfZeroInThePixelMapper() {
    final PixelMapper flat = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, 0.0f);
    final float[][] matrix = flat.getMatrix();
    final float strength = flat.getStrength();
    assertEquals(0.0f, strength, "a strength of 0 is allowed");
    // every threshold is (value / max - 0.5) * strength, so the entries below the middle of the pattern come out as
    // negative zero; the tolerance compares the numbers rather than the sign of a zero
    final float exactly = 0.0f;
    final float[] firstRow = matrix[0];
    assertArrayEquals(new float[] { 0.0f, 0.0f }, firstRow, exactly, "and flattens every threshold of the pattern");
  }

  @Test
  void shouldRejectInvalidMatricesInThePixelMapper() {
    assertThrows(NullPointerException.class, () -> PixelMapper.ofPixelMapper((int[][]) null, 1, UNIT_STRENGTH));
    assertThrows(NullPointerException.class, () -> PixelMapper.ofPixelMapper((int[][]) null, UNIT_STRENGTH));
    assertThrows(NullPointerException.class, () -> PixelMapper.ofPixelMapper((ThresholdMatrix) null, 1, UNIT_STRENGTH));
    assertThrows(NullPointerException.class, () -> PixelMapper.ofPixelMapper((ThresholdMatrix) null, UNIT_STRENGTH));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, 0, UNIT_STRENGTH));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(new int[0][], UNIT_STRENGTH));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(new int[][] { {} }, UNIT_STRENGTH));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(new int[][] { { 1, 2 }, { 3 } }, UNIT_STRENGTH));
    assertThrows(IllegalArgumentException.class, () -> PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, -1.0f));
    assertThrows(NullPointerException.class, () -> PixelMapper.ofPixelMapper(new int[][] { null }, UNIT_STRENGTH));
    assertThrows(NullPointerException.class, () -> PixelMapper.ofPixelMapper(new int[][] { { 1 }, null }, UNIT_STRENGTH));
  }

  @Test
  void shouldGenerateBayerMatricesOfAnyPowerOfTwo() {
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

    final int[] firstRowOfFour = four[0];
    assertArrayEquals(new int[] { 0, 8, 2, 10 }, firstRowOfFour);

    for (final boolean wasSeen : seen) {
      assertTrue(wasSeen, "every threshold appears exactly once");
    }

    assertThrows(IllegalArgumentException.class, () -> BayerDither.createBayerMatrix(0));
    assertThrows(IllegalArgumentException.class, () -> BayerDither.createBayerMatrix(3));
  }

  @Test
  void shouldNotOverBrightenADarkGrayWithTheOrderedSpread() {
    final PixelMapper mapper = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, UNIT_STRENGTH);
    final OrderedDither dither = new OrderedDither(DitherTestImages.BLACK_WHITE, mapper);
    final int[] pixels = { 0xFF5A5A5A, 0xFF5A5A5A, 0xFF5A5A5A, 0xFF5A5A5A };
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, SMALL_IMAGE_WIDTH, SMALL_IMAGE_HEIGHT)) {
      final byte[] actual = dither.ditherIntoBytes(image);
      assertArrayEquals(new byte[] { 0, 0, 1, 0 }, actual);
    }
  }

  @Test
  void shouldAddTheCenteredPatternScaledToThePaletteSpacing() {
    final PixelMapper mapper = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, PixelMapper.NORMAL_STRENGTH);
    final OrderedDither dither = new OrderedDither(DitherTestImages.BLACK_WHITE, mapper);
    final int[] pixels = new int[SMALL_IMAGE_WIDTH * SMALL_IMAGE_HEIGHT];
    Arrays.fill(pixels, 0xFF6E6E6E);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, SMALL_IMAGE_WIDTH, SMALL_IMAGE_HEIGHT)) {
      final byte[] indices = dither.ditherIntoBytes(image);
      assertArrayEquals(new byte[] { 0, 1, 1, 0 }, indices);
    }
  }

  @Test
  void shouldGiveBayerMatricesTheSizeOfTheirNamesAndRankTheirCellsEvenly() {
    assertEvenRanking("NORMAL_2X2", BayerDither.NORMAL_2X2, 2, 2, BayerDither.NORMAL_2X2_MAXIMUM);
    assertEvenRanking("NORMAL_4X4", BayerDither.NORMAL_4X4, 4, 4, BayerDither.NORMAL_4X4_MAXIMUM);
    assertEvenRanking("NORMAL_8X8", BayerDither.NORMAL_8X8, 8, 8, BayerDither.NORMAL_8X8_MAXIMUM);
  }

  @Test
  void shouldMakeTheBayerConstantsExactlyTheMatricesOfTheRecursion() {
    final ThresholdMatrix generatedTwo = oneBasedBayerMatrix(2);
    final ThresholdMatrix generatedFour = oneBasedBayerMatrix(4);
    final ThresholdMatrix generatedEight = oneBasedBayerMatrix(8);

    final int[][] normalTwo = BayerDither.NORMAL_2X2.toArray();
    assertArrayEquals(generatedTwo.toArray(), normalTwo, "NORMAL_2X2");

    final int[][] normalFour = BayerDither.NORMAL_4X4.toArray();
    assertArrayEquals(generatedFour.toArray(), normalFour, "NORMAL_4X4");

    final int[][] normalEight = BayerDither.NORMAL_8X8.toArray();
    assertArrayEquals(generatedEight.toArray(), normalEight, "NORMAL_8X8");
  }

  @Test
  void shouldMakeTheEightByEightMatrixTheOneOfTheLiterature() {
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
  void shouldGiveClusteredDotMatricesTheSizeOfTheirNamesAndRankTheirCellsEvenly() {
    assertEvenRanking("CLUSTERED_DOT_4X4", BayerDither.CLUSTERED_DOT_4X4, 4, 4, BayerDither.CLUSTERED_DOT_4X4_MAXIMUM);
    assertEvenRanking("CLUSTERED_DOT_6X6", BayerDither.CLUSTERED_DOT_6X6, 6, 6, BayerDither.CLUSTERED_DOT_6X6_MAXIMUM);
    assertEvenRanking("CLUSTERED_DOT_6X6_2", BayerDither.CLUSTERED_DOT_6X6_2, 6, 6, BayerDither.CLUSTERED_DOT_6X6_2_MAXIMUM);
    assertEvenRanking("CLUSTERED_DOT_6X6_3", BayerDither.CLUSTERED_DOT_6X6_3, 6, 6, BayerDither.CLUSTERED_DOT_6X6_3_MAXIMUM);
    assertEvenRanking("CLUSTERED_DOT_SPIRAL_5X5", BayerDither.CLUSTERED_DOT_SPIRAL_5X5, 5, 5, BayerDither.CLUSTERED_DOT_SPIRAL_5X5_MAXIMUM);
    assertEvenRanking("CLUSTERED_DOT_8X8", BayerDither.CLUSTERED_DOT_8X8, 8, 8, BayerDither.CLUSTERED_DOT_8X8_MAXIMUM);
  }

  @Test
  void shouldGiveLineMatricesTheSizeOfTheirNamesAndRankTheirCellsEvenly() {
    assertEvenRanking("VERTICAL_5X3", BayerDither.VERTICAL_5X3, 3, 5, BayerDither.VERTICAL_5X3_MAXIMUM);
    assertEvenRanking("HORIZONTAL_3X5", BayerDither.HORIZONTAL_3X5, 5, 3, BayerDither.HORIZONTAL_3X5_MAXIMUM);
    assertEvenRanking(
      "CLUSTERED_DOT_HORIZONTAL_LINE",
      BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE,
      6,
      6,
      BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE_MAXIMUM
    );
    assertEvenRanking(
      "CLUSTERED_DOT_VERTICAL_LINE",
      BayerDither.CLUSTERED_DOT_VERTICAL_LINE,
      6,
      6,
      BayerDither.CLUSTERED_DOT_VERTICAL_LINE_MAXIMUM
    );
  }

  @Test
  void shouldGiveDiagonalClusteredDotMatricesTheSizeOfTheirNamesAndRankTheirCellsEvenly() {
    assertEvenRanking(
      "CLUSTERED_DOT_DIAGONAL_6X6",
      BayerDither.CLUSTERED_DOT_DIAGONAL_6X6,
      6,
      6,
      BayerDither.CLUSTERED_DOT_DIAGONAL_6X6_MAXIMUM
    );
    assertEvenRanking(
      "CLUSTERED_DOT_DIAGONAL_8X8",
      BayerDither.CLUSTERED_DOT_DIAGONAL_8X8,
      8,
      8,
      BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_MAXIMUM
    );
    assertEvenRanking(
      "CLUSTERED_DOT_DIAGONAL_8X8_2",
      BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2,
      8,
      8,
      BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2_MAXIMUM
    );
  }

  @Test
  void shouldGiveLargerDiagonalClusteredDotMatricesTheSizeOfTheirNamesAndRankTheirCellsEvenly() {
    assertEvenRanking(
      "CLUSTERED_DOT_DIAGONAL_8X8_3",
      BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3,
      8,
      8,
      BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3_MAXIMUM
    );
    assertEvenRanking(
      "CLUSTERED_DOT_DIAGONAL_16X16",
      BayerDither.CLUSTERED_DOT_DIAGONAL_16X16,
      16,
      16,
      BayerDither.CLUSTERED_DOT_DIAGONAL_16X16_MAXIMUM
    );
  }
}
