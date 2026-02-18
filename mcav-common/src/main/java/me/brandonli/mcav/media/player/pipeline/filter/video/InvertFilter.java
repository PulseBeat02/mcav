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
 * A video filter that inverts the colors of the video frames.
 */
public class InvertFilter extends MatVideoFilter {

  /**
   * Constructs a new InvertFilter instance.
   */
  public InvertFilter() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  boolean modifyMat(final Mat mat) {
    opencv_core.bitwise_not(mat, mat);
    return true;
  }
}
