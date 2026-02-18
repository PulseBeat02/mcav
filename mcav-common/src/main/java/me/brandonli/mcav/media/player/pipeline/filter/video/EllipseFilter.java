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
import org.bytedeco.opencv.opencv_core.Size;

/**
 * A filter that draws an ellipse on a video frame.
 */
public class EllipseFilter extends MatVideoFilter {

  private final int angle;
  private final int startAngle;
  private final int endAngle;
  private final Point center;
  private final Size axes;
  private final Scalar color;

  /**
   * Constructs an EllipseFilter with the specified parameters.
   *
   * @param centerX     The x-coordinate of the center of the ellipse.
   * @param centerY     The y-coordinate of the center of the ellipse.
   * @param axisX       The length of the semi-major axis of the ellipse.
   * @param axisY       The length of the semi-minor axis of the ellipse.
   * @param angle       The rotation angle of the ellipse in degrees.
   * @param startAngle  The starting angle of the elliptic arc in degrees.
   * @param endAngle    The ending angle of the elliptic arc in degrees.
   * @param colorScalar An array representing the color to draw the ellipse, in BGR format.
   */
  public EllipseFilter(
    final int centerX,
    final int centerY,
    final int axisX,
    final int axisY,
    final int angle,
    final int startAngle,
    final int endAngle,
    final double[] colorScalar
  ) {
    this.angle = angle;
    this.startAngle = startAngle;
    this.endAngle = endAngle;
    this.center = new Point(centerX, centerY);
    this.axes = new Size(axisX, axisY);
    this.color = ImageUtils.toScalar(colorScalar);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  boolean modifyMat(final Mat mat) {
    opencv_imgproc.ellipse(mat, this.center, this.axes, this.angle, this.startAngle, this.endAngle, this.color);
    return true;
  }
}
