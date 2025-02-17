/*
 * This file is part of mcav, a media playback library for Minecraft
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

import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;

public class ThresholdFilter extends MatVideoFilter {

  private final double thresh;
  private final double maxVal;
  private final int type;

  public ThresholdFilter(final double thresh, final double maxVal, final int type) {
    this.thresh = thresh;
    this.maxVal = maxVal;
    this.type = type;
  }

  @Override
  void modifyMat(final Mat mat) {
    opencv_imgproc.threshold(mat, mat, this.thresh, this.maxVal, this.type);
  }
}
