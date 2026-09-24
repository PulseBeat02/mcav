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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ErrorRows}, with expected values computed by hand from the Floyd-Steinberg weights 7, 3, 5 and 1
 * sixteenths.
 */
final class ErrorRowsTest {

  private static final int GRAY = 0x808080;

  @Test
  void scansEvenRowsForwardAndOddRowsBackward() {
    final int evenStart = ErrorRows.getScanStart(0, 5);
    final int evenEnd = ErrorRows.getScanEnd(0, 5);
    final int evenStep = ErrorRows.getScanStep(0);
    final int oddStart = ErrorRows.getScanStart(3, 5);
    final int oddEnd = ErrorRows.getScanEnd(3, 5);
    final int oddStep = ErrorRows.getScanStep(3);
    assertEquals(0, evenStart);
    assertEquals(5, evenEnd);
    assertEquals(1, evenStep);
    assertEquals(4, oddStart);
    assertEquals(-1, oddEnd);
    assertEquals(-1, oddStep);
  }

  @Test
  void splitsPackedColorsIntoChannels() {
    final int red = ErrorRows.red(0xFF123456);
    final int green = ErrorRows.green(0xFF123456);
    final int blue = ErrorRows.blue(0xFF123456);
    final int lookup = ErrorRows.getLookupIndex(0x123456);
    final int expectedLookup = DitherUtils.getLookupIndex(0x12, 0x34, 0x56);
    assertEquals(0x12, red);
    assertEquals(0x34, green);
    assertEquals(0x56, blue);
    assertEquals(expectedLookup, lookup);
  }

  @Test
  void spreadsErrorsToTheNeighborsNamedByTheKernel() {
    final ErrorRows rows = new ErrorRows(DiffusionKernel.FLOYD_STEINBERG, 4);
    rows.diffuse(1, 0, 1, 160, -32, 16);
    final int right = rows.applyPendingError(GRAY, 2, 0);
    final int belowLeft = rows.applyPendingError(GRAY, 0, 1);
    final int below = rows.applyPendingError(GRAY, 1, 1);
    final int belowRight = rows.applyPendingError(GRAY, 2, 1);
    final int untouched = rows.applyPendingError(GRAY, 3, 1);
    assertEquals(0xC67287, right, "7/16 of (160, -32, 16) is (70, -14, 7)");
    assertEquals(0x9E7A83, belowLeft, "3/16 of (160, -32, 16) is (30, -6, 3)");
    assertEquals(0xB27685, below, "5/16 of (160, -32, 16) is (50, -10, 5)");
    assertEquals(0x8A7E81, belowRight, "1/16 of (160, -32, 16) is (10, -2, 1)");
    assertEquals(GRAY, untouched);
  }

  @Test
  void mirrorsTheKernelOnRowsScannedBackward() {
    final ErrorRows rows = new ErrorRows(DiffusionKernel.FLOYD_STEINBERG, 4);
    rows.diffuse(2, 1, -1, 16, 16, 16);
    final int ahead = rows.applyPendingError(0, 1, 1);
    final int behind = rows.applyPendingError(0, 3, 1);
    final int belowBehind = rows.applyPendingError(0, 3, 2);
    final int below = rows.applyPendingError(0, 2, 2);
    final int belowAhead = rows.applyPendingError(0, 1, 2);
    assertEquals(0x070707, ahead, "on a backward row the next pixel is to the left");
    assertEquals(0, behind);
    assertEquals(0x030303, belowBehind);
    assertEquals(0x050505, below);
    assertEquals(0x010101, belowAhead);
  }

  @Test
  void spreadsAnErrorThatOnlyOneChannelHas() {
    final ErrorRows redOnly = new ErrorRows(DiffusionKernel.FLOYD_STEINBERG, 4);
    final ErrorRows greenOnly = new ErrorRows(DiffusionKernel.FLOYD_STEINBERG, 4);
    final ErrorRows blueOnly = new ErrorRows(DiffusionKernel.FLOYD_STEINBERG, 4);
    redOnly.diffuse(1, 0, 1, 160, 0, 0);
    greenOnly.diffuse(1, 0, 1, 0, 160, 0);
    blueOnly.diffuse(1, 0, 1, 0, 0, 160);
    final int rightOfRed = redOnly.applyPendingError(GRAY, 2, 0);
    final int belowRed = redOnly.applyPendingError(GRAY, 1, 1);
    final int rightOfGreen = greenOnly.applyPendingError(GRAY, 2, 0);
    final int rightOfBlue = blueOnly.applyPendingError(GRAY, 2, 0);
    final int belowBlue = blueOnly.applyPendingError(GRAY, 1, 1);
    assertEquals(0xC68080, rightOfRed, "7/16 of 160 is 70, and the channels without an error keep their value");
    assertEquals(0xB28080, belowRed, "5/16 of 160 is 50");
    assertEquals(0x80C680, rightOfGreen, "an error of a single channel is spread like any other");
    assertEquals(0x8080C6, rightOfBlue, "an error only the blue channel has is spread as well");
    assertEquals(0x8080B2, belowBlue);
  }

  @Test
  void clampsTheWantedColorToTheChannelRange() {
    final ErrorRows rows = new ErrorRows(DiffusionKernel.FLOYD_STEINBERG, 2);
    rows.diffuse(0, 0, 1, -160, 160, 0);
    final int clamped = rows.applyPendingError(0x20F010, 1, 0);
    assertEquals(0x00FF10, clamped, "0x20 - 70 clamps to 0 and 0xF0 + 70 clamps to 0xFF");
  }

