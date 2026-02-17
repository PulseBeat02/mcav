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
package me.brandonli.mcav.media.image;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.examinable.Examinable;
import org.bytedeco.javacv.Frame;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Represents a static image that can be manipulated and examined.
 */
public interface ImageBuffer extends Image, Examinable {
  /**
   * Creates a new StaticImage instance using the given OpenCV Mat object.
   *
   * @param mat the OpenCV Mat object representing the image
   * @return a new StaticImage instance containing the provided Mat object
   */
  static ImageBuffer mat(final Mat mat) {
    return new MatImageBuffer(mat);
  }

  /**
   * Creates a new StaticImage instance using the given byte data, width, and height. Must be 3
   * channels RGB format.
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
   * Creates a new StaticImage instance using the given Frame object.
   *
   * @param frame the Frame object representing the image
   * @return a new StaticImage instance containing the provided Frame object
   */
  static ImageBuffer frame(final Frame frame) {
    return new MatImageBuffer(frame);
  }

  /**
   * Creates a new StaticImage instance using the given byte data, width, and height. Must be 3
   * channels RGB format.
   *
   * @param data   the byte array representing the image data
   * @param width  the width of the image
   * @param height the height of the image
   * @return a new StaticImage instance containing the provided data, width, and height
   */
  static ImageBuffer bytes(final ByteBuffer data, final int width, final int height) {
    return new MatImageBuffer(data, width, height);
  }

  /**
   * Converts the given byte array directly into a StaticImage object.
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

  /**
   * Creates a StaticImage instance from an array of pixel data. Must be 4 channels RGBA.
   *
   * @param data   the pixel data as an array of integers
   * @param width  the width of the image
   * @param height the height of the image
   * @return a StaticImage instance containing the provided pixel data
   */
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

  /**
   * Converts the current image buffer to a BufferedImage.
   *
   * @return a BufferedImage representation of the current image buffer
   */
  BufferedImage toBufferedImage();

  /**
   * Sets the current image buffer to the provided BufferedImage.
   *
   * @param image the BufferedImage to set as the current image buffer
   */
  void setAsBufferedImage(final BufferedImage image);

  /**
   * Sets the pixel at the specified coordinates to the given value.
   *
   * @param x     the x-coordinate of the pixel
   * @param y     the y-coordinate of the pixel
   * @param value the value to set for the pixel
   */
  void setPixel(final int x, final int y, final double[] value);

  /**
   * Gets all pixels in the image buffer as an array of integers.
   *
   * @return an array of integers representing the pixel values in the image buffer
   */
  int[] getPixels();

  /**
   * Gets the pixel value at the specified coordinates.
   *
   * @param x the x-coordinate of the pixel
   * @param y the y-coordinate of the pixel
   * @return an array representing the pixel value at the specified coordinates
   */
  double[] getPixel(final int x, final int y);

  /**
   * Gets the width of the image buffer.
   *
   * @return the width of the image buffer
   */
  int getWidth();

  /**
   * Gets the height of the image buffer.
   *
   * @return the height of the image buffer
   */
  int getHeight();

  /**
   * Gets the number of pixels in the image buffer.
   *
   * @return the total number of pixels in the image buffer
   */
  int getPixelCount();

  /**
   * Releases any resources held by this image buffer.
   * This method should be called when the image buffer is no longer needed.
   */
  void release();

  /**
   * Gets the raw pixel data of the image buffer as a ByteBuffer.
   *
   * @return a ByteBuffer containing the raw pixel data
   */
  ByteBuffer getData();

  /**
   * Updates the backing data of this image buffer in-place, reusing native allocations
   * when the dimensions match. This avoids per-frame allocation overhead.
   *
   * @param data   the new image data
   * @param width  the image width
   * @param height the image height
   */
  default void updateData(final ByteBuffer data, final int width, final int height) {
    throw new UnsupportedOperationException("updateData not supported by this ImageBuffer implementation");
  }
}
