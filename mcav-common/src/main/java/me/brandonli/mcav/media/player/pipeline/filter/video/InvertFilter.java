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

import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Inverts the colors of frames, turning every color into its negative.
 */
public class InvertFilter extends MatVideoFilter {

  /**
   * Constructs a new invert filter.
   */
  public InvertFilter() {
    // stateless
  }

  /**
   * Inverts every channel of the frame in place.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because every frame is changed
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    opencv_core.bitwise_not(mat, mat);
    return true;
  }
}
