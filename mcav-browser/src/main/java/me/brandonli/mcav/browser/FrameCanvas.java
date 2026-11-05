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
package me.brandonli.mcav.browser;

import com.google.common.annotations.VisibleForTesting;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.checkerframework.checker.nullness.qual.Nullable;

// The page starts white, like a page before its first paint.
final class FrameCanvas implements AutoCloseable {

  private static final double FULL_CHANNEL = 255.0;

  private final int width;
  private final int height;
  private final Mat page;
  private final ByteBuffer pixels;

  private boolean closed;

  FrameCanvas(final int width, final int height) {
    this.width = width;
    this.height = height;
    final Scalar white = new Scalar(FULL_CHANNEL, FULL_CHANNEL, FULL_CHANNEL, FULL_CHANNEL);
    this.page = new Mat(height, width, opencv_core.CV_8UC4, white);
    this.pixels = this.page.createBuffer();
  }

  synchronized void apply(final FrameRegion region) throws ProtocolException {
    final int pageWidth = region.getPageWidth();
    final int pageHeight = region.getPageHeight();
    if (pageWidth != this.width || pageHeight != this.height) {
      final String message = "A frame of a %sx%s page arrived for a %sx%s page!".formatted(pageWidth, pageHeight, this.width, this.height);
      throw new ProtocolException(message);
    }

    if (this.closed) {
      return;
    }

    final byte[] source = region.getPixels();
    final int left = region.getX();
    final int top = region.getY();
    final int regionWidth = region.getWidth();
    final int rows = region.getHeight();
    final int rowBytes = regionWidth * HelperProtocol.PIXEL_BYTES;
    for (int row = 0; row < rows; row++) {
      final int target = ((top + row) * this.width + left) * HelperProtocol.PIXEL_BYTES;
      final int offset = row * rowBytes;
      this.pixels.put(target, source, offset, rowBytes);
    }
  }

  synchronized @Nullable ImageBuffer snapshot() {
    if (this.closed) {
      return null;
    }

    final Mat bgr = new Mat();
    opencv_imgproc.cvtColor(this.page, bgr, opencv_imgproc.COLOR_BGRA2BGR);
    return ImageBuffer.mat(bgr);
  }

  @Override
  public synchronized void close() {
    if (this.closed) {
      return;
    }

    this.closed = true;
    this.page.close();
  }

  int getWidth() {
    return this.width;
  }

  int getHeight() {
    return this.height;
  }

  @VisibleForTesting
  Mat getPage() {
    return this.page;
  }
}
