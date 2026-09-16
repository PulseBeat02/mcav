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
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalFloydSteinbergDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link TemporalDitherAlgorithm} and {@link TemporalFloydSteinbergDither}.
 */
final class TemporalDitherTest {

  private static final DitherPalette PRIMARIES = DitherPalette.colors(0x000000, 0xFF0000, 0x00FF00, 0xFFFF00, 0xFF00FF, 0xFFFFFF);
  private static final int BLACK = 0;
  private static final int RED = 1;
  private static final int GREEN = 2;
  private static final int YELLOW = 3;
  private static final int MAGENTA = 4;
  private static final int NO_DIFFUSION = 1_000;
  // three colors close enough together that a drift within the threshold can point at another color than the nearest
  private static final DitherPalette NEAR_BLACK = DitherPalette.colors(0x000000, 0x141414, 0xFFFFFF);
  private static final int DARKEST = 0;
  private static final int DIM = 1;
  private static final int BRIGHTEST = 2;

  private static byte[] ditherSolid(final TemporalDitherAlgorithm dither, final int argb) {
    final int[] pixels = new int[16 * 16];
    Arrays.fill(pixels, argb);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 16, 16)) {
      return dither.ditherIntoBytes(image);
    }
  }

  private static byte[] ditherSolidInParallel(final TemporalDitherAlgorithm dither, final int argb, final ForkJoinPool pool) {
    final int[] pixels = new int[16 * 16];
    Arrays.fill(pixels, argb);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 16, 16)) {
      return dither.ditherIntoBytes(image, pool);
    }
  }

  /**
   * Counts the rows of a dithered frame that hold no white pixel at all, which is what a strip that was never
   * dithered leaves behind.
   *
   * @param indices the palette indices of the frame
   * @param width   the width of the frame
   * @return the number of rows without a single white pixel
   */
  private static int countRowsWithoutWhite(final byte[] indices, final int width) {
    final int height = indices.length / width;
    int rowsWithoutWhite = 0;
    for (int y = 0; y < height; y++) {
      boolean white = false;
      for (int x = 0; x < width; x++) {
        final int index = indices[y * width + x];
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
  void identicalFramesDitherIdentically() {
    final int[] pixels = DitherTestImages.gradient(320, 180);
    final TemporalDitherAlgorithm dither = DitherAlgorithm.temporalFloydSteinberg();
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 320, 180)) {
      final byte[] first = dither.ditherIntoBytes(image);
      final byte[] second = dither.ditherIntoBytes(image);
      assertArrayEquals(first, second);
    }
  }

  @Test
  void parallelDitheringIsDeterministicAndMatchesTheSerialBrightness() {
    final int width = 320;
    final int height = 180;
    final int[] pixels = DitherTestImages.gradient(width, height);
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE);
    final ForkJoinPool pool = new ForkJoinPool(4);
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
  void keepsThePreviousColorWhileTheWantedColorStaysClose() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, 191, NO_DIFFUSION, 1.0f);
    final TemporalDitherAlgorithm fresh = new TemporalFloydSteinbergDither(PRIMARIES, 191, NO_DIFFUSION, 1.0f);
    final byte[] exact = ditherSolid(dither, 0xFFFF0000);
    final byte[] darkRed = ditherSolid(dither, 0xFF400000);
    final byte[] freshDarkRed = ditherSolid(fresh, 0xFF400000);
    assertAll(exact, RED, "pure red is red");
    assertAll(freshDarkRed, BLACK, "without a previous frame, a dark red is closest to black");
    assertAll(darkRed, RED, "a drift of exactly the threshold, 0xFF - 0x40 = 191, keeps the previous color");
  }

  @Test
  void diffusesTheRemainingErrorOfKeptPixels() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, 8, 4, 1.0f);
    ditherSolid(dither, 0xFFFF0000);
    final byte[] slightlyDarker = ditherSolid(dither, 0xFFF70000);
    final int red = countRed(slightlyDarker);
    assertTrue(red > (slightlyDarker.length * 9) / 10, "almost every pixel stays red, " + red);
    assertTrue(red < slightlyDarker.length, "the kept pixels pass on their error of -8, so a few pixels turn darker");
  }

  @Test
  void recomputesPixelsWhenAnyChannelDriftsTooFar() {
    final int[][] drifts = { { 0xFFFFFF00, YELLOW }, { 0xFFFF00FF, MAGENTA }, { 0xFF00FF00, GREEN } };
    for (final int[] drift : drifts) {
      final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, 8, NO_DIFFUSION, 1.0f);
      ditherSolid(dither, 0xFFFF0000);
      final byte[] indices = ditherSolid(dither, drift[0]);
      final String target = Integer.toHexString(drift[0]);
      assertAll(indices, drift[1], "drifting to " + target);
    }
  }

  @Test
  void forgetsThePreviousFrameWhenTheSizeChangesOrOnReset() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, 255, NO_DIFFUSION, 1.0f);
    final byte[] red = ditherSolid(dither, 0xFFFF0000);
    final byte[] greenKeptRed = ditherSolid(dither, 0xFF00FF00);
    dither.resetTemporalState();
    final byte[] greenAfterReset = ditherSolid(dither, 0xFF00FF00);
    final int[] smallRedPixels = new int[8 * 8];
    Arrays.fill(smallRedPixels, 0xFFFF0000);
    final byte[] smallRed;
    try (final ImageBuffer smaller = ImageBuffer.buffer(smallRedPixels, 8, 8)) {
      smallRed = dither.ditherIntoBytes(smaller);
    }
    final byte[] greenAfterResize = ditherSolid(dither, 0xFF00FF00);
    assertAll(red, RED, "the first frame is red");
    assertAll(greenKeptRed, RED, "a threshold of 255 keeps every previous color");
    assertAll(greenAfterReset, GREEN, "a reset forgets the previous frame");
    assertEquals(64, smallRed.length);
    assertAll(smallRed, RED, "a frame of another size ignores the previous frame");
    assertAll(greenAfterResize, GREEN, "and so does the next frame of the original size");
  }

  @Test
  void callersOwnTheReturnedIndices() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, 255, NO_DIFFUSION, 1.0f);
    final byte[] first = ditherSolid(dither, 0xFFFF0000);
    Arrays.fill(first, (byte) GREEN);
    final byte[] second = ditherSolid(dither, 0xFF00FF00);
    final byte[] secondCopy = second.clone();
    Arrays.fill(second, (byte) BLACK);
    final byte[] third = ditherSolid(dither, 0xFF00FF00);
    assertAll(secondCopy, RED, "changing the returned indices does not change what the algorithm remembers");
    assertAll(third, RED, "not even once the algorithm reuses the array of the frame before");
  }

  @Test
  void ignoresTinyErrorsAndScalesTheRest() {
    final TemporalDitherAlgorithm damped = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE, 8, NO_DIFFUSION, 0.5f);
    final TemporalDitherAlgorithm halfStrength = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE, 8, 0, 0.5f);
    final TemporalDitherAlgorithm fullStrength = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE, 8, 0, 1.0f);
    final byte[] dampedIndices = ditherSolid(damped, 0xFF606060);
    final byte[] halfIndices = ditherSolid(halfStrength, 0xFF606060);
    final double dampedRatio = DitherTestImages.whiteRatio(dampedIndices);
    final double halfRatio = DitherTestImages.whiteRatio(halfIndices);
    final byte[] fullIndices = ditherSolid(fullStrength, 0xFF606060);
    final double fullRatio = DitherTestImages.whiteRatio(fullIndices);
    final float strength = halfStrength.getErrorStrength();
    assertEquals(0.0, dampedRatio, "without diffusion a dark gray is all black");
    assertTrue(halfRatio > 0.0, "diffused errors produce some white pixels");
    assertTrue(halfRatio < fullRatio, "half of the error produces fewer white pixels, " + halfRatio + " < " + fullRatio);
    assertEquals(0.5f, strength);
  }

  @Test
  void acceptsTheBoundaryValuesOfItsSettings() {
    final TemporalDitherAlgorithm lowest = new TemporalFloydSteinbergDither(PRIMARIES, 0, 0, 0.0f);
    final TemporalDitherAlgorithm highest = new TemporalFloydSteinbergDither(PRIMARIES, 255, 0, 1.0f);
    final int lowestThreshold = lowest.getTemporalThreshold();
    final float lowestStrength = lowest.getErrorStrength();
    final int highestThreshold = highest.getTemporalThreshold();
    final float highestStrength = highest.getErrorStrength();
    assertEquals(0, lowestThreshold, "a threshold of 0 is allowed");
    assertEquals(0.0f, lowestStrength, "and so is a strength of 0");
    assertEquals(255, highestThreshold);
    assertEquals(1.0f, highestStrength);
  }

  @Test
  void remembersTheIndicesOfTheFrameBeforeAndNotOfAnOlderOne() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(NEAR_BLACK, 40, NO_DIFFUSION, 1.0f);
    final byte[] white = ditherSolid(dither, 0xFFFFFFFF);
    final byte[] black = ditherSolid(dither, 0xFF000000);
    final byte[] slightlyBrighter = ditherSolid(dither, 0xFF181818);
    assertAll(white, BRIGHTEST, "the first frame has no previous frame");
    assertAll(black, DARKEST, "a drift of 255 recomputes the color");
    // the drift of 24 from black is within the threshold, while the drift of 231 from the white of the frame before
    // that is not, and the nearest color of 0x18 would be 0x14
    assertAll(slightlyBrighter, DARKEST, "the frame before is remembered, not an older one");
  }

  @Test
  void remembersTheIndicesOfFramesThatWereDitheredInParallel() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES, 191, NO_DIFFUSION, 1.0f);
    final ForkJoinPool pool = new ForkJoinPool(2);
    try {
      final byte[] red = ditherSolidInParallel(dither, 0xFFFF0000, pool);
      final byte[] darkRed = ditherSolidInParallel(dither, 0xFF400000, pool);
      assertAll(red, RED, "pure red is red");
      assertAll(darkRed, RED, "the parallel path remembers its frame, so a drift of exactly 191 keeps red");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void comparesEveryChannelWithTheThresholdOnItsOwn() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(NEAR_BLACK, 11, NO_DIFFUSION, 1.0f);
    final byte[] dim = ditherSolid(dither, 0xFF141414);
    final byte[] darker = ditherSolid(dither, 0xFF090909);
    assertAll(dim, DIM, "0x14 is the second color exactly");
    // 0x14 - 0x09 = 11 on every channel, so the drift is exactly the threshold and the sum of two channels is not
    assertAll(darker, DIM, "a drift of exactly the threshold keeps the previous color");
  }

  @Test
  void reusesThePreviousColorEvenWhenItIsTheFirstOfThePalette() {
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(NEAR_BLACK, 13, NO_DIFFUSION, 1.0f);
    final byte[] black = ditherSolid(dither, 0xFF000000);
    final byte[] slightlyBrighter = ditherSolid(dither, 0xFF0C0C0C);
    assertAll(black, DARKEST, "black is the first color of the palette");
    // 0x0C drifts 12 from black, within the threshold, although 0x14 is the nearer color
    assertAll(slightlyBrighter, DARKEST, "index 0 is reused like every other index");
  }

  @Test
  void diffusesNothingWhenTheErrorIsExactlyTheThreshold() {
    final TemporalDitherAlgorithm atThreshold = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE, 8, 288, 1.0f);
    final TemporalDitherAlgorithm justBelow = new TemporalFloydSteinbergDither(DitherTestImages.BLACK_WHITE, 8, 287, 1.0f);
    // a gray of 0x60 is 96 away from black on every channel, so the total error is exactly 288
    final byte[] uniform = ditherSolid(atThreshold, 0xFF606060);
    final byte[] diffused = ditherSolid(justBelow, 0xFF606060);
    final double uniformRatio = DitherTestImages.whiteRatio(uniform);
    final double diffusedRatio = DitherTestImages.whiteRatio(diffused);
    assertEquals(0.0, uniformRatio, "an error of exactly the threshold is not diffused");
    assertTrue(diffusedRatio > 0.0, "one less lets the same error through, " + diffusedRatio);
  }

  @Test
  void dithersEveryRowOfAFrameThatIsSplitIntoStrips() {
    final int width = 64;
    final int height = 64;
    final int[] gray = new int[width * height];
    Arrays.fill(gray, 0xFF808080);
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
  void rejectsInvalidSettings() {
    assertThrows(IllegalArgumentException.class, () -> new TemporalFloydSteinbergDither(PRIMARIES, -1, 0, 1.0f));
    assertThrows(IllegalArgumentException.class, () -> new TemporalFloydSteinbergDither(PRIMARIES, 256, 0, 1.0f));
    assertThrows(IllegalArgumentException.class, () -> new TemporalFloydSteinbergDither(PRIMARIES, 0, -1, 1.0f));
    assertThrows(IllegalArgumentException.class, () -> new TemporalFloydSteinbergDither(PRIMARIES, 0, 0, -0.1f));
    assertThrows(IllegalArgumentException.class, () -> new TemporalFloydSteinbergDither(PRIMARIES, 0, 0, 1.1f));
    final TemporalDitherAlgorithm dither = new TemporalFloydSteinbergDither(PRIMARIES);
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      assertThrows(NullPointerException.class, () -> dither.ditherIntoBytes(null, pool));
    }
    try (final ImageBuffer image = ImageBuffer.buffer(new int[1], 1, 1)) {
      assertThrows(NullPointerException.class, () -> dither.ditherIntoBytes(image, null));
    }
  }
}
