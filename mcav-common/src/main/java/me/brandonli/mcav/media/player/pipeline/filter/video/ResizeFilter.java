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
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Resizes frames to a fixed size.
 *
 * <p>Downscaling uses area averaging, which produces smooth results without aliasing and is what the Minecraft
 * displays need. Area averaging is selected when either axis shrinks, including mixed shrink/grow resizes;
 * bilinear interpolation is used when neither axis shrinks. Frames that already have the target size are left
 * untouched.
 *
 * <p>The result is written into the spare matrix of the frame, see
 * {@link MatImageBuffer#transformMat(java.util.function.BiConsumer)}. A player that refills the frame at the size of
 * the video every frame gets the spare back, so resizing allocates nothing per frame once the sizes are settled. The
 * filter keeps no state besides its target size, so one filter may be attached to several pipelines at once.
 */
public class ResizeFilter extends MatVideoFilter {

  private final int width;
  private final int height;
  private final Size size;

  /**
   * Constructs a new resize filter.
   *
   * @param width  the target width in pixels
   * @param height the target height in pixels
   */
  public ResizeFilter(final int width, final int height) {
    Preconditions.checkArgument(width > 0 && height > 0, "Target size must be positive but was %sx%s", width, height);
    this.width = width;
    this.height = height;
    this.size = new Size(width, height);
  }

  /**
   * Checks whether this filter resizes to the specified size.
   *
   * @param width  the width in pixels
   * @param height the height in pixels
   * @return true if the target size is identical
   */
  public boolean matches(final int width, final int height) {
    return this.width == width && this.height == height;
  }

  /**
   * Resizes the frame into its spare matrix, which then becomes the matrix of the frame, averaging areas when it
   * shrinks and interpolating bilinearly when it grows.
   *
   * @param image the frame to resize
   * @return true if the frame was resized, false if it already had the target size and was left untouched
   */
  @Override
  protected boolean modifyImage(final MatImageBuffer image) {
    final int currentWidth = image.getWidth();
    final int currentHeight = image.getHeight();
    final boolean sameSize = currentWidth == this.width && currentHeight == this.height;
    if (sameSize) {
      return false;
    }
    final boolean downscaling = this.width < currentWidth || this.height < currentHeight;
    final int interpolation = downscaling ? opencv_imgproc.INTER_AREA : opencv_imgproc.INTER_LINEAR;
    final Size target = this.size;
    image.transformMat((source, resized) -> opencv_imgproc.resize(source, resized, target, 0, 0, interpolation));
    return true;
  }

  /**
   * Always throws, because resizing changes the size of the frame and cannot be done in place;
   * {@link #modifyImage(MatImageBuffer)} resizes instead.
   *
   * @param mat the matrix of the frame
   * @return never returns normally
   * @throws UnsupportedOperationException always
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    throw new UnsupportedOperationException("Resizing cannot be done in place");
  }
}
