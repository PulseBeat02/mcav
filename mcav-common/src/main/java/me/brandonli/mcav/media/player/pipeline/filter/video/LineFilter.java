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
 * A filter that draws a line on a video frame from a specified start point to an end point with a given color.
 */
public class LineFilter extends MatVideoFilter {

  private final Point start;
  private final Point end;
  private final Scalar color;

  /**
   * Constructs a LineFilter with specified start and end points and color.
   *
   * @param startX      the x-coordinate of the start point
   * @param startY      the y-coordinate of the start point
   * @param endX        the x-coordinate of the end point
   * @param endY        the y-coordinate of the end point
   * @param colorScalar an array representing the color in BGR format
   */
  public LineFilter(final int startX, final int startY, final int endX, final int endY, final double[] colorScalar) {
    this.start = new Point(startX, startY);
    this.end = new Point(endX, endY);
    this.color = ImageUtils.toScalar(colorScalar);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  void modifyMat(final Mat mat) {
    opencv_imgproc.line(mat, this.start, this.end, this.color);
  }
}
