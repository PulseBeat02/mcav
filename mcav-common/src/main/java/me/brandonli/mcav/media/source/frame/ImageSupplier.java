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
package me.brandonli.mcav.media.source.frame;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Supplies frames as Java images to a {@link FrameSource}. Called once per frame on the player thread.
 *
 * <p>Images of type {@link BufferedImage#TYPE_INT_ARGB} or {@link BufferedImage#TYPE_INT_RGB} are the fastest to
 * supply, because they already store packed ARGB pixels, which are copied out row by row. Every other type is converted
 * pixel by pixel.
 */
@FunctionalInterface
public interface ImageSupplier {
  /**
   * Gets the next frame.
   *
   * @return the frame
   */
  BufferedImage getImage();

  /**
   * Adapts this supplier to supply packed ARGB pixels.
   *
   * @return a sample supplier that copies the pixels of every image into a new array
   */
  default SampleSupplier toSampleSupplier() {
    return () -> {
      final BufferedImage image = this.getImage();
      return readArgb(image);
    };
  }

  /**
   * Reads the pixels of an image as packed ARGB, exactly as {@link BufferedImage#getRGB} does. The integer RGB types
   * store that layout already, so their rows are copied out of the raster in bulk, without the per-pixel conversion
   * through the color model; the raster copies rows through its own data array, which keeps sub-images correct and
   * the image eligible for hardware acceleration. {@code TYPE_INT_RGB} stores no alpha, so the pixels are made opaque.
   *
   * @param image the image
   * @return a new array with the pixels of the image laid out row by row
   */
  private static int[] readArgb(final BufferedImage image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = new int[width * height];
    final int type = image.getType();
    final boolean packed = type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_RGB;
    if (!packed) {
      image.getRGB(0, 0, width, height, pixels, 0, width);
      return pixels;
    }
    final WritableRaster raster = image.getRaster();
    raster.getDataElements(0, 0, width, height, pixels);
    if (type == BufferedImage.TYPE_INT_RGB) {
      final int opaqueAlpha = 0xFF << 24;
      for (int index = 0; index < pixels.length; index++) {
        pixels[index] |= opaqueAlpha;
      }
    }
    return pixels;
  }
}
