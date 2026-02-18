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
 * A video filter that applies rotation to video frames.
 */
public class RotationFilter extends MatVideoFilter {

  private final int code;

  /**
   * Constructs a RotationFilter with the specified rotation.
   *
   * @param rotation The rotation to apply to the video frames.
   */
  public RotationFilter(final Rotation rotation) {
    this.code = rotation.code;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  boolean modifyMat(final Mat mat) {
    opencv_core.rotate(mat, mat, this.code);
    return true;
  }

  /**
   * Enum representing the different rotation options available for video frames.
   */
  public enum Rotation {
    /**
     * 90 degrees clockwise rotation.
     */
    ROTATE_90_CLOCKWISE(opencv_core.ROTATE_90_CLOCKWISE),

    /**
     * 180 degrees rotation.
     */
    ROTATE_180(opencv_core.ROTATE_180),

    /**
     * 90 degrees counterclockwise rotation.
     */
    ROTATE_90_COUNTERCLOCKWISE(opencv_core.ROTATE_90_COUNTERCLOCKWISE);

    private final int code;

    Rotation(final int code) {
      this.code = code;
    }
  }
}
