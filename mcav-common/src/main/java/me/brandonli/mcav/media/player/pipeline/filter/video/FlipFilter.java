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
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class FlipFilter extends MatVideoFilter {

  private final int flipCode;

  /**
   * Constructs a new flip filter.
   *
   * @param direction the direction to mirror in
   * @throws NullPointerException if {@code direction} is null
   * @since 1.0.0
   */
  public FlipFilter(final FlipDirection direction) {
    Preconditions.checkNotNull(direction, "Direction must not be null!");

    this.flipCode = direction.getCode();
  }

  /**
   * Mirrors the frame in place in the direction of this filter.
   *
   * @param matrix the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   * @since 1.0.0
   */
  @Override
  protected boolean modifyMat(final Mat matrix) {
    opencv_core.flip(matrix, matrix, this.flipCode);

    return true;
  }

  /**
   * The directions {@link FlipFilter} can mirror in.
   *
   * <p>This enum is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public enum FlipDirection {
    /**
     * Mirrors left and right.
     *
     * @since 1.0.0
     */
    HORIZONTAL(1),

    /**
     * Mirrors top and bottom.
     *
     * @since 1.0.0
     */
    VERTICAL(0),

    /**
     * Mirrors in both directions, which equals a rotation by 180 degrees.
     *
     * @since 1.0.0
     */
    BOTH(-1);

    private final int code;

    FlipDirection(final int code) {
      this.code = code;
    }

    /**
     * Returns the OpenCV flip code of this direction.
     *
     * @return the flip code
     * @since 1.0.0
     */
    public int getCode() {
      return this.code;
    }
  }
}
