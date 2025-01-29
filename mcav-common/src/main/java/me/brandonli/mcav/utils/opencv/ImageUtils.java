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

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * A utility class for performing image-related operations such as resizing.
 * <p>
 * This class is designed to handle operations on images represented as integer arrays.
 * Each pixel in the integer array is assumed to be a 32-bit ARGB color value.
 */
public class ImageUtils {

  private ImageUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
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
    final Mat originalMat = new Mat(originalHeight, originalWidth, CvType.CV_8UC4);
    final byte[] byteData = new byte[originalData.length * 4];
    for (int i = 0; i < originalData.length; i++) {
      final int pixel = originalData[i];
      final int idx = i * 4;
      byteData[idx] = (byte) (pixel & 0xFF);
      byteData[idx + 1] = (byte) ((pixel >> 8) & 0xFF);
      byteData[idx + 2] = (byte) ((pixel >> 16) & 0xFF);
      byteData[idx + 3] = (byte) 0xFF;
    }
    originalMat.put(0, 0, byteData);
    final Mat resizedMat = new Mat();
    Imgproc.resize(originalMat, resizedMat, new Size(newWidth, newHeight));
    final int[] resizedData = new int[newWidth * newHeight];
    final byte[] resizedByteData = new byte[resizedData.length * 4];
    resizedMat.get(0, 0, resizedByteData);
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
