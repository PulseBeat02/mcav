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

import me.brandonli.mcav.media.image.MatImageBuffer;
import org.opencv.core.Core;
import org.opencv.core.Mat;

public class BlendFilter extends MatVideoFilter {

  private final double alpha;
  private final Mat otherMat;

  public BlendFilter(final MatImageBuffer other, final double alpha) {
    this.alpha = alpha;
    this.otherMat = other.getOrThrow(MatImageBuffer.MAT_PROPERTY);
  }

  @Override
  void modifyMat(final Mat mat) {
    if (mat.size().equals(this.otherMat.size())) {
      Core.addWeighted(mat, this.alpha, this.otherMat, 1.0 - this.alpha, 0.0, mat);
    }
  }
}
