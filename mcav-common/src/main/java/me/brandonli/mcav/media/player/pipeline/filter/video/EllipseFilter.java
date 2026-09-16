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
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Draws an ellipse outline, or an arc of one, onto every frame.
 */
public class EllipseFilter extends MatVideoFilter {

  private final Point center;
  private final Size axes;
  private final int angle;
  private final int startAngle;
  private final int endAngle;
  private final Scalar color;

  /**
   * Constructs a new ellipse filter.
   *
   * @param centerX    the x coordinate of the center
   * @param centerY    the y coordinate of the center
   * @param axisX      half of the width of the ellipse, which must be positive
   * @param axisY      half of the height of the ellipse, which must be positive
   * @param angle      the rotation of the ellipse in degrees
   * @param startAngle the angle in degrees the arc starts at; 0 and 360 draw the whole ellipse
   * @param endAngle   the angle in degrees the arc ends at
   * @param color      the blue, green, and red components of the color, from 0 to 255
   */
  public EllipseFilter(
    final int centerX,
    final int centerY,
    final int axisX,
    final int axisY,
    final int angle,
    final int startAngle,
    final int endAngle,
    final double[] color
  ) {
    Preconditions.checkArgument(axisX > 0 && axisY > 0, "Axes must be positive");
    this.center = new Point(centerX, centerY);
    this.axes = new Size(axisX, axisY);
    this.angle = angle;
    this.startAngle = startAngle;
    this.endAngle = endAngle;
    this.color = ImageUtils.toScalar(color);
  }

  /**
   * Draws the ellipse outline, or its arc, onto the frame in place. Parts of the ellipse outside of the frame are cut
   * off.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    opencv_imgproc.ellipse(mat, this.center, this.axes, this.angle, this.startAngle, this.endAngle, this.color);
    return true;
  }
}
