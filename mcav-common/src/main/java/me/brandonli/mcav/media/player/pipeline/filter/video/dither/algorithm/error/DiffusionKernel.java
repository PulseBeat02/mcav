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

import com.google.common.base.Preconditions;

/**
 * Describes how an error diffusion algorithm spreads the rounding error of a pixel to the pixels that have not
 * been processed yet.
 *
 * <p>A kernel is a list of taps. Every tap names a neighbor relative to the current pixel, where positive
 * {@code dx} points in the scanning direction and positive {@code dy} points to the following rows, and the
 * fraction {@code weight / divisor} of the error that neighbor receives. Only neighbors that are processed after
 * the current pixel may be named: {@code dy} must not be negative, and {@code dx} must be positive on the current
 * row. The weights of most kernels add up to the divisor, so the whole error is spread; Atkinson spreads only three
 * quarters of it on purpose. The shares are rounded toward zero, and what rounding cuts off goes to the tap with the
 * largest weight, so exactly the fraction the weights describe is spread and nothing is lost to rounding.
 *
 * <p>Kernels are immutable. The kernels of all common algorithms are available as constants.
 */
public final class DiffusionKernel {

  /**
   * Floyd-Steinberg, the classic four-tap kernel.
   */
  public static final DiffusionKernel FLOYD_STEINBERG = new DiffusionKernel(
    "Floyd-Steinberg",
    16,
    new int[][] { { 1, 0, 7 }, { -1, 1, 3 }, { 0, 1, 5 }, { 1, 1, 1 } }
  );

  /**
   * Filter Lite, also known as Sierra Lite, a three-tap kernel that is the fastest error diffusion.
   */
  public static final DiffusionKernel FILTER_LITE = new DiffusionKernel(
    "Filter Lite",
    4,
    new int[][] { { 1, 0, 2 }, { -1, 1, 1 }, { 0, 1, 1 } }
  );

  /**
   * Atkinson, which diffuses only three quarters of the error and produces a high-contrast look.
   */
  public static final DiffusionKernel ATKINSON = new DiffusionKernel(
    "Atkinson",
    8,
    new int[][] { { 1, 0, 1 }, { 2, 0, 1 }, { -1, 1, 1 }, { 0, 1, 1 }, { 1, 1, 1 }, { 0, 2, 1 } }
  );

  /**
   * Burkes, a seven-tap kernel over two rows.
   */
  public static final DiffusionKernel BURKES = new DiffusionKernel(
    "Burkes",
    32,
    new int[][] { { 1, 0, 8 }, { 2, 0, 4 }, { -2, 1, 2 }, { -1, 1, 4 }, { 0, 1, 8 }, { 1, 1, 4 }, { 2, 1, 2 } }
  );

  /**
   * Jarvis, Judice, and Ninke, a twelve-tap kernel over three rows that produces very smooth results.
   */
  public static final DiffusionKernel JARVIS_JUDICE_NINKE = new DiffusionKernel(
    "Jarvis-Judice-Ninke",
    48,
    new int[][] {
      { 1, 0, 7 },
      { 2, 0, 5 },
      { -2, 1, 3 },
      { -1, 1, 5 },
      { 0, 1, 7 },
      { 1, 1, 5 },
      { 2, 1, 3 },
      { -2, 2, 1 },
      { -1, 2, 3 },
      { 0, 2, 5 },
      { 1, 2, 3 },
      { 2, 2, 1 },
    }
  );

  /**
   * Stucki, a twelve-tap kernel over three rows with sharper results than Jarvis, Judice, and Ninke.
   */
  public static final DiffusionKernel STUCKI = new DiffusionKernel(
    "Stucki",
    42,
    new int[][] {
      { 1, 0, 8 },
      { 2, 0, 4 },
      { -2, 1, 2 },
      { -1, 1, 4 },
      { 0, 1, 8 },
      { 1, 1, 4 },
      { 2, 1, 2 },
      { -2, 2, 1 },
      { -1, 2, 2 },
      { 0, 2, 4 },
      { 1, 2, 2 },
      { 2, 2, 1 },
    }
  );

  /**
   * Stevenson and Arce, a twelve-tap kernel over four rows on a hexagonal grid.
   */
  public static final DiffusionKernel STEVENSON_ARCE = new DiffusionKernel(
    "Stevenson-Arce",
    200,
    new int[][] {
      { 2, 0, 32 },
      { -3, 1, 12 },
      { -1, 1, 26 },
      { 1, 1, 30 },
      { 3, 1, 16 },
      { -2, 2, 12 },
      { 0, 2, 26 },
      { 2, 2, 12 },
      { -3, 3, 5 },
      { -1, 3, 12 },
      { 1, 3, 12 },
      { 3, 3, 5 },
    }
  );

