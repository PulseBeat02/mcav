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
import org.bytedeco.opencv.opencv_core.Size;

/**
 * A filter that resizes video frames to a specified width and height.
 */
public class ResizeFilter extends MatVideoFilter {

  private final Size newSize;

  /**
   * Constructs a ResizeFilter with the specified width and height.
   *
   * @param width  the new width of the video frames
   * @param height the new height of the video frames
   */
  public ResizeFilter(final int width, final int height) {
    this.newSize = new Size(width, height);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  void modifyMat(final Mat mat) {
    final Mat resizedMat = new Mat();
    opencv_imgproc.resize(mat, resizedMat, this.newSize);
    resizedMat.copyTo(mat);
    resizedMat.release();
  }
}
