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
package me.brandonli.mcav.utils.opencv;

import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * A utility class for performing image-related operations.
 */
public class ImageUtils {

  private ImageUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Converts a double array to an OpenCV Scalar object.
   * The first four elements of the array are used to set the values of the Scalar.
   * If the array has fewer than four elements, the missing values are set to 0.
   *
   * @param scalar the double array to convert
   * @return a Scalar object representing the values in the array
   */
  public static Scalar toScalar(final double[] scalar) {
    final double v0 = scalar.length > 0 ? scalar[0] : 0;
    final double v1 = scalar.length > 1 ? scalar[1] : 0;
    final double v2 = scalar.length > 2 ? scalar[2] : 0;
    final double v3 = scalar.length > 3 ? scalar[3] : 0;
    return new Scalar(v0, v1, v2, v3);
  }

  /**
   * Resizes an image represented as a one-dimensional integer array.
   * Each pixel in the input array is assumed to be a 32-bit ARGB color value.
   *
   * @param originalData   the original image data represented as a one-dimensional integer array
   * @param originalWidth  the width of the original image
   * @param originalHeight the height of the original image
   * @param newWidth       the width of the resized image
   * @param newHeight      the height of the resized image
   * @return the resized image data as a one-dimensional integer array where each pixel is a 32-bit ARGB color value
   */
  public static int[] resizeIntArrayImage(
    final int[] originalData,
    final int originalWidth,
    final int originalHeight,
    final int newWidth,
    final int newHeight
  ) {
    final Mat originalMat = new Mat(originalHeight, originalWidth, opencv_core.CV_8UC4);
    final byte[] byteData = new byte[originalData.length * 4];
    for (int i = 0; i < originalData.length; i++) {
      final int pixel = originalData[i];
      final int idx = i * 4;
      byteData[idx] = (byte) (pixel & 0xFF);
      byteData[idx + 1] = (byte) ((pixel >> 8) & 0xFF);
      byteData[idx + 2] = (byte) ((pixel >> 16) & 0xFF);
      byteData[idx + 3] = (byte) 0xFF;
    }
    originalMat.data().put(byteData);
    final Mat resizedMat = new Mat();
    opencv_imgproc.resize(originalMat, resizedMat, new Size(newWidth, newHeight));
    final int[] resizedData = new int[newWidth * newHeight];
    final byte[] resizedByteData = new byte[resizedData.length * 4];
    resizedMat.data().put(resizedByteData);
    for (int i = 0; i < resizedData.length; i++) {
      final int idx = i * 4;
      final int b = resizedByteData[idx] & 0xFF;
      final int g = resizedByteData[idx + 1] & 0xFF;
      final int r = resizedByteData[idx + 2] & 0xFF;
      resizedData[i] = (r << 16) | (g << 8) | b;
    }
    return resizedData;
  }
}
