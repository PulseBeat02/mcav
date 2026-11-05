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

import java.util.Arrays;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;

// A pass's pending errors in padded ring rows; the heaviest tap takes the rounding remainder, so smooth gradients do not darken.
final class ErrorRows {

  private static final int CHANNELS = 3;
  private static final int GREEN_OFFSET = 1;
  private static final int BLUE_OFFSET = 2;

  private static final int CHANNEL_MASK = 0xFF;
  private static final int CHANNEL_MAXIMUM = 255;
  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final int PADDED_SIDES = 2;

  private final DiffusionKernel kernel;
  private final int padding;
  private final int rowCount;
  private final long weightSum;
  private final int largestTap;
  private final long[][] rows;

  ErrorRows(final DiffusionKernel kernel, final int width) {
    this.kernel = kernel;
    this.padding = kernel.getMaximumOffsetX();
    this.rowCount = kernel.getMaximumOffsetY() + 1;
    this.weightSum = sumWeights(kernel);
    this.largestTap = findLargestTap(kernel);
    final int paddedWidth = width + PADDED_SIDES * this.padding;
    this.rows = new long[this.rowCount][paddedWidth * CHANNELS];
  }

  private static long sumWeights(final DiffusionKernel kernel) {
    final int tapCount = kernel.getTapCount();
    long sum = 0;
    for (int tap = 0; tap < tapCount; tap++) {
      sum += kernel.getWeight(tap);
    }

    return sum;
  }

  private static int findLargestTap(final DiffusionKernel kernel) {
    final int tapCount = kernel.getTapCount();
    int largest = 0;
    for (int tap = 1; tap < tapCount; tap++) {
      final int weight = kernel.getWeight(tap);
      final int largestWeight = kernel.getWeight(largest);
      if (weight > largestWeight) {
        largest = tap;
      }
    }

    return largest;
  }

  static int getScanStart(final int row, final int width) {
    return isForward(row) ? 0 : width - 1;
  }

  static int getScanEnd(final int row, final int width) {
    return isForward(row) ? width : -1;
  }

  static int getScanStep(final int row) {
    return isForward(row) ? 1 : -1;
  }

  private static boolean isForward(final int row) {
    return (row & 1) == 0;
  }

  static int red(final int color) {
    return (color >> RED_SHIFT) & CHANNEL_MASK;
  }

  static int green(final int color) {
    return (color >> GREEN_SHIFT) & CHANNEL_MASK;
  }

  static int blue(final int color) {
    return color & CHANNEL_MASK;
  }

  static int getLookupIndex(final int color) {
    final int red = red(color);
    final int green = green(color);
    final int blue = blue(color);
    return DitherUtils.getLookupIndex(red, green, blue);
  }

  int applyPendingError(final int argb, final int column, final int row) {
    final long[] errorRow = this.rows[row % this.rowCount];
    final int index = (column + this.padding) * CHANNELS;
    final int sourceRed = red(argb);
    final int sourceGreen = green(argb);
    final int sourceBlue = blue(argb);
    final int red = sourceRed + Math.clamp(errorRow[index], -sourceRed, CHANNEL_MAXIMUM - sourceRed);
    final int green = sourceGreen + Math.clamp(errorRow[index + GREEN_OFFSET], -sourceGreen, CHANNEL_MAXIMUM - sourceGreen);
    final int blue = sourceBlue + Math.clamp(errorRow[index + BLUE_OFFSET], -sourceBlue, CHANNEL_MAXIMUM - sourceBlue);
    return (red << RED_SHIFT) | (green << GREEN_SHIFT) | blue;
  }

  // The shares sum to the error times the weight sum over the divisor, rounded toward zero; overflow throws instead of wrapping.
  void diffuse(final int column, final int row, final int step, final int errorRed, final int errorGreen, final int errorBlue) {
    if (errorRed == 0 && errorGreen == 0 && errorBlue == 0) {
      return;
    }

    final int divisor = this.kernel.getDivisor();
    final int tapCount = this.kernel.getTapCount();
    long spreadRed = 0;
    long spreadGreen = 0;
    long spreadBlue = 0;
    for (int tap = 0; tap < tapCount; tap++) {
      final int weight = this.kernel.getWeight(tap);
      final long shareRed = ((long) errorRed * weight) / divisor;
      final long shareGreen = ((long) errorGreen * weight) / divisor;
      final long shareBlue = ((long) errorBlue * weight) / divisor;
      this.addError(tap, column, row, step, shareRed, shareGreen, shareBlue);
      spreadRed = Math.addExact(spreadRed, shareRed);
      spreadGreen = Math.addExact(spreadGreen, shareGreen);
      spreadBlue = Math.addExact(spreadBlue, shareBlue);
    }

    final long remainderRed = Math.multiplyExact((long) errorRed, this.weightSum) / divisor - spreadRed;
    final long remainderGreen = Math.multiplyExact((long) errorGreen, this.weightSum) / divisor - spreadGreen;
    final long remainderBlue = Math.multiplyExact((long) errorBlue, this.weightSum) / divisor - spreadBlue;
    this.addError(this.largestTap, column, row, step, remainderRed, remainderGreen, remainderBlue);
  }

  private void addError(final int tap, final int column, final int row, final int step, final long red, final long green, final long blue) {
    final int offsetX = this.kernel.getOffsetX(tap) * step;
    final int offsetY = this.kernel.getOffsetY(tap);
    final long[] targetRow = this.rows[(row + offsetY) % this.rowCount];
    final int targetIndex = (column + offsetX + this.padding) * CHANNELS;
    targetRow[targetIndex] = Math.addExact(targetRow[targetIndex], red);
    targetRow[targetIndex + GREEN_OFFSET] = Math.addExact(targetRow[targetIndex + GREEN_OFFSET], green);
    targetRow[targetIndex + BLUE_OFFSET] = Math.addExact(targetRow[targetIndex + BLUE_OFFSET], blue);
  }

  // Clears a finished row, so its slot can be reused for the row DiffusionKernel.getMaximumOffsetY() rows further down.
  void finishRow(final int row) {
    final long[] errorRow = this.rows[row % this.rowCount];
    Arrays.fill(errorRow, 0);
  }
}
