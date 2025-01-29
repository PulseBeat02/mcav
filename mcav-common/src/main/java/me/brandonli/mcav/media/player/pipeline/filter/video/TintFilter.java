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

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

public class TintFilter extends MatVideoFilter {

  private final Scalar tintColor;
  private final double alpha;

  public TintFilter(final double[] tintColor, final double alpha) {
    this.tintColor = new Scalar(tintColor);
    this.alpha = alpha;
  }

  @Override
  void modifyMat(final Mat mat) {
    final Mat tintedMat = new Mat(mat.size(), mat.type(), this.tintColor);
    Core.addWeighted(mat, 1.0 - this.alpha, tintedMat, this.alpha, 0.0, mat);
    tintedMat.release();
  }
}
