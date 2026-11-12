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
 * <p>The matrix of the tint color is kept between frames and only rebuilt when a frame has another size or type, so
 * tinting reuses native image storage in the usual serial case with stable dimensions, and it is freed when the filter
 * is garbage collected. A call that finds the matrix lent to another frame fills one of its own, so one filter may be
 * attached to several pipelines at once.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class TintFilter extends MatVideoFilter {

  private final Scalar color;
  private final double strength;
  private final ReusableMat tint;

  /**
   * Creates a new tint filter.
   *
   * @param color    the blue, green, and red components of the tint color, normally 0 to 255; the non-null array is copied
   * @param strength how strongly the color is applied, from 0 for no tint to 1 for a solid color
   * @throws IllegalArgumentException if {@code strength} is outside 0 through 1 or is NaN
   * @throws NullPointerException     if {@code color} is null
   * @since 1.0.0
   */
  public TintFilter(final double[] color, final double strength) {
    Preconditions.checkArgument(strength >= 0 && strength <= 1, "Strength must be between 0 and 1 inclusive!");

    this.color = ImageUtils.toScalar(color);
    this.strength = strength;
    this.tint = new ReusableMat();
  }

  /**
   * Blends the frame in place with a matrix of the tint color that is reused from frame to frame.
   *
   * @param matrix the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   * @since 1.0.0
   */
  @Override
  protected boolean modifyMat(final Mat matrix) {
    final double frameWeight = 1.0 - this.strength;
    final Mat solid = this.tint.borrow();
    try {
      this.fillLike(solid, matrix);
      opencv_core.addWeighted(matrix, frameWeight, solid, this.strength, 0.0, matrix);
    } finally {
      this.tint.giveBack(solid);
    }

    return true;
  }

  // A matrix that already has the frame's size and type still holds the color, as nothing else writes into it, and is left alone.
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
