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
import com.google.common.primitives.Ints;
import me.brandonli.mcav.utils.opencv.ImageUtils;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Draws a rectangle outline onto every frame.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class RectangleFilter extends MatVideoFilter {

  private final Point topLeft;
  private final Point bottomRight;
  private final Scalar color;

  /**
   * Creates a new rectangle filter.
   *
   * @param left   the x coordinate of the top left corner
   * @param top    the y coordinate of the top left corner
   * @param width  the width of the rectangle, which must be positive
   * @param height the height of the rectangle, which must be positive
   * @param color  the blue, green, and red components of the color, normally 0 to 255; the non-null array is copied
   * @throws IllegalArgumentException if either size is not positive
   * @throws NullPointerException     if {@code color} is null
   * @since 1.0.0
   */
  public RectangleFilter(final int left, final int top, final int width, final int height, final double[] color) {
    Preconditions.checkArgument(width > 0 && height > 0, "Rectangle size must be positive!");

    this.topLeft = new Point(left, top);
    // OpenCV draws both corners, so the opposite corner is the last pixel inside the rectangle
    final long right = (long) left + width - 1;
    final long bottom = (long) top + height - 1;
    this.bottomRight = new Point(Ints.saturatedCast(right), Ints.saturatedCast(bottom));
    this.color = ImageUtils.toScalar(color);
  }

  /**
   * Draws the rectangle outline onto the frame in place. Parts of the rectangle outside of the frame are cut off.
   *
   * @param matrix the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   * @since 1.0.0
   */
  @Override
  protected boolean modifyMat(final Mat matrix) {
    opencv_imgproc.rectangle(matrix, this.topLeft, this.bottomRight, this.color);

    return true;
  }
}
