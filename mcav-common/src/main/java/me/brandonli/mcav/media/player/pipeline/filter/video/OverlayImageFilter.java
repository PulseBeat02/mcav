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
import me.brandonli.mcav.media.image.ImageBuffer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

/**
 * Draws a fixed image on top of every frame at a fixed position. Parts of the image that extend past the edge of
 * the frame are cut off. The image is copied when the filter is created, so it may be released afterward.
 */
public class OverlayImageFilter extends MatVideoFilter {

  private final Mat overlay;
  private final int x;
  private final int y;

  /**
   * Constructs a new overlay filter.
   *
   * @param overlay the image to draw
   * @param x       the x coordinate of the top left corner of the image inside the frame
   * @param y       the y coordinate of the top left corner of the image inside the frame
   */
  public OverlayImageFilter(final ImageBuffer overlay, final int x, final int y) {
    Preconditions.checkNotNull(overlay, "Overlay must not be null");
    Preconditions.checkArgument(x >= 0 && y >= 0, "Overlay position must not be negative");
    this.overlay = copyToMat(overlay);
    this.x = x;
    this.y = y;
  }

  /**
   * Copies the visible part of the image into the frame in place.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true if part of the image was drawn, false if the position lies outside of the frame and the frame was
   *     left untouched
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    final int overlayWidth = this.overlay.cols();
    final int overlayHeight = this.overlay.rows();
    final int frameWidth = mat.cols();
    final int frameHeight = mat.rows();
    final int width = Math.min(overlayWidth, frameWidth - this.x);
    final int height = Math.min(overlayHeight, frameHeight - this.y);
    if (width <= 0 || height <= 0) {
      return false;
    }
    try (
      final Rect targetRect = new Rect(this.x, this.y, width, height);
      final Rect sourceRect = new Rect(0, 0, width, height);
      final Mat target = new Mat(mat, targetRect);
      final Mat source = new Mat(this.overlay, sourceRect)
    ) {
      source.copyTo(target);
    }
    return true;
  }
}
