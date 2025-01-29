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

import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

public class BilateralFilter extends MatVideoFilter {

  private final int diameter;
  private final double sigmaColor;
  private final double sigmaSpace;

  public BilateralFilter(final int diameter, final double sigmaColor, final double sigmaSpace) {
    this.diameter = diameter;
    this.sigmaColor = sigmaColor;
    this.sigmaSpace = sigmaSpace;
  }

  @Override
  void modifyMat(final Mat mat) {
    Imgproc.bilateralFilter(mat, mat, this.diameter, this.sigmaColor, this.sigmaSpace);
  }
}