  @Test
  void finishedRowsAreClearedForReuse() {
    final ErrorRows rows = new ErrorRows(DiffusionKernel.FLOYD_STEINBERG, 2);
    rows.diffuse(0, 0, 1, 64, 64, 64);
    rows.finishRow(0);
    final int nextRow = rows.applyPendingError(0x101010, 0, 1);
    final int reusedRow = rows.applyPendingError(0x101010, 1, 2);
    assertEquals(0x242424, nextRow, "the row below keeps its 5/16 of 64");
    assertEquals(0x101010, reusedRow, "row 2 reuses the cleared slot of row 0");
  }

  @Test
  void addsWhatRoundingCutsOffToTheTapWithTheLargestWeight() {
    final ErrorRows rows = new ErrorRows(DiffusionKernel.FLOYD_STEINBERG, 4);
    rows.diffuse(1, 0, 1, 10, -10, 0);
    final int right = rows.applyPendingError(GRAY, 2, 0);
    final int belowLeft = rows.applyPendingError(GRAY, 0, 1);
    final int below = rows.applyPendingError(GRAY, 1, 1);
    final int belowRight = rows.applyPendingError(GRAY, 2, 1);
    assertEquals(0x867A80, right, "7/16 of 10 rounds to 4, and the 2 that rounding cut off go to the largest tap");
    assertEquals(0x817F80, belowLeft, "3/16 of 10 rounds to 1");
    assertEquals(0x837D80, below, "5/16 of 10 rounds to 3");
    assertEquals(GRAY, belowRight, "1/16 of 10 rounds to 0, so the four shares add up to the whole error of 10");
  }

  @Test
  void spreadsOnlyTheFractionTheWeightsDescribe() {
    final ErrorRows rows = new ErrorRows(DiffusionKernel.ATKINSON, 4);
    rows.diffuse(0, 0, 1, 10, 10, 10);
    final int right = rows.applyPendingError(0, 1, 0);
    final int twoRight = rows.applyPendingError(0, 2, 0);
    final int below = rows.applyPendingError(0, 0, 1);
    final int belowRight = rows.applyPendingError(0, 1, 1);
    final int twoBelow = rows.applyPendingError(0, 0, 2);
    assertEquals(0x020202, right, "every share of 1/8 of 10 rounds to 1, and the remainder to 6/8 of 10 goes to the first tap");
    assertEquals(0x010101, twoRight);
    assertEquals(0x010101, below);
    assertEquals(0x010101, belowRight);
    assertEquals(0x010101, twoBelow, "Atkinson spreads 7 of 10 in total and drops the rest on purpose");
  }

  @Test
  void findsTheLargestTapWhereverItIs() {
    final DiffusionKernel kernel = new DiffusionKernel("Mostly down", 4, new int[][] { { 1, 0, 1 }, { 0, 1, 3 } });
    final ErrorRows rows = new ErrorRows(kernel, 2);
    rows.diffuse(0, 0, 1, 10, 10, 10);
    final int right = rows.applyPendingError(0, 1, 0);
    final int below = rows.applyPendingError(0, 0, 1);
    assertEquals(0x020202, right, "1/4 of 10 rounds to 2");
    assertEquals(0x080808, below, "3/4 of 10 rounds to 7, plus the remainder of 1");
  }

  @Test
  void preservesAUnitFractionWithLargeEquivalentWeights() {
    final DiffusionKernel kernel = new DiffusionKernel("Scaled unit", Integer.MAX_VALUE, new int[][] { { 1, 0, Integer.MAX_VALUE } });
    final ErrorRows rows = new ErrorRows(kernel, 2);
    rows.diffuse(0, 0, 1, 100, -100, 1);
    final int right = rows.applyPendingError(GRAY, 1, 0);
    assertEquals(0xE41C81, right, "MAX/MAX is one; scaling a fraction must not change any channel");
  }

  @Test
  void sumsLargeWeightsWithoutWrappingOrForbiddingAmplification() {
    final DiffusionKernel kernel = new DiffusionKernel(
      "Two unit shares",
      Integer.MAX_VALUE,
      new int[][] { { 1, 0, Integer.MAX_VALUE }, { 0, 1, Integer.MAX_VALUE } }
    );
    final ErrorRows rows = new ErrorRows(kernel, 2);
    rows.diffuse(0, 0, 1, 50, -50, 3);
    final int right = rows.applyPendingError(GRAY, 1, 0);
    final int below = rows.applyPendingError(GRAY, 0, 1);
    assertEquals(0xB24E83, right, "each tap receives one whole error; the total weight exceeds int range");
    assertEquals(0xB24E83, below);
  }

  @Test
  void dropsErrorsThatFallOutsideTheImage() {
    final ErrorRows rows = new ErrorRows(DiffusionKernel.STEVENSON_ARCE, 1);
    rows.diffuse(0, 0, 1, 200, 200, 200);
    rows.diffuse(0, 1, -1, 200, 200, 200);
    final int twoRowsDown = rows.applyPendingError(0, 0, 2);
    assertEquals(0x1A1A1A, twoRowsDown, "only the tap straight down, 26/200 of 200, lands inside a one pixel wide image");
  }
}
