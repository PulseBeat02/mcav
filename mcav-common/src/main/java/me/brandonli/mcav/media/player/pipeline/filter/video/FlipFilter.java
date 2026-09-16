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
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Mirrors frames horizontally, vertically, or both.
 */
public class FlipFilter extends MatVideoFilter {

  private final int flipCode;

  /**
   * Constructs a new flip filter.
   *
   * @param direction the direction to mirror in
   */
  public FlipFilter(final FlipDirection direction) {
    Preconditions.checkNotNull(direction, "Direction must not be null");
    this.flipCode = direction.getCode();
  }

  /**
   * Mirrors the frame in place in the direction of this filter.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    opencv_core.flip(mat, mat, this.flipCode);
    return true;
  }

  /**
   * The directions {@link FlipFilter} can mirror in.
   */
  public enum FlipDirection {
    /**
     * Mirrors left and right.
     */
    HORIZONTAL(1),

    /**
     * Mirrors top and bottom.
     */
    VERTICAL(0),

    /**
     * Mirrors in both directions, which equals a rotation by 180 degrees.
     */
    BOTH(-1);

    private final int code;

    FlipDirection(final int code) {
      this.code = code;
    }

    /**
     * Gets the OpenCV flip code of this direction.
     *
     * @return the flip code
     */
    public int getCode() {
      return this.code;
    }
  }
}
