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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.image.MatImageBuffer;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.utils.UncheckedIOException;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * An abstract class for video filters that operate on OpenCV Mat objects.
 */
abstract class MatVideoFilter implements VideoFilter {

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final ImageBuffer samples, final VideoMetadata metadata) {
    final Consumer<Mat> matOperation = this::modifyMat;
    final Mat mat = this.getMat(samples);
    matOperation.accept(mat);
    if (this.mustApplyMat(samples)) {
      this.applyMatResults(samples, mat);
      mat.release();
    }
  }

  /**
   * Modifies the given OpenCV Mat object.
   *
   * @param mat the OpenCV Mat object to modify
   */
  abstract void modifyMat(final Mat mat);

  /**
   * Retrieves the OpenCV Mat object from the given ImageBuffer.
   * If the ImageBuffer does not contain a Mat, it converts the buffer to a Mat.
   *
   * @param buffer the ImageBuffer containing image data
   * @return the OpenCV Mat object
   */
  Mat getMat(final ImageBuffer buffer) {
    if (buffer.has(MatImageBuffer.MAT_PROPERTY)) {
      return buffer.getOrThrow(MatImageBuffer.MAT_PROPERTY);
    } else {
      final BufferedImage image = buffer.toBufferedImage();
      return Java2DFrameUtils.toMat(image);
    }
  }

  /**
   * Determines whether the Mat operation must be applied to the ImageBuffer.
   * This is true if the ImageBuffer does not already contain a Mat object.
   *
   * @param buffer the ImageBuffer to check
   * @return true if the Mat operation must be applied, false otherwise
   */
  boolean mustApplyMat(final ImageBuffer buffer) {
    return !buffer.has(MatImageBuffer.MAT_PROPERTY);
  }

  /**
   * Applies the results of the Mat operation to the ImageBuffer.
   * This method encodes the Mat as a JPEG image and sets it in the ImageBuffer.
   *
   * @param buffer the ImageBuffer to update
   * @param mat    the OpenCV Mat object containing the processed image
   */
  void applyMatResults(final ImageBuffer buffer, final Mat mat) {
    try {
      final BytePointer bytePointer = new BytePointer();
      opencv_imgcodecs.imencode(".jpg", mat, bytePointer);
      final byte[] byteArray = new byte[(int) bytePointer.limit()];
      bytePointer.get(byteArray);
      final BufferedImage img = ImageIO.read(new ByteArrayInputStream(byteArray));
      buffer.setAsBufferedImage(img);
      bytePointer.deallocate();
    } catch (final IOException e) {
      throw new UncheckedIOException(e.getMessage(), e);
    }
  }
}
