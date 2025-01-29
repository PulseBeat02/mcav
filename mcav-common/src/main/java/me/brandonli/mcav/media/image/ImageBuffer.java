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
package me.brandonli.mcav.media.image;

import java.awt.image.BufferedImage;
import java.io.IOException;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.media.source.UriSource;
import me.brandonli.mcav.utils.examinable.Examinable;

/**
 * Represents a static image that can be manipulated and examined.
 */
public interface ImageBuffer extends Image, Examinable {
  /**
   * Creates a new StaticImage instance using the given byte data, width, and height.
   *
   * @param data   the byte array representing the image data
   * @param width  the width of the image
   * @param height the height of the image
   * @return a new StaticImage instance containing the provided data, width, and height
   */
  static ImageBuffer bytes(final byte[] data, final int width, final int height) {
    return new MatImageBuffer(data, width, height);
  }

  /**
   * Converts the given byte array into a StaticImage object.
   *
   * @param bytes the byte array representing the image data
   * @return a StaticImage object created from the provided byte array
   */
  static ImageBuffer bytes(final byte[] bytes) {
    return new MatImageBuffer(bytes);
  }

  /**
   * Creates a StaticImage instance from a URI.
   *
   * @param source the URI source representing the image
   * @return a StaticImage instance
   */
  static ImageBuffer uri(final UriSource source) {
    return new MatImageBuffer(source);
  }

  /**
   * Creates a StaticImage instance from a file path.
   *
   * @param path the file path representing the image
   * @return a StaticImage instance
   */
  static ImageBuffer path(final FileSource path) {
    return new MatImageBuffer(path);
  }

  static ImageBuffer buffer(final int[] data, final int width, final int height) {
    return new MatImageBuffer(data, width, height);
  }

  /**
   * Creates a StaticImage instance from a BufferedImage.
   *
   * @param image the BufferedImage representing the image
   * @return a StaticImage instance
   * @throws IOException if an error occurs while processing the BufferedImage
   */
  static ImageBuffer image(final BufferedImage image) throws IOException {
    return new MatImageBuffer(image);
  }

  BufferedImage toBufferedImage();

  void setAsBufferedImage(final BufferedImage image);

  void setPixel(final int x, final int y, final double[] value);

  int[] getPixels();

  double[] getPixel(final int x, final int y);

  int getWidth();

  int getHeight();

  int getPixelCount();

  void release();
}
