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

public class TextFilter extends MatVideoFilter {

  private final String text;
  private final int fontFace;
  private final double fontScale;
  private final int thickness;
  private final Point position;
  private final Scalar color;

  public TextFilter(
    final String text,
    final int x,
    final int y,
    final int fontFace,
    final double fontScale,
    final double[] scalarColor,
    final int thickness
  ) {
    this.text = text;
    this.fontFace = fontFace;
    this.fontScale = fontScale;
    this.thickness = thickness;
    this.position = new Point(x, y);
    this.color = new Scalar(scalarColor);
  }

  @Override
  void modifyMat(final Mat mat) {
    Imgproc.putText(mat, this.text, this.position, this.fontFace, this.fontScale, this.color, this.thickness);
  }
}
