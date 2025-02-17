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

public class FlipFilter extends MatVideoFilter {

  private final int flipCode;

  public FlipFilter(final FlipDirection direction) {
    this.flipCode = direction.code;
  }

  @Override
  void modifyMat(final Mat mat) {
    final Mat flippedMat = new Mat();
    opencv_core.flip(mat, flippedMat, this.flipCode);
    flippedMat.copyTo(mat);
    flippedMat.release();
  }

  public enum FlipDirection {
    HORIZONTAL(1),
    VERTICAL(0),
    BOTH(-1);

    private final int code;

    FlipDirection(final int code) {
      this.code = code;
    }
  }
}
