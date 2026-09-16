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
import me.brandonli.mcav.media.image.ImageBuffer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Blends every frame with a fixed image of the same size. Frames whose size differs from the image are left
 * untouched. The image is copied when the filter is created, so it may be released afterward.
 */
public class BlendFilter extends MatVideoFilter {

  private final double alpha;
  private final Mat other;

  /**
   * Constructs a new blend filter.
   *
   * @param other the image to blend with
   * @param alpha the weight of the frame from 0 to 1; the image gets the remaining weight
   */
  public BlendFilter(final ImageBuffer other, final double alpha) {
    Preconditions.checkNotNull(other, "Other image must not be null");
    Preconditions.checkArgument(alpha >= 0 && alpha <= 1, "Alpha must be between 0 and 1");
    this.alpha = alpha;
    this.other = copyToMat(other);
  }

  /**
   * Blends the frame in place with the image, if both have the same size.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true if the frame was blended, false if its size differs from the image and it was left untouched
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    final int frameWidth = mat.cols();
    final int frameHeight = mat.rows();
    final int otherWidth = this.other.cols();
    final int otherHeight = this.other.rows();
    final boolean sameSize = frameWidth == otherWidth && frameHeight == otherHeight;
    if (!sameSize) {
      return false;
    }
    final double otherWeight = 1.0 - this.alpha;
    opencv_core.addWeighted(mat, this.alpha, this.other, otherWeight, 0.0, mat);
    return true;
  }
}
