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
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * A video filter that applies a tint effect to the video frames.
 */
public class TintFilter extends MatVideoFilter {

  private final Scalar tintColor;
  private final double alpha;

  /**
   * Constructs a new TintFilter with the specified tint color and alpha value.
   *
   * @param tintColor an array of doubles representing the RGB tint color, where each value is in the range [0, 255].
   * @param alpha     a double representing the alpha blending factor, where 0.0 is no tint and 1.0 is full tint.
   */
  public TintFilter(final double[] tintColor, final double alpha) {
    this.tintColor = ImageUtils.toScalar(tintColor);
    this.alpha = alpha;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  void modifyMat(final Mat mat) {
    final Mat tintedMat = new Mat(mat.size(), mat.type(), this.tintColor);
    opencv_core.addWeighted(mat, 1.0 - this.alpha, tintedMat, this.alpha, 0.0, mat);
    tintedMat.release();
  }
}
