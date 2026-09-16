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
 * Transposes frames, swapping their rows and columns. This mirrors the frame along its diagonal and swaps the
 * width and the height.
 *
 * <p>The result is written into the spare matrix of the frame, see
 * {@link MatImageBuffer#transformMat(java.util.function.BiConsumer)}, so no matrix is allocated per frame once the
 * sizes are settled. The filter keeps no state, so one filter may be attached to several pipelines at once.
 */
public class TransposeFilter extends MatVideoFilter {

  /**
   * Constructs a new transpose filter.
   */
  public TransposeFilter() {
    // stateless
  }

  /**
   * Transposes the frame into its spare matrix, which then becomes the matrix of the frame with the width and the
   * height swapped.
   *
   * @param image the frame to transpose
   * @return true, because every frame is changed
   */
  @Override
  protected boolean modifyImage(final MatImageBuffer image) {
    image.transformMat(opencv_core::transpose);
    return true;
  }

  /**
   * Always throws, because a transposition changes the size of the frame and cannot be done in place;
   * {@link #modifyImage(MatImageBuffer)} transposes instead.
   *
   * @param mat the matrix of the frame
   * @return never returns normally
   * @throws UnsupportedOperationException always
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    throw new UnsupportedOperationException("Transposing cannot be done in place");
  }
}
