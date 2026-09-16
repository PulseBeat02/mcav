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
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Tints frames by blending them with a solid color.
 *
 * <p>The matrix of the tint color is kept between frames and only rebuilt when a frame has another size, so tinting
 * allocates nothing per frame. One filter may be attached to several pipelines at once: the matrix is lent to one
 * frame at a time, see {@link ReusableMat}, and freed when the filter is garbage collected.
 */
public class TintFilter extends MatVideoFilter {

  private final Scalar color;
  private final double strength;
  private final ReusableMat tint;

  /**
   * Constructs a new tint filter.
   *
   * @param color    the blue, green, and red components of the tint color, from 0 to 255
   * @param strength how strongly the color is applied, from 0 for no tint to 1 for a solid color
   */
  public TintFilter(final double[] color, final double strength) {
    Preconditions.checkArgument(strength >= 0 && strength <= 1, "Strength must be between 0 and 1");
    this.color = ImageUtils.toScalar(color);
    this.strength = strength;
    this.tint = new ReusableMat();
  }

  /**
   * Blends the frame in place with a matrix of the tint color that is reused from frame to frame.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    final double frameWeight = 1.0 - this.strength;
    final Mat solid = this.tint.borrow();
    try {
      this.fillLike(solid, mat);
      opencv_core.addWeighted(mat, frameWeight, solid, this.strength, 0.0, mat);
    } finally {
      this.tint.giveBack(solid);
    }
    return true;
  }

  /**
   * Makes a matrix of the tint color with the size and type of the frame. A matrix that already has them holds the
   * color from an earlier frame, because nothing else writes into it, and is left untouched.
   */
  private void fillLike(final Mat solid, final Mat frame) {
    final int rows = frame.rows();
    final int columns = frame.cols();
    final int type = frame.type();
    final boolean matches = solid.rows() == rows && solid.cols() == columns && solid.type() == type;
    if (matches) {
      return;
    }
    solid.create(rows, columns, type);
    solid.put(this.color);
  }
}
