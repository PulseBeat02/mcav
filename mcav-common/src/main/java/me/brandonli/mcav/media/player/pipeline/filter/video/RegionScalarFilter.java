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
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Fills a rectangle of every frame with a solid color. Parts of the rectangle that extend past the edge of the
 * frame are ignored.
 */
public class RegionScalarFilter extends MatVideoFilter {

  private final int x;
  private final int y;
  private final int width;
  private final int height;
  private final Scalar color;

  /**
   * Constructs a new region fill filter.
   *
   * @param x      the x coordinate of the top left corner
   * @param y      the y coordinate of the top left corner
   * @param width  the width of the rectangle, which must be positive
   * @param height the height of the rectangle, which must be positive
   * @param color  the blue, green, and red components of the color, from 0 to 255
   */
  public RegionScalarFilter(final int x, final int y, final int width, final int height, final double[] color) {
    Preconditions.checkArgument(x >= 0 && y >= 0, "Region origin must not be negative");
    Preconditions.checkArgument(width > 0 && height > 0, "Region size must be positive");
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
    this.color = ImageUtils.toScalar(color);
  }

  /**
   * Fills the part of the rectangle that lies inside the frame with the color, in place.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true if part of the rectangle was filled, false if the rectangle lies outside of the frame and the frame
   *     was left untouched
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    final int frameWidth = mat.cols();
    final int frameHeight = mat.rows();
    final int clampedWidth = Math.min(this.width, frameWidth - this.x);
    final int clampedHeight = Math.min(this.height, frameHeight - this.y);
    if (clampedWidth <= 0 || clampedHeight <= 0) {
      return false;
    }
    try (final Rect bounds = new Rect(this.x, this.y, clampedWidth, clampedHeight); final Mat region = new Mat(mat, bounds)) {
      region.put(this.color);
    }
    return true;
  }
}
