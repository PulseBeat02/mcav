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
 * A filter that flips video frames in a specified direction.
 */
public class FlipFilter extends MatVideoFilter {

  private final int flipCode;

  /**
   * Constructs a FlipFilter with the specified flip direction.
   *
   * @param direction the direction to flip the video frames
   */
  public FlipFilter(final FlipDirection direction) {
    this.flipCode = direction.code;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  void modifyMat(final Mat mat) {
    opencv_core.flip(mat, mat, this.flipCode);
  }

  /**
   * Enum representing the possible flip directions for the video frames.
   */
  public enum FlipDirection {
    /**
     * Horizontal flip.
     */
    HORIZONTAL(1),

    /**
     * Vertical flip.
     */
    VERTICAL(0),

    /**
     * Both horizontal and vertical flip.
     */
    BOTH(-1);

    private final int code;

    FlipDirection(final int code) {
      this.code = code;
    }
  }
}
