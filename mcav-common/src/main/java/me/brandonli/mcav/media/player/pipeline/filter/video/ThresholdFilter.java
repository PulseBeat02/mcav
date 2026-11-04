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
package me.brandonli.mcav.media.player.pipeline.filter.video;

import com.google.common.base.Preconditions;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Applies a fixed threshold independently to every channel of every frame. To obtain black and white with
 * {@link opencv_imgproc#THRESH_BINARY}, use {@link GrayscaleFilter} first; thresholding color channels directly
 * can retain combinations of saturated colors.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class ThresholdFilter extends MatVideoFilter {

  private static final double CHANNEL_MAXIMUM = 255.0;

  private final double threshold;
  private final double maximumValue;
  private final int type;

  /**
   * Constructs a new threshold filter.
   *
   * @param threshold    the threshold value, from 0 to 255
   * @param maximumValue the value assigned to channels that pass the threshold, from 0 to 255
   * @param type         a fixed threshold mode from {@link opencv_imgproc}; automatic Otsu and triangle modes
   *                     require a single-channel input and are not suitable for this three-channel BGR filter
   * @throws IllegalArgumentException if the threshold or maximum is outside 0 through 255 or is NaN
   * @since 1.0.0
   */
  public ThresholdFilter(final double threshold, final double maximumValue, final int type) {
    Preconditions.checkArgument(threshold >= 0 && threshold <= CHANNEL_MAXIMUM, "Threshold must be between 0 and 255 inclusive!");
    Preconditions.checkArgument(maximumValue >= 0 && maximumValue <= CHANNEL_MAXIMUM, "Max value must be between 0 and 255 inclusive!");

    this.threshold = threshold;
    this.maximumValue = maximumValue;
    this.type = type;
  }

  /**
   * Applies the threshold to every channel of the frame in place.
   *
   * @param matrix the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   * @since 1.0.0
   */
  @Override
  protected boolean modifyMat(final Mat matrix) {
    opencv_imgproc.threshold(matrix, matrix, this.threshold, this.maximumValue, this.type);

    return true;
  }
}
