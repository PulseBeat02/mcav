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
 * Applies a fixed threshold to every channel of every frame, for example to turn frames into pure black and
 * white with {@link opencv_imgproc#THRESH_BINARY}.
 */
public class ThresholdFilter extends MatVideoFilter {

  private final double threshold;
  private final double maxValue;
  private final int type;

  /**
   * Constructs a new threshold filter.
   *
   * @param threshold the threshold value, from 0 to 255
   * @param maxValue  the value assigned to channels that pass the threshold, from 0 to 255
   * @param type      the threshold type, one of the {@code THRESH_} constants of {@link opencv_imgproc}
   */
  public ThresholdFilter(final double threshold, final double maxValue, final int type) {
    Preconditions.checkArgument(threshold >= 0 && threshold <= 255, "Threshold must be between 0 and 255");
    Preconditions.checkArgument(maxValue >= 0 && maxValue <= 255, "Max value must be between 0 and 255");
    this.threshold = threshold;
    this.maxValue = maxValue;
    this.type = type;
  }

  /**
   * Applies the threshold to every channel of the frame in place.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    opencv_imgproc.threshold(mat, mat, this.threshold, this.maxValue, this.type);
    return true;
  }
}
