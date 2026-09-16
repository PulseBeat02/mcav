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
import me.brandonli.mcav.media.image.MatImageBuffer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Rotates frames by a multiple of 90 degrees. Rotating by 90 or 270 degrees swaps the width and the height.
 *
 * <p>The result is written into the spare matrix of the frame, see
 * {@link MatImageBuffer#transformMat(java.util.function.BiConsumer)}, so no matrix is allocated per frame once the
 * sizes are settled. The filter keeps no state besides its rotation, so one filter may be attached to several
 * pipelines at once.
 */
public class RotationFilter extends MatVideoFilter {

  private final int rotateCode;

  /**
   * Constructs a new rotation filter.
   *
   * @param rotation the rotation to apply
   */
  public RotationFilter(final Rotation rotation) {
    Preconditions.checkNotNull(rotation, "Rotation must not be null");
    this.rotateCode = rotation.getCode();
  }

  /**
   * Rotates the frame into its spare matrix, which then becomes the matrix of the frame.
   *
   * @param image the frame to rotate
   * @return true, because every frame is changed
   */
  @Override
  protected boolean modifyImage(final MatImageBuffer image) {
    image.transformMat(this::rotate);
    return true;
  }

  private void rotate(final Mat source, final Mat target) {
    opencv_core.rotate(source, target, this.rotateCode);
  }

  /**
   * Always throws, because a rotation by 90 degrees changes the size of the frame and cannot be done in place;
   * {@link #modifyImage(MatImageBuffer)} rotates instead.
   *
   * @param mat the matrix of the frame
   * @return never returns normally
   * @throws UnsupportedOperationException always
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    throw new UnsupportedOperationException("Rotation cannot be done in place");
  }

  /**
   * The rotations {@link RotationFilter} supports.
   */
  public enum Rotation {
    /**
     * Rotates by 90 degrees clockwise.
     */
    ROTATE_90_CLOCKWISE(opencv_core.ROTATE_90_CLOCKWISE),

    /**
     * Rotates by 180 degrees.
     */
    ROTATE_180(opencv_core.ROTATE_180),

    /**
     * Rotates by 90 degrees counterclockwise.
     */
    ROTATE_90_COUNTERCLOCKWISE(opencv_core.ROTATE_90_COUNTERCLOCKWISE);

    private final int code;

    Rotation(final int code) {
      this.code = code;
    }

    /**
     * Gets the OpenCV rotate code of this rotation.
     *
     * @return the rotate code
     */
    public int getCode() {
      return this.code;
    }
  }
}
