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
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

/**
 * Crops frames to a rectangle. The rectangle is clamped to the frame, so a rectangle that extends past the edge
 * of the frame crops to the visible part. Frames the rectangle does not touch at all are left untouched.
 *
 * <p>The visible part is copied into the spare matrix of the frame, see
 * {@link MatImageBuffer#transformMat(java.util.function.BiConsumer)}. A player that refills the frame at the size of
 * the video every frame gets the spare back, so cropping allocates no frame-sized matrix per frame once the sizes are
 * settled. The filter keeps no state besides its rectangle, so one filter may be attached to several pipelines at once.
 */
public class CropFilter extends MatVideoFilter {

  private final int x;
  private final int y;
  private final int width;
  private final int height;

  /**
   * Constructs a new crop filter.
   *
   * @param x      the x coordinate of the top left corner of the rectangle
   * @param y      the y coordinate of the top left corner of the rectangle
   * @param width  the width of the rectangle, which must be positive
   * @param height the height of the rectangle, which must be positive
   */
  public CropFilter(final int x, final int y, final int width, final int height) {
    Preconditions.checkArgument(x >= 0 && y >= 0, "Crop origin must not be negative");
    Preconditions.checkArgument(width > 0 && height > 0, "Crop size must be positive");
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
  }

  /**
   * Copies the part of the frame inside the rectangle into the spare matrix of the frame, which then becomes the
   * matrix of the frame.
   *
   * @param image the frame to crop
   * @return true if the frame was cropped, false if the rectangle lies outside of the frame or covers all of it and
   *     the frame was left untouched
   */
  @Override
  protected boolean modifyImage(final MatImageBuffer image) {
    final int frameWidth = image.getWidth();
    final int frameHeight = image.getHeight();
    final int clampedWidth = Math.min(this.width, frameWidth - this.x);
    final int clampedHeight = Math.min(this.height, frameHeight - this.y);
    if (clampedWidth <= 0 || clampedHeight <= 0) {
      return false;
    }
    final boolean wholeFrame = this.x == 0 && this.y == 0 && clampedWidth == frameWidth && clampedHeight == frameHeight;
    if (wholeFrame) {
      return false;
    }
    try (final Rect bounds = new Rect(this.x, this.y, clampedWidth, clampedHeight)) {
      image.transformMat((source, cropped) -> copyRegion(source, bounds, cropped));
    }
    return true;
  }

  private static void copyRegion(final Mat source, final Rect bounds, final Mat target) {
    try (final Mat region = new Mat(source, bounds)) {
      region.copyTo(target);
    }
  }

  /**
   * Always throws, because a crop changes the size of the frame and cannot be done in place;
   * {@link #modifyImage(MatImageBuffer)} crops instead.
   *
   * @param mat the matrix of the frame
   * @return never returns normally
   * @throws UnsupportedOperationException always
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    throw new UnsupportedOperationException("Cropping cannot be done in place");
  }
}
