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

/**
 * The pending errors of an error diffusion pass, together with the serpentine scan order the pass uses.
 *
 * <p>Only the rows a kernel can reach are kept, in a ring of padded rows: the row being dithered and the
 * {@link DiffusionKernel#getMaxOffsetY()} rows below it. The padding on both sides is as wide as the kernel reaches
 * sideways, so errors that fall outside the image land in the padding and no bounds checks are needed. Colors are
 * passed around as packed {@code 0xRRGGBB} values.
 *
 * <p>Every tap receives its share of the error rounded toward zero, and what the rounding cuts off is added to the
 * tap with the largest weight, so a kernel spreads exactly the fraction of the error its weights describe. Rounding
 * alone would lose up to one unit per tap and pixel, which darkens smooth gradients.
 *
 * <p>Rows are scanned in alternating directions: even rows from left to right and odd rows from right to left, which
 * avoids the diagonal streaks a fixed scanning direction produces. Instances belong to a single pass and are not
 * thread safe.
 */
final class ErrorRows {

  private static final int CHANNELS = 3;

  private final DiffusionKernel kernel;
  private final int padding;
  private final int rowCount;
  private final int weightSum;
  private final int largestTap;
  private final int[][] rows;

  /**
   * Creates the error rows of a pass over an image of the given width.
   *
   * @param kernel the kernel that spreads the errors
   * @param width  the width of the image
   */
  ErrorRows(final DiffusionKernel kernel, final int width) {
    this.kernel = kernel;
    this.padding = kernel.getMaxOffsetX();
    this.rowCount = kernel.getMaxOffsetY() + 1;
    this.weightSum = sumWeights(kernel);
    this.largestTap = findLargestTap(kernel);
    final int paddedWidth = width + 2 * this.padding;
    this.rows = new int[this.rowCount][paddedWidth * CHANNELS];
  }

  private static int sumWeights(final DiffusionKernel kernel) {
    final int tapCount = kernel.getTapCount();
    int sum = 0;
    for (int tap = 0; tap < tapCount; tap++) {
      sum += kernel.getWeight(tap);
    }
    return sum;
  }

  /**
   * Finds the tap with the largest weight, the first one of them if several share it.
   */
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

  /**
   * Gets the first column of a row in scan order.
   *
   * @param y     the row
   * @param width the width of the image
   * @return the column the scan of the row starts at
   */
  static int getScanStart(final int y, final int width) {
    return isForward(y) ? 0 : width - 1;
  }

  /**
   * Gets the column just past the last column of a row in scan order.
   *
   * @param y     the row
   * @param width the width of the image
   * @return the column the scan of the row stops at, exclusive
   */
  static int getScanEnd(final int y, final int width) {
    return isForward(y) ? width : -1;
  }

  /**
   * Gets the direction a row is scanned in.
   *
   * @param y the row
   * @return {@code 1} for left to right, {@code -1} for right to left
   */
  static int getScanStep(final int y) {
    return isForward(y) ? 1 : -1;
  }

  private static boolean isForward(final int y) {
    return (y & 1) == 0;
  }

  /**
   * Gets the red channel of a packed color.
   *
   * @param color the packed color
   * @return the red channel from 0 to 255
   */
  static int red(final int color) {
    return (color >> 16) & 0xFF;
  }

  /**
   * Gets the green channel of a packed color.
   *
   * @param color the packed color
   * @return the green channel from 0 to 255
   */
  static int green(final int color) {
    return (color >> 8) & 0xFF;
  }

  /**
   * Gets the blue channel of a packed color.
   *
   * @param color the packed color
   * @return the blue channel from 0 to 255
   */
  static int blue(final int color) {
    return color & 0xFF;
  }

  /**
   * Gets the index of the color lookup table that belongs to a packed color.
   *
   * @param color the packed color
   * @return the lookup index
   */
  static int getLookupIndex(final int color) {
    final int red = red(color);
    final int green = green(color);
    final int blue = blue(color);
    return DitherUtils.getLookupIndex(red, green, blue);
  }

  /**
   * Adds the error pending at a pixel to its color.
   *
   * @param argb the original color of the pixel
   * @param x    the column of the pixel
   * @param y    the row of the pixel
   * @return the wanted color of the pixel as a packed color, clamped to the valid range
   */
  int applyPendingError(final int argb, final int x, final int y) {
    final int[] row = this.rows[y % this.rowCount];
    final int index = (x + this.padding) * CHANNELS;
    final int wantedRed = red(argb) + row[index];
    final int wantedGreen = green(argb) + row[index + 1];
    final int wantedBlue = blue(argb) + row[index + 2];
    final int red = DitherUtils.clamp(wantedRed);
    final int green = DitherUtils.clamp(wantedGreen);
    final int blue = DitherUtils.clamp(wantedBlue);
    return (red << 16) | (green << 8) | blue;
  }

  /**
   * Spreads an error from a pixel to its unprocessed neighbors, as described by the kernel. The shares add up to
   * the error times the sum of the weights divided by the divisor, rounded toward zero, so no error is lost to
   * rounding.
   *
   * @param x          the column of the pixel
   * @param y          the row of the pixel
   * @param step       the scan direction of the row, which mirrors the kernel on odd rows
   * @param errorRed   the error of the red channel
   * @param errorGreen the error of the green channel
   * @param errorBlue  the error of the blue channel
   */
  void diffuse(final int x, final int y, final int step, final int errorRed, final int errorGreen, final int errorBlue) {
    if (errorRed == 0 && errorGreen == 0 && errorBlue == 0) {
      return;
    }
    final int divisor = this.kernel.getDivisor();
    final int tapCount = this.kernel.getTapCount();
    int spreadRed = 0;
    int spreadGreen = 0;
    int spreadBlue = 0;
    for (int tap = 0; tap < tapCount; tap++) {
      final int weight = this.kernel.getWeight(tap);
      final int shareRed = (errorRed * weight) / divisor;
      final int shareGreen = (errorGreen * weight) / divisor;
      final int shareBlue = (errorBlue * weight) / divisor;
      this.addError(tap, x, y, step, shareRed, shareGreen, shareBlue);
      spreadRed += shareRed;
      spreadGreen += shareGreen;
      spreadBlue += shareBlue;
    }
    final int remainderRed = (errorRed * this.weightSum) / divisor - spreadRed;
    final int remainderGreen = (errorGreen * this.weightSum) / divisor - spreadGreen;
    final int remainderBlue = (errorBlue * this.weightSum) / divisor - spreadBlue;
    this.addError(this.largestTap, x, y, step, remainderRed, remainderGreen, remainderBlue);
  }

  private void addError(final int tap, final int x, final int y, final int step, final int red, final int green, final int blue) {
    final int offsetX = this.kernel.getOffsetX(tap) * step;
    final int offsetY = this.kernel.getOffsetY(tap);
    final int[] targetRow = this.rows[(y + offsetY) % this.rowCount];
    final int targetIndex = (x + offsetX + this.padding) * CHANNELS;
    targetRow[targetIndex] += red;
    targetRow[targetIndex + 1] += green;
    targetRow[targetIndex + 2] += blue;
  }

  /**
   * Clears a finished row, so its slot can be reused for the row {@link DiffusionKernel#getMaxOffsetY()} rows
   * further down.
   *
   * @param y the row that was finished
   */
  void finishRow(final int y) {
    final int[] row = this.rows[y % this.rowCount];
    Arrays.fill(row, 0);
  }
}
