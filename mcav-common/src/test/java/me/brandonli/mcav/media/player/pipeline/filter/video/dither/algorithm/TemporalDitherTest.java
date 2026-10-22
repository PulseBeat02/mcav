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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.zip.CRC32;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalFloydSteinbergDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.junit.jupiter.api.Test;

final class TemporalDitherTest {

  private static final DitherPalette PRIMARIES = DitherPalette.colors(0x000000, 0xFF0000, 0x00FF00, 0xFFFF00, 0xFF00FF, 0xFFFFFF);
  private static final int BLACK = 0;
  private static final int RED = 1;
  private static final int GREEN = 2;
  private static final int YELLOW = 3;
  private static final int MAGENTA = 4;
  private static final int NO_DIFFUSION = 1_000;

  private static final long FOUR_STRIP_GRADIENT_CHECKSUM = 1_452_660_893L;

  private static final DitherPalette NEAR_BLACK = DitherPalette.colors(0x000000, 0x141414, 0xFFFFFF);
  private static final int DARKEST = 0;
  private static final int DIM = 1;
  private static final int BRIGHTEST = 2;

  private static final int OPAQUE_BLACK = 0xFF000000;
  private static final int OPAQUE_RED = 0xFFFF0000;
  private static final int OPAQUE_GREEN = 0xFF00FF00;
  private static final int DARK_RED = 0xFF400000;
  private static final int DARK_GRAY = 0xFF606060;
  private static final int MIDDLE_GRAY = 0xFF808080;

  private static final int SMALL_THRESHOLD = 8;
  private static final int DARK_RED_DRIFT = 191;
  private static final int MAXIMUM_THRESHOLD = 255;

  private static final int DARK_GRAY_ERROR = 288;

  private static final int SOLID_FRAME_SIDE = 16;

  private static final int GRADIENT_WIDTH = 320;
  private static final int GRADIENT_HEIGHT = 180;

  private static final int PARALLELISM = 4;

