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
import me.brandonli.mcav.utils.opencv.ImageUtils;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Draws a rectangle outline onto every frame.
 */
public class RectangleFilter extends MatVideoFilter {

  private final Point topLeft;
  private final Point bottomRight;
  private final Scalar color;

  /**
   * Constructs a new rectangle filter.
   *
   * @param x      the x coordinate of the top left corner
   * @param y      the y coordinate of the top left corner
   * @param width  the width of the rectangle, which must be positive
   * @param height the height of the rectangle, which must be positive
   * @param color  the blue, green, and red components of the color, from 0 to 255
   */
  public RectangleFilter(final int x, final int y, final int width, final int height, final double[] color) {
    Preconditions.checkArgument(width > 0 && height > 0, "Rectangle size must be positive");
    this.topLeft = new Point(x, y);
    // OpenCV draws both corners, so the opposite corner is the last pixel inside the rectangle
    this.bottomRight = new Point(x + width - 1, y + height - 1);
    this.color = ImageUtils.toScalar(color);
  }

  /**
   * Draws the rectangle outline onto the frame in place. Parts of the rectangle outside of the frame are cut off.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    opencv_imgproc.rectangle(mat, this.topLeft, this.bottomRight, this.color);
    return true;
  }
}
