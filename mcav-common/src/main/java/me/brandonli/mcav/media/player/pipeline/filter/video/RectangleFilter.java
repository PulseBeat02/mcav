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

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

public class RectangleFilter extends MatVideoFilter {

  private final int thickness;
  private final Point topLeft;
  private final Point bottomRight;
  private final Scalar color;

  public RectangleFilter(final int x, final int y, final int width, final int height, final double[] colorScalar, final int thickness) {
    this.thickness = thickness;
    this.topLeft = new Point(x, y);
    this.bottomRight = new Point((double) x + width, (double) y + height);
    this.color = new Scalar(colorScalar);
  }

  @Override
  void modifyMat(final Mat mat) {
    Imgproc.rectangle(mat, this.topLeft, this.bottomRight, this.color, this.thickness);
  }
}
