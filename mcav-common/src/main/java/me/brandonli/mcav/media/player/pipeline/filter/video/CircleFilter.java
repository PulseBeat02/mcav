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
 * Draws a circle outline onto every frame.
 */
public class CircleFilter extends MatVideoFilter {

  private final Point center;
  private final int radius;
  private final Scalar color;

  /**
   * Constructs a new circle filter.
   *
   * @param centerX the x coordinate of the center
   * @param centerY the y coordinate of the center
   * @param radius  the radius in pixels, which must not be negative; a radius of 0 draws a single pixel
   * @param color   the blue, green, and red components of the color, from 0 to 255
   */
  public CircleFilter(final int centerX, final int centerY, final int radius, final double[] color) {
    Preconditions.checkArgument(radius >= 0, "Radius must not be negative but was %s", radius);
    this.center = new Point(centerX, centerY);
    this.radius = radius;
    this.color = ImageUtils.toScalar(color);
  }

  /**
   * Draws the circle outline onto the frame in place. Parts of the circle outside of the frame are cut off.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    opencv_imgproc.circle(mat, this.center, this.radius, this.color);
    return true;
  }
}
