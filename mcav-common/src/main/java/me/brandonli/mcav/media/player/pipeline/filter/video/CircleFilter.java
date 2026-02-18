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

import me.brandonli.mcav.utils.opencv.ImageUtils;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * A filter that draws a circle on a video frame.
 */
public class CircleFilter extends MatVideoFilter {

  private final int radius;
  private final Point center;
  private final Scalar color;

  /**
   * Creates a new CircleFilter with the specified center, radius, and color.
   *
   * @param centerX the x-coordinate of the circle's center
   * @param centerY the y-coordinate of the circle's center
   * @param radius the radius of the circle
   * @param scalarColor the color of the circle in scalar format
   */
  public CircleFilter(final int centerX, final int centerY, final int radius, final double[] scalarColor) {
    this.radius = radius;
    this.center = new Point(centerX, centerY);
    this.color = ImageUtils.toScalar(scalarColor);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  boolean modifyMat(final Mat mat) {
    opencv_imgproc.circle(mat, this.center, this.radius, this.color);
    return true;
  }
}
