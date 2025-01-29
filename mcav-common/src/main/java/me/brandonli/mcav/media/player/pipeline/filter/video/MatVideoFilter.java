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
import org.bytedeco.javacv.Java2DFrameUtils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

abstract class MatVideoFilter implements VideoFilter {

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

  abstract void modifyMat(final Mat mat);

  Mat getMat(final ImageBuffer buffer) {
    if (buffer.has(MatImageBuffer.MAT_PROPERTY)) {
      return buffer.getOrThrow(MatImageBuffer.MAT_PROPERTY);
    } else {
      final BufferedImage image = buffer.toBufferedImage();
      final org.bytedeco.opencv.opencv_core.Mat mat = Java2DFrameUtils.toMat(image);
      return new Mat(mat.address());
    }
  }

  boolean mustApplyMat(final ImageBuffer buffer) {
    return !buffer.has(MatImageBuffer.MAT_PROPERTY);
  }

  void applyMatResults(final ImageBuffer buffer, final Mat mat) {
    // used for non-native image buffers
    final MatOfByte mob = new MatOfByte();
    Imgcodecs.imencode(".jpg", mat, mob);
    final byte[] ba = mob.toArray();
    try {
      final BufferedImage img = ImageIO.read(new ByteArrayInputStream(ba));
      buffer.setAsBufferedImage(img);
    } catch (final IOException e) {
      throw new UncheckedIOException(e.getMessage(), e);
    }
  }
}
