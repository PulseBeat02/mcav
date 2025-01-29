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

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

/**
 * A filter that crops a video frame to a specified rectangle.
 */
public class CropFilter extends MatVideoFilter {

  private final Rect rect;

  /**
   * Constructs a CropFilter with the specified rectangle.
   *
   * @param x      The x-coordinate of the top-left corner of the crop rectangle.
   * @param y      The y-coordinate of the top-left corner of the crop rectangle.
   * @param width  The width of the crop rectangle.
   * @param height The height of the crop rectangle.
   */
  public CropFilter(final int x, final int y, final int width, final int height) {
    this.rect = new Rect(x, y, width, height);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  void modifyMat(final Mat mat) {
    final Mat croppedMat = new Mat(mat, this.rect);
    croppedMat.copyTo(mat);
    croppedMat.release();
  }
}
