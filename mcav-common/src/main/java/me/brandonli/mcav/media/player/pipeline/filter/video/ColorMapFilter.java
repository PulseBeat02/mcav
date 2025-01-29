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

import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * A filter that applies a color map to a video frame.
 */
public class ColorMapFilter extends MatVideoFilter {

  private final int colorMapType;

  /**
   * Creates a new ColorMapFilter with the specified color map type.
   * @param colorMapType the type of color map to apply, such as
   */
  public ColorMapFilter(final int colorMapType) {
    this.colorMapType = colorMapType;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  void modifyMat(final Mat mat) {
    final Mat colored = new Mat();
    opencv_imgproc.applyColorMap(mat, colored, this.colorMapType);
    colored.copyTo(mat);
    colored.release();
  }
}
