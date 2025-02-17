/*
 * This file is part of mcav, a media playback library for Minecraft
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

public class RotationFilter extends MatVideoFilter {

  private final int code;

  public RotationFilter(final Rotation rotation) {
    this.code = rotation.code;
  }

  @Override
  void modifyMat(final Mat mat) {
    final Mat rotatedMat = new Mat();
    opencv_core.rotate(mat, rotatedMat, this.code);
    rotatedMat.copyTo(mat);
    rotatedMat.release();
  }

  public enum Rotation {
    ROTATE_90_CLOCKWISE(opencv_core.ROTATE_90_CLOCKWISE),
    ROTATE_180(opencv_core.ROTATE_180),
    ROTATE_90_COUNTERCLOCKWISE(opencv_core.ROTATE_90_COUNTERCLOCKWISE);

    private final int code;

    Rotation(final int code) {
      this.code = code;
    }
  }
}