  private final String name;
  private final int divisor;
  private final int[] offsetsX;
  private final int[] offsetsY;
  private final int[] weights;
  private final int maxOffsetX;
  private final int maxOffsetY;

  /**
   * Constructs a new kernel.
   *
   * @param name    the name of the algorithm the kernel belongs to
   * @param divisor the number the weights are divided by
   * @param taps    the taps as {@code {dx, dy, weight}} triples
   * @throws IllegalArgumentException if a tap points to an already processed pixel or has a non-positive weight
   * @throws NullPointerException     if the name, the taps or one of the taps is null
   */
  public DiffusionKernel(final String name, final int divisor, final int[][] taps) {
    Preconditions.checkNotNull(name, "Name must not be null");
    Preconditions.checkNotNull(taps, "Taps must not be null");
    Preconditions.checkArgument(divisor > 0, "Divisor must be positive");
    Preconditions.checkArgument(taps.length > 0, "Kernel must have at least one tap");

    this.name = name;
    this.divisor = divisor;
    this.offsetsX = new int[taps.length];
    this.offsetsY = new int[taps.length];
    this.weights = new int[taps.length];
    for (int tapIndex = 0; tapIndex < taps.length; tapIndex++) {
      final int[] tap = taps[tapIndex];
      checkTap(tap, tapIndex);
      this.offsetsX[tapIndex] = tap[0];
      this.offsetsY[tapIndex] = tap[1];
      this.weights[tapIndex] = tap[2];
    }
    this.maxOffsetX = getLargestMagnitude(this.offsetsX);
    this.maxOffsetY = getLargestMagnitude(this.offsetsY);
  }

  /**
   * Checks that a tap has an offset and a weight, and only points to pixels that are processed later.
   */
  private static void checkTap(final int[] tap, final int tapIndex) {
    Preconditions.checkNotNull(tap, "Tap %s must not be null", tapIndex);
    Preconditions.checkArgument(tap.length == 3, "Tap %s must have three values", tapIndex);
    final int offsetX = tap[0];
    final int offsetY = tap[1];
    final int weight = tap[2];
    Preconditions.checkArgument(offsetY >= 0, "Tap %s points to a previous row", tapIndex);
    Preconditions.checkArgument(offsetY > 0 || offsetX > 0, "Tap %s points to an already processed pixel", tapIndex);
    Preconditions.checkArgument(weight > 0, "Tap %s must have a positive weight", tapIndex);
  }

  private static int getLargestMagnitude(final int[] values) {
    int largest = 0;
    for (final int value : values) {
      final int magnitude = Math.abs(value);
      largest = Math.max(largest, magnitude);
    }
    return largest;
  }

  /**
   * Gets the name of the algorithm the kernel belongs to.
   *
   * @return the name
   */
  public String getName() {
    return this.name;
  }

  /**
   * Gets the number the weights are divided by.
   *
   * @return the divisor
   */
  public int getDivisor() {
    return this.divisor;
  }

  /**
   * Gets the number of taps.
   *
   * @return the tap count
   */
  public int getTapCount() {
    return this.weights.length;
  }

  /**
   * Gets the horizontal offset of a tap, relative to the scanning direction.
   *
   * @param tap the tap index
   * @return the horizontal offset
   */
  public int getOffsetX(final int tap) {
    return this.offsetsX[tap];
  }

  /**
   * Gets the vertical offset of a tap.
   *
   * @param tap the tap index
   * @return the vertical offset, which is never negative
   */
  public int getOffsetY(final int tap) {
    return this.offsetsY[tap];
  }

  /**
   * Gets the weight of a tap.
   *
   * @param tap the tap index
   * @return the weight
   */
  public int getWeight(final int tap) {
    return this.weights[tap];
  }

  /**
   * Gets the largest horizontal distance any tap reaches.
   *
   * @return the maximum horizontal offset
   */
  public int getMaxOffsetX() {
    return this.maxOffsetX;
  }

  /**
   * Gets the largest number of rows any tap reaches ahead.
   *
   * @return the maximum vertical offset
   */
  public int getMaxOffsetY() {
    return this.maxOffsetY;
  }

  /**
   * Gets the name of the algorithm the kernel belongs to, such as {@code Floyd-Steinberg}.
   *
   * @return the name
   */
  @Override
  public String toString() {
    return this.name;
  }
}
