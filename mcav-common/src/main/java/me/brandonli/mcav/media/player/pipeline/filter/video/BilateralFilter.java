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
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Smooths frames while keeping edges sharp, using a bilateral filter. Bilateral filtering is expensive; a
 * diameter of 5 to 9 is a reasonable range for real-time video.
 *
 * <p>OpenCV cannot filter bilaterally in place, so the result is written into the spare matrix of the frame, see
 * {@link MatImageBuffer#transformMat(java.util.function.BiConsumer)}, which allocates and copies nothing per frame. The
 * filter keeps no state of its own, so one filter may be attached to several pipelines at once.
 */
public class BilateralFilter extends MatVideoFilter {

  private final int diameter;
  private final double sigmaColor;
  private final double sigmaSpace;

  /**
   * Constructs a new bilateral filter.
   *
   * @param diameter   the diameter of the pixel neighborhood used for filtering, which must be positive
   * @param sigmaColor how far apart colors may be to still be mixed; larger values blur more
   * @param sigmaSpace how far apart pixels may be to still influence each other; larger values blur more
   */
  public BilateralFilter(final int diameter, final double sigmaColor, final double sigmaSpace) {
    Preconditions.checkArgument(diameter > 0, "Diameter must be positive");
    Preconditions.checkArgument(sigmaColor > 0, "Sigma color must be positive");
    Preconditions.checkArgument(sigmaSpace > 0, "Sigma space must be positive");
    this.diameter = diameter;
    this.sigmaColor = sigmaColor;
    this.sigmaSpace = sigmaSpace;
  }

  /**
   * Smooths the frame into its spare matrix, which then becomes the matrix of the frame.
   *
   * @param image the frame to smooth
   * @return true, because the frame may have changed
   */
  @Override
  protected boolean modifyImage(final MatImageBuffer image) {
    image.transformMat(this::smooth);
    return true;
  }

  /**
   * Smooths a matrix in place through a temporary matrix, for subclasses and callers that only have a matrix; frames
   * go through {@link #modifyImage(MatImageBuffer)} instead, which needs no temporary matrix.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    try (final Mat filtered = new Mat()) {
      this.smooth(mat, filtered);
      filtered.copyTo(mat);
    }
    return true;
  }

  private void smooth(final Mat source, final Mat target) {
    opencv_imgproc.bilateralFilter(source, target, this.diameter, this.sigmaColor, this.sigmaSpace);
  }
}
