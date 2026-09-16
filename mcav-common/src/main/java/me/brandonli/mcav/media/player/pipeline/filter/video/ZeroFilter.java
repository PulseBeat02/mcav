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

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Turns every frame black.
 */
public class ZeroFilter extends MatVideoFilter {

  private static final Scalar BLACK = new Scalar(0, 0, 0, 0);

  /**
   * Constructs a new zero filter.
   */
  public ZeroFilter() {
    // stateless
  }

  /**
   * Sets every pixel of the frame to black in place.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have had other colors before
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    mat.put(BLACK);
    return true;
  }
}
