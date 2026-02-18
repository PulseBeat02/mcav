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
 * A video filter that modifies a specified rectangular region of a video frame to a given scalar value.
 */
public class RegionScalarFilter extends MatVideoFilter {

  private final int x;
  private final int y;
  private final int width;
  private final int height;
  private final Mat scalar;

  /**
   * Constructs a RegionScalarFilter with the specified region and scalar value.
   *
   * @param x           the x-coordinate of the top-left corner of the region
   * @param y           the y-coordinate of the top-left corner of the region
   * @param width       the width of the region
   * @param height      the height of the region
   * @param scalarValue an array representing the scalar value to set in the region (should be of length 3 for RGB)
   */
  public RegionScalarFilter(final int x, final int y, final int width, final int height, final double[] scalarValue) {
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
    this.scalar = new Mat(1, 3, opencv_core.CV_8U);
    final byte[] byteValues = new byte[scalarValue.length];
    for (int i = 0; i < scalarValue.length; i++) {
      byteValues[i] = (byte) scalarValue[i];
    }
    this.scalar.data().put(byteValues);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  boolean modifyMat(final Mat mat) {
    final Mat submat = mat.adjustROI(this.x, this.y, this.width, this.height);
    submat.setTo(this.scalar);
    return true;
  }
}
