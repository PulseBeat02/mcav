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
 * Draws a straight line onto every frame.
 */
public class LineFilter extends MatVideoFilter {

  private final Point start;
  private final Point end;
  private final Scalar color;

  /**
   * Constructs a new line filter.
   *
   * @param startX the x coordinate of the start of the line
   * @param startY the y coordinate of the start of the line
   * @param endX   the x coordinate of the end of the line
   * @param endY   the y coordinate of the end of the line
   * @param color  the blue, green, and red components of the color, from 0 to 255
   */
  public LineFilter(final int startX, final int startY, final int endX, final int endY, final double[] color) {
    this.start = new Point(startX, startY);
    this.end = new Point(endX, endY);
    this.color = ImageUtils.toScalar(color);
  }

  /**
   * Draws the line onto the frame in place. Parts of the line outside of the frame are cut off.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    opencv_imgproc.line(mat, this.start, this.end, this.color);
    return true;
  }
}
