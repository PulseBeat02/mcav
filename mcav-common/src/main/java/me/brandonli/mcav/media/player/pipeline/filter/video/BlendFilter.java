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

import me.brandonli.mcav.media.image.MatImageBuffer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * A video filter that blends the current frame with another frame using a specified alpha value.
 */
public class BlendFilter extends MatVideoFilter {

  private final double alpha;
  private final Mat otherMat;

  /**
   * Constructs a BlendFilter that blends the current frame with another frame.
   *
   * @param other the MatImageBuffer containing the other frame to blend with
   * @param alpha the blending factor, where 0.0 means only the other frame is visible,
   *              and 1.0 means only the current frame is visible
   */
  public BlendFilter(final MatImageBuffer other, final double alpha) {
    this.alpha = alpha;
    this.otherMat = other.getOrThrow(MatImageBuffer.MAT_PROPERTY);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  void modifyMat(final Mat mat) {
    if (mat.size().equals(this.otherMat.size())) {
      opencv_core.addWeighted(mat, this.alpha, this.otherMat, 1.0 - this.alpha, 0.0, mat);
    }
  }
}