  private static byte[] ditherSolid(final TemporalDitherAlgorithm dither, final int argb) {
    final int[] pixels = new int[SOLID_FRAME_SIDE * SOLID_FRAME_SIDE];
    Arrays.fill(pixels, argb);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, SOLID_FRAME_SIDE, SOLID_FRAME_SIDE)) {
      return dither.ditherIntoBytes(image);
    }
  }

  private static byte[] ditherSolidInParallel(final TemporalDitherAlgorithm dither, final int argb, final ForkJoinPool pool) {
    final int[] pixels = new int[SOLID_FRAME_SIDE * SOLID_FRAME_SIDE];
    Arrays.fill(pixels, argb);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, SOLID_FRAME_SIDE, SOLID_FRAME_SIDE)) {
      return dither.ditherIntoBytes(image, pool);
    }
  }

  // Counts the rows of a dithered frame that hold no white pixel at all, which is what a strip that was never dithered
  // leaves behind.
  private static int countRowsWithoutWhite(final byte[] indices, final int width) {
    final int height = indices.length / width;
    int rowsWithoutWhite = 0;
    for (int row = 0; row < height; row++) {
      boolean white = false;
      for (int column = 0; column < width; column++) {
        final int index = indices[row * width + column];
        white = white || index == 1;
      }
      if (!white) {
        rowsWithoutWhite++;
      }
    }

    return rowsWithoutWhite;
  }

  private static void assertAll(final byte[] indices, final int expected, final String message) {
    for (final byte index : indices) {
      assertEquals(expected, index, message);
    }
  }

  private static int countRed(final byte[] indices) {
    int count = 0;
    for (final byte index : indices) {
      if (index == RED) {
        count++;
      }
    }

    return count;
  }

  @Test
  void shouldDitherIdenticalFramesIdentically() {
    final int[] pixels = DitherTestImages.gradient(GRADIENT_WIDTH, GRADIENT_HEIGHT);
    final TemporalDitherAlgorithm dither = DitherAlgorithm.temporalFloydSteinberg();
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, GRADIENT_WIDTH, GRADIENT_HEIGHT)) {
      final byte[] first = dither.ditherIntoBytes(image);
      final byte[] second = dither.ditherIntoBytes(image);
      assertArrayEquals(first, second);
    }
  }

  @Test
  void shouldDitherDeterministicallyInParallelAndMatchTheSerialBrightness() {
    final int width = GRADIENT_WIDTH;
    final int height = GRADIENT_HEIGHT;
    final int[] pixels = DitherTestImages.gradient(width, height);
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE);
    final ForkJoinPool pool = new ForkJoinPool(PARALLELISM);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, width, height)) {
      final byte[] serial = dither.ditherIntoBytes(image);
      dither.resetTemporalState();
      final byte[] parallel = dither.ditherIntoBytes(image, pool);
      dither.resetTemporalState();
      final byte[] parallelAgain = dither.ditherIntoBytes(image, pool);
      final double serialRatio = DitherTestImages.whiteRatio(serial);
      final double parallelRatio = DitherTestImages.whiteRatio(parallel);
      assertArrayEquals(parallel, parallelAgain);
      assertEquals(serialRatio, parallelRatio, 0.02);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void shouldSplitTheFrameIntoOneStripPerThread() {
    final int[] pixels = DitherTestImages.gradient(GRADIENT_WIDTH, GRADIENT_HEIGHT);
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE);
    try (
      final ForkJoinPool pool = new ForkJoinPool(PARALLELISM);
      final ImageBuffer image = ImageBuffer.buffer(pixels, GRADIENT_WIDTH, GRADIENT_HEIGHT)
    ) {
      final byte[] indices = dither.ditherIntoBytes(image, pool);
      final CRC32 checksum = new CRC32();
      checksum.update(indices);

      final long value = checksum.getValue();
      assertEquals(FOUR_STRIP_GRADIENT_CHECKSUM, value, "four strips of 45 rows, each warmed up on the rows above it");
    }
  }

  @Test
  void shouldKeepThePreviousColorWhileTheWantedColorStaysClose() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, DARK_RED_DRIFT, NO_DIFFUSION, 1.0f);
    final TemporalDitherAlgorithm fresh = new TemporalFloydSteinbergDither(PRIMARIES, DARK_RED_DRIFT, NO_DIFFUSION, 1.0f);
    final byte[] exact = ditherSolid(dither, OPAQUE_RED);
    final byte[] darkRed = ditherSolid(dither, DARK_RED);
    final byte[] freshDarkRed = ditherSolid(fresh, DARK_RED);
    assertAll(exact, RED, "pure red is red");
    assertAll(freshDarkRed, BLACK, "without a previous frame, a dark red is closest to black");
    assertAll(darkRed, RED, "a drift of exactly the threshold, 0xFF - 0x40 = 191, keeps the previous color");
  }

  @Test
  void shouldDiffuseTheRemainingErrorOfKeptPixels() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, SMALL_THRESHOLD, 4, 1.0f);
    ditherSolid(dither, OPAQUE_RED);
    final byte[] slightlyDarker = ditherSolid(dither, 0xFFF70000);
    final int red = countRed(slightlyDarker);
    final boolean almostAllRed = red > (slightlyDarker.length * 9) / 10;
    assertTrue(almostAllRed, "almost every pixel stays red, " + red);

    final boolean someDarker = red < slightlyDarker.length;
    assertTrue(someDarker, "the kept pixels pass on their error of -8, so a few pixels turn darker");
  }

  @Test
  void shouldDiffuseOnlyTheConfiguredShareOfTheErrorOfKeptPixels() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE, 100, 0, 0.0f);
    final byte[] dark = ditherSolid(dither, 0xFF404040);
    final byte[] lighter = ditherSolid(dither, DARK_GRAY);
    final double darkRatio = DitherTestImages.whiteRatio(dark);
    assertEquals(0.0, darkRatio, "a dark gray is black");

    final double lighterRatio = DitherTestImages.whiteRatio(lighter);
    assertEquals(0.0, lighterRatio, "a strength of 0 diffuses nothing, a kept pixel's error included");
  }

  @Test
  void shouldRecomputePixelsWhenAnyChannelDriftsTooFar() {
    final int[][] drifts = { { 0xFFFFFF00, YELLOW }, { 0xFFFF00FF, MAGENTA }, { 0xFF00FF00, GREEN } };
    for (final int[] drift : drifts) {
      final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, SMALL_THRESHOLD, NO_DIFFUSION, 1.0f);
      ditherSolid(dither, OPAQUE_RED);
      final byte[] indices = ditherSolid(dither, drift[0]);
      final String target = Integer.toHexString(drift[0]);
      assertAll(indices, drift[1], "drifting to " + target);
    }
  }

  @Test
  void shouldForgetThePreviousFrameWhenTheSizeChangesOrOnReset() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, MAXIMUM_THRESHOLD, NO_DIFFUSION, 1.0f);
    final byte[] red = ditherSolid(dither, OPAQUE_RED);
    final byte[] greenKeptRed = ditherSolid(dither, OPAQUE_GREEN);
    dither.resetTemporalState();
    final byte[] greenAfterReset = ditherSolid(dither, OPAQUE_GREEN);
    final int[] smallRedPixels = new int[8 * 8];
    Arrays.fill(smallRedPixels, OPAQUE_RED);
    final byte[] smallRed;
    try (final ImageBuffer smaller = ImageBuffer.buffer(smallRedPixels, 8, 8)) {
      smallRed = dither.ditherIntoBytes(smaller);
    }

    final byte[] greenAfterResize = ditherSolid(dither, OPAQUE_GREEN);
    assertAll(red, RED, "the first frame is red");
    assertAll(greenKeptRed, RED, "a threshold of 255 keeps every previous color");
    assertAll(greenAfterReset, GREEN, "a reset forgets the previous frame");
    assertEquals(64, smallRed.length);
    assertAll(smallRed, RED, "a frame of another size ignores the previous frame");
    assertAll(greenAfterResize, GREEN, "and so does the next frame of the original size");
  }

  @Test
  void shouldLetCallersOwnTheReturnedIndices() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, MAXIMUM_THRESHOLD, NO_DIFFUSION, 1.0f);
    final byte[] first = ditherSolid(dither, OPAQUE_RED);
    Arrays.fill(first, (byte) GREEN);
    final byte[] second = ditherSolid(dither, OPAQUE_GREEN);
    final byte[] secondCopy = second.clone();
    Arrays.fill(second, (byte) BLACK);
    final byte[] third = ditherSolid(dither, OPAQUE_GREEN);
    assertAll(secondCopy, RED, "changing the returned indices does not change what the algorithm remembers");
    assertAll(third, RED, "not even once the algorithm reuses the array of the frame before");
  }

  @Test
  void shouldIgnoreTinyErrorsAndScaleTheRest() {
    final TemporalDitherAlgorithm damped = new TemporalFloydSteinbergDither(
      DitherTestImages.BLACK_WHITE,
      SMALL_THRESHOLD,
      NO_DIFFUSION,
      0.5f
    );
    final TemporalDitherAlgorithm halfStrength = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE, SMALL_THRESHOLD, 0, 0.5f);
    final TemporalDitherAlgorithm fullStrength = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE, SMALL_THRESHOLD, 0, 1.0f);
    final byte[] dampedIndices = ditherSolid(damped, DARK_GRAY);
    final byte[] halfIndices = ditherSolid(halfStrength, DARK_GRAY);
    final double dampedRatio = DitherTestImages.whiteRatio(dampedIndices);
    final double halfRatio = DitherTestImages.whiteRatio(halfIndices);
    final byte[] fullIndices = ditherSolid(fullStrength, DARK_GRAY);
    final double fullRatio = DitherTestImages.whiteRatio(fullIndices);
    final float strength = halfStrength.getErrorStrength();
    assertEquals(0.0, dampedRatio, "without diffusion a dark gray is all black");

    final boolean someWhite = halfRatio > 0.0;
    assertTrue(someWhite, "diffused errors produce some white pixels");

    final boolean fewerWhite = halfRatio < fullRatio;
    assertTrue(fewerWhite, "half of the error produces fewer white pixels, " + halfRatio + " < " + fullRatio);
    assertEquals(0.5f, strength);
  }

  @Test
  void shouldHonorTheErrorThresholdAndStrengthWhenDitheringInPlace() {
    final TemporalDitherAlgorithm threshold = new TemporalFloydSteinbergDither(
      DitherTestImages.BLACK_WHITE,
      SMALL_THRESHOLD,
      DARK_GRAY_ERROR,
      1.0f
    );
    final TemporalDitherAlgorithm zeroStrength = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE, SMALL_THRESHOLD, 0, 0.0f);
    final int[] expected = new int[SOLID_FRAME_SIDE * SOLID_FRAME_SIDE];
    Arrays.fill(expected, OPAQUE_BLACK);
    for (final TemporalDitherAlgorithm dither : new TemporalDitherAlgorithm[] { threshold, zeroStrength }) {
      final int[] pixels = new int[SOLID_FRAME_SIDE * SOLID_FRAME_SIDE];
      Arrays.fill(pixels, DARK_GRAY);
      dither.dither(pixels, SOLID_FRAME_SIDE);
      assertArrayEquals(expected, pixels);
    }
  }

  @Test
  void shouldNotReadOrReplaceTheRememberedVideoFrameWhenDitheringInPlace() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, MAXIMUM_THRESHOLD, NO_DIFFUSION, 1.0f);
    ditherSolid(dither, OPAQUE_RED);
    final int[] green = new int[SOLID_FRAME_SIDE * SOLID_FRAME_SIDE];
    Arrays.fill(green, OPAQUE_GREEN);
    final int[] expected = green.clone();
    dither.dither(green, SOLID_FRAME_SIDE);
    assertArrayEquals(expected, green, "in-place still images do not reuse the video frame");

    final byte[] remembered = ditherSolid(dither, OPAQUE_GREEN);
    assertAll(remembered, RED, "in-place still images do not replace video history");
  }

  @Test
  void shouldAcceptTheBoundaryValuesOfItsSettings() {
    final TemporalDitherAlgorithm lowest = new TemporalFloydSteinbergDither(PRIMARIES, 0, 0, 0.0f);
    final TemporalDitherAlgorithm highest = new TemporalFloydSteinbergDither(PRIMARIES, MAXIMUM_THRESHOLD, 0, 1.0f);
    final int lowestThreshold = lowest.getTemporalThreshold();
    final float lowestStrength = lowest.getErrorStrength();
    final int highestThreshold = highest.getTemporalThreshold();
    final float highestStrength = highest.getErrorStrength();
    assertEquals(0, lowestThreshold, "a threshold of 0 is allowed");
    assertEquals(0.0f, lowestStrength, "and so is a strength of 0");
    assertEquals(MAXIMUM_THRESHOLD, highestThreshold);
    assertEquals(1.0f, highestStrength);
  }

  @Test
  void shouldRememberTheIndicesOfTheFrameBeforeAndNotOfAnOlderOne() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(NEAR_BLACK, 40, NO_DIFFUSION, 1.0f);
    final byte[] white = ditherSolid(dither, 0xFFFFFFFF);
    final byte[] black = ditherSolid(dither, OPAQUE_BLACK);
    final byte[] slightlyBrighter = ditherSolid(dither, 0xFF181818);
    assertAll(white, BRIGHTEST, "the first frame has no previous frame");
    assertAll(black, DARKEST, "a drift of 255 recomputes the color");
    assertAll(slightlyBrighter, DARKEST, "the frame before is remembered, not an older one");
  }

  @Test
  void shouldRememberTheIndicesOfFramesThatWereDitheredInParallel() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, DARK_RED_DRIFT, NO_DIFFUSION, 1.0f);
    final ForkJoinPool pool = new ForkJoinPool(2);
    try {
      final byte[] red = ditherSolidInParallel(dither, OPAQUE_RED, pool);
      final byte[] darkRed = ditherSolidInParallel(dither, DARK_RED, pool);
      assertAll(red, RED, "pure red is red");
      assertAll(darkRed, RED, "the parallel path remembers its frame, so a drift of exactly 191 keeps red");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void shouldCompareEveryChannelWithTheThresholdOnItsOwn() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(NEAR_BLACK, 11, NO_DIFFUSION, 1.0f);
    final byte[] dim = ditherSolid(dither, 0xFF141414);
    final byte[] darker = ditherSolid(dither, 0xFF090909);
    assertAll(dim, DIM, "0x14 is the second color exactly");
    assertAll(darker, DIM, "a drift of exactly the threshold keeps the previous color");
  }

  @Test
  void shouldReuseThePreviousColorEvenWhenItIsTheFirstOfThePalette() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(NEAR_BLACK, 13, NO_DIFFUSION, 1.0f);
    final byte[] black = ditherSolid(dither, OPAQUE_BLACK);
    final byte[] slightlyBrighter = ditherSolid(dither, 0xFF0C0C0C);
    assertAll(black, DARKEST, "black is the first color of the palette");
    assertAll(slightlyBrighter, DARKEST, "index 0 is reused like every other index");
  }

  @Test
  void shouldDiffuseNothingWhenTheErrorIsExactlyTheThreshold() {
    final TemporalDitherAlgorithm atThreshold = new TemporalFloydSteinbergDither(
      DitherTestImages.BLACK_WHITE,
      SMALL_THRESHOLD,
      DARK_GRAY_ERROR,
      1.0f
    );
    final TemporalDitherAlgorithm justBelow = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE, SMALL_THRESHOLD, 287, 1.0f);
    final byte[] uniform = ditherSolid(atThreshold, DARK_GRAY);
    final byte[] diffused = ditherSolid(justBelow, DARK_GRAY);
    final double uniformRatio = DitherTestImages.whiteRatio(uniform);
    final double diffusedRatio = DitherTestImages.whiteRatio(diffused);
    assertEquals(0.0, uniformRatio, "an error of exactly the threshold is not diffused");

    final boolean errorLetThrough = diffusedRatio > 0.0;
    assertTrue(errorLetThrough, "one less lets the same error through, " + diffusedRatio);
  }

  @Test
  void shouldDitherEveryRowOfAFrameThatIsSplitIntoStrips() {
    final int width = 64;
    final int height = 64;
    final int[] gray = new int[width * height];
    Arrays.fill(gray, MIDDLE_GRAY);
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE);
    final ForkJoinPool pool = new ForkJoinPool(3);
    try (final ImageBuffer image = ImageBuffer.buffer(gray, width, height)) {
      final byte[] indices = dither.ditherIntoBytes(image, pool);
      final int rowsWithoutWhite = countRowsWithoutWhite(indices, width);
      assertEquals(0, rowsWithoutWhite, "every row belongs to a strip and is dithered");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void shouldDitherEveryRowOfATallFrameOnASingleThread() {
    final int height = 32_769;
    final int[] white = new int[height];
    Arrays.fill(white, 0xFFFFFFFF);
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE);
    try (final ForkJoinPool pool = new ForkJoinPool(1); final ImageBuffer image = ImageBuffer.buffer(white, 1, height)) {
      final byte[] indices = dither.ditherIntoBytes(image, pool);
      final int rowsWithoutWhite = countRowsWithoutWhite(indices, 1);
      assertEquals(0, rowsWithoutWhite, "one strip holds every row, although twice the rows times the rows overflows an int");
    }
  }

  @Test
  void shouldRejectAnIncompleteFinalRowBeforeChangingAnyPixels() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES);
    final int[] pixels = { MIDDLE_GRAY, MIDDLE_GRAY, MIDDLE_GRAY };
    final int[] original = pixels.clone();
    assertThrows(IllegalArgumentException.class, () -> dither.dither(pixels, 2));
    assertArrayEquals(original, pixels);
  }

  @Test
  void shouldRejectInvalidSettings() {
    assertThrows(IllegalArgumentException.class, () -> new TemporalFloydSteinbergDither(PRIMARIES, -1, 0, 1.0f));
    assertThrows(IllegalArgumentException.class, () -> new TemporalFloydSteinbergDither(PRIMARIES, 256, 0, 1.0f));
    assertThrows(IllegalArgumentException.class, () -> new TemporalFloydSteinbergDither(PRIMARIES, 0, -1, 1.0f));
    assertThrows(IllegalArgumentException.class, () -> new TemporalFloydSteinbergDither(PRIMARIES, 0, 0, -0.1f));
    assertThrows(IllegalArgumentException.class, () -> new TemporalFloydSteinbergDither(PRIMARIES, 0, 0, 1.1f));

    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES);
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
  }
}
