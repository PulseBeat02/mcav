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
package me.brandonli.mcav.utils.opencv;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Helpers for working with OpenCV values and packed pixel arrays.
 */
public final class ImageUtils {

  private static final int SCALAR_COMPONENTS = 4;

  private ImageUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Converts up to four channel values into an OpenCV scalar. Missing components are set to zero, and extra
   * components are ignored.
   *
   * @param components the channel values in the order OpenCV expects, which is blue, green, red, and alpha
   * @return the scalar
   */
  public static Scalar toScalar(final double[] components) {
    Preconditions.checkNotNull(components, "Components must not be null");
    final double[] values = new double[SCALAR_COMPONENTS];
    final int count = Math.min(components.length, SCALAR_COMPONENTS);
    System.arraycopy(components, 0, values, 0, count);
    return new Scalar(values[0], values[1], values[2], values[3]);
  }

  /**
   * Resizes an image given as packed ARGB pixels. The result has the same layout, with every pixel fully opaque.
   *
   * @param pixels         the pixels of the image, laid out row by row
   * @param originalWidth  the width of the image
   * @param originalHeight the height of the image
   * @param newWidth       the width of the resized image
   * @param newHeight      the height of the resized image
   * @return the pixels of the resized image, laid out row by row
   */
  public static int[] resizeIntArrayImage(
    final int[] pixels,
    final int originalWidth,
    final int originalHeight,
    final int newWidth,
    final int newHeight
  ) {
    Preconditions.checkNotNull(pixels, "Pixels must not be null");
    Preconditions.checkArgument(originalWidth > 0 && originalHeight > 0, "Original size must be positive");
    Preconditions.checkArgument(newWidth > 0 && newHeight > 0, "New size must be positive");
    Preconditions.checkArgument(pixels.length == originalWidth * originalHeight, "Pixel count does not match the original size");
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, originalWidth, originalHeight)) {
      final ResizeFilter resize = new ResizeFilter(newWidth, newHeight);
      resize.applyFilter(image);
      final int[] resized = image.getPixels();
      return resized.clone();
    }
  }
}
