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

import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * A video filter that applies a bilateral filter to smooth the image while preserving edges.
 */
public class BilateralFilter extends MatVideoFilter {

  private final int diameter;
  private final double sigmaColor;
  private final double sigmaSpace;

  /**
   * Constructs a BilateralFilter with the specified parameters.
   *
   * @param diameter    the diameter of the pixel neighborhood used during filtering
   * @param sigmaColor  the filter sigma in color space; a larger value means that farther colors will
   *                    be mixed together, resulting in larger areas of semi-equal color
   * @param sigmaSpace  the filter sigma in coordinate space; a larger value means that farther pixels
   *                    will influence each other as long as their colors are close enough
   */
  public BilateralFilter(final int diameter, final double sigmaColor, final double sigmaSpace) {
    this.diameter = diameter;
    this.sigmaColor = sigmaColor;
    this.sigmaSpace = sigmaSpace;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  boolean modifyMat(final Mat mat) {
    opencv_imgproc.bilateralFilter(mat, mat, this.diameter, this.sigmaColor, this.sigmaSpace);
    return true;
  }
}
