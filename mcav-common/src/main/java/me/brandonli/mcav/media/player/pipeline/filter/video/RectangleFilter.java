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

public class RectangleFilter extends MatVideoFilter {

  private final Point topLeft;
  private final Point bottomRight;
  private final Scalar color;

  public RectangleFilter(final int x, final int y, final int width, final int height, final double[] colorScalar) {
    this.topLeft = new Point(x, y);
    this.bottomRight = new Point(x + width, y + height);
    this.color = ImageUtils.toScalar(colorScalar);
  }

  @Override
  void modifyMat(final Mat mat) {
    opencv_imgproc.rectangle(mat, this.topLeft, this.bottomRight, this.color);
  }
}
