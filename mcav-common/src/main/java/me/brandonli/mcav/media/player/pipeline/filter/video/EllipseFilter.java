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

import me.brandonli.mcav.utils.opencv.ImageUtils;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

public class EllipseFilter extends MatVideoFilter {

  private final int angle;
  private final int startAngle;
  private final int endAngle;
  private final Point center;
  private final Size axes;
  private final Scalar color;

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

  @Override
  void modifyMat(final Mat mat) {
    opencv_imgproc.ellipse(mat, this.center, this.axes, this.angle, this.startAngle, this.endAngle, this.color);
  }
}
