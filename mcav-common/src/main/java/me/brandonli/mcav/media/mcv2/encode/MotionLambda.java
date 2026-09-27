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
package me.brandonli.mcav.media.mcv2.encode;

import static me.brandonli.mcav.media.mcv2.Mcv2Format.CHANNELS;

/**
 * The lambda of a live stream's frames from the motion of its source. VMAF forgives more error in fast motion, so one
 * lambda that keeps quiet pictures near a VMAF of 76 spends far more than needed on fast gameplay (VMAF 89 at lambda
 * 72). A frame's lambda is the profile's on quiet pictures and rises with the source's temporal information above a knee:
 * {@code lambda * min(4, (ti / 4.6) ^ 0.79)}, where {@code ti} is the mean absolute change of the blurred luma of every
 * fourth pixel between consecutive source frames, averaged over about half a second.
 *
 * <p>The knee and the exponent are measured: at lambda 72 the quiet contents (the 1080p30 and 1080p60 proxies, a dinner
 * scene) score VMAF 75.3 to 76.8 and stay there, and the two gameplay captures, whose temporal information is 16.3 and
 * 12.3, reach 76 at lambda 195 and 157, which this rule gives both. The lambda depends on the source alone, never on
 * what the encoder made of it, so it cannot oscillate; a scene cut or a new size starts the average again, at the
 * profile's lambda.
 */
final class MotionLambda {

  /** The temporal information up to which a frame keeps the profile's lambda. */
  static final double KNEE = 4.6;

  /** How fast lambda rises with the temporal information above the knee, on a log-log scale. */
  static final double EXPONENT = 0.79;

  /** The largest factor lambda rises by. */
  static final double MAX_RAISE = 4.0;

  /** The weight of a frame in the average: its half-life is about eleven frames, a third of a second at 30 fps. */
  static final double SMOOTHING = 1.0 / 16;

  /** Every fourth pixel of every fourth row is sampled. */
  private static final int SAMPLING = 4;

  /** The samples are blurred over a 3x3 box of them. */
  private static final int BOX_AREA = 9;

  /** A sample's luma is {@code r + 2 g + b}, four times the luma. */
  private static final int LUMA_SCALE = 4;

  /** The blurred luma of the last frame's samples, times {@link #LUMA_SCALE} and {@link #BOX_AREA}. */
  private int[] previous = new int[0];

  private int columns;

  private int rows;

  /** The average temporal information, or NaN before a frame has been compared with the one before it. */
  private double motion = Double.NaN;

  /**
   * Gets the lambda of the next frame.
   *
   * @param base the profile's lambda
   * @return the base, raised with the average motion
   */
  double lambda(final double base) {
    return base * raise(this.motion);
  }

  /**
   * The factor a frame's lambda rises by at an average temporal information: 1 up to the knee, then with the motion.
   *
   * @param motion the average temporal information, or NaN before any
   * @return the factor, 1 to {@link #MAX_RAISE}
   */
  static double raise(final double motion) {
    if (!(motion > KNEE)) {
      return 1;
    }
    return Math.min(MAX_RAISE, Math.pow(motion / KNEE, EXPONENT));
  }

  /**
   * Measures a frame's motion against the frame before it and adds it to the average.
   *
   * @param rgb     the frame, row-major RGB
   * @param width   the width
   * @param height  the height
   * @param restart whether the frame starts a new scene, so the motion before it no longer applies
   */
  void observe(final byte[] rgb, final int width, final int height, final boolean restart) {
    final int columns = (width + SAMPLING - 1) / SAMPLING;
    final int rows = (height + SAMPLING - 1) / SAMPLING;
    final int[] current = blurredLuma(rgb, width, height);
    if (restart || columns != this.columns || rows != this.rows) {
      this.motion = Double.NaN;
    } else {
      this.add(temporalInformation(current, this.previous));
    }
    this.previous = current;
    this.columns = columns;
    this.rows = rows;
  }

  /**
   * Adds a frame's temporal information to the average; the first after a start is the average.
   *
   * @param information the temporal information, at least 0
   */
  void add(final double information) {
    this.motion = Double.isNaN(this.motion) ? information : this.motion + SMOOTHING * (information - this.motion);
  }

  /**
   * The luma of every fourth pixel of every fourth row, blurred over a 3x3 box of samples, the edge samples repeated:
   * {@code 36} times the blurred luma, exactly.
   *
   * @param rgb    the frame, row-major RGB
   * @param width  the width
   * @param height the height
   * @return the blurred samples, row-major
   */
  static int[] blurredLuma(final byte[] rgb, final int width, final int height) {
    final int columns = (width + SAMPLING - 1) / SAMPLING;
    final int rows = (height + SAMPLING - 1) / SAMPLING;
    // each row of samples summed over three columns, then three of those rows: the same sums as the box, separably
    final int[] across = new int[columns * rows];
    for (int j = 0; j < rows; j++) {
      final int line = j * SAMPLING * width;
      int left = luma(rgb, line);
      int middle = left;
      for (int i = 0; i < columns; i++) {
        final int right = i + 1 < columns ? luma(rgb, line + (i + 1) * SAMPLING) : middle;
        across[j * columns + i] = left + middle + right;
        left = middle;
        middle = right;
      }
    }
    final int[] blurred = new int[across.length];
    for (int j = 0; j < rows; j++) {
      final int above = Math.max(j - 1, 0) * columns;
      final int at = j * columns;
      final int below = Math.min(j + 1, rows - 1) * columns;
      for (int i = 0; i < columns; i++) {
        blurred[at + i] = across[above + i] + across[at + i] + across[below + i];
      }
    }
    return blurred;
  }

  /** The luma of a pixel, {@code r + 2 g + b}: four times the luma. */
  private static int luma(final byte[] rgb, final int pixel) {
    final int at = pixel * CHANNELS;
    return (rgb[at] & 0xFF) + 2 * (rgb[at + 1] & 0xFF) + (rgb[at + 2] & 0xFF);
  }

  /**
   * The mean absolute difference of two frames' blurred samples, in luma units.
   *
   * @param current  the frame's samples, from {@link #blurredLuma}
   * @param previous the samples of the frame before, as many
   * @return the temporal information
   */
  static double temporalInformation(final int[] current, final int[] previous) {
    long sum = 0;
    for (int i = 0; i < current.length; i++) {
      sum += Math.abs(current[i] - previous[i]);
    }
    return (double) sum / ((long) current.length * LUMA_SCALE * BOX_AREA);
  }
}
