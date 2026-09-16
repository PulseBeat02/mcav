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

import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Converts frames to grayscale. The frame keeps its three channels, so it stays compatible with every other filter.
 *
 * <p>The single-channel matrix the gray levels pass through is kept between frames, so converting allocates nothing
 * per frame. One filter may be attached to several pipelines at once: the matrix is lent to one frame at a time, see
 * {@link ReusableMat}, and freed when the filter is garbage collected.
 */
public class GrayscaleFilter extends MatVideoFilter {

  private final ReusableMat gray;

  /**
   * Constructs a new grayscale filter.
   */
  public GrayscaleFilter() {
    this.gray = new ReusableMat();
  }

  /**
   * Converts the frame to gray through a reused single-channel matrix and writes the gray level back to all three
   * channels.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have had colors before
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    final Mat levels = this.gray.borrow();
    try {
      opencv_imgproc.cvtColor(mat, levels, opencv_imgproc.COLOR_BGR2GRAY);
      opencv_imgproc.cvtColor(levels, mat, opencv_imgproc.COLOR_GRAY2BGR);
    } finally {
      this.gray.giveBack(levels);
    }
    return true;
  }
}
