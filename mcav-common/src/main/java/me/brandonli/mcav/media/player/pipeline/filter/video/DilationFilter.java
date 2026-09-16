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
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Grows the bright regions of frames with a rectangular structuring element, which thickens light features and
 * closes small dark gaps.
 */
public class DilationFilter extends MatVideoFilter {

  private final Mat kernel;

  /**
   * Constructs a new dilation filter.
   *
   * @param kernelSize the size of the structuring element in pixels, which must be positive
   */
  public DilationFilter(final int kernelSize) {
    Preconditions.checkArgument(kernelSize > 0, "Kernel size must be positive");
    final Size size = new Size(kernelSize, kernelSize);
    this.kernel = opencv_imgproc.getStructuringElement(opencv_imgproc.MORPH_RECT, size);
  }

  /**
   * Dilates the frame in place with the structuring element.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    opencv_imgproc.dilate(mat, mat, this.kernel);
    return true;
  }
}
