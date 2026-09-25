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

/**
 * The server's copy of the page a browser helper streams: the changed regions the helper sends are written into it,
 * and every frame for the video pipeline is a copy of it converted from BGRA to the BGR the pipeline works with. The
 * page starts white, like a page before its first paint.
 */
final class FrameCanvas implements AutoCloseable {

  private final int width;
  private final int height;
  private final Mat page;
  private final ByteBuffer pixels;
  private boolean closed;

  /**
   * Constructs a white canvas.
   *
   * @param width  the width of the page in pixels
   * @param height the height of the page in pixels
   */
  FrameCanvas(final int width, final int height) {
    this.width = width;
    this.height = height;
    final Scalar white = new Scalar(255.0, 255.0, 255.0, 255.0);
    this.page = new Mat(height, width, opencv_core.CV_8UC4, white);
    this.pixels = this.page.createBuffer();
  }

  /**
   * Writes a changed region into the page.
   *
   * @param region the region, whose position and size {@link HelperProtocol} already checked against its page
   * @throws ProtocolException if the region belongs to a page of another size
   */
  synchronized void apply(final FrameRegion region) throws ProtocolException {
    if (region.getPageWidth() != this.width || region.getPageHeight() != this.height) {
      throw new ProtocolException(
        "A frame of a " +
        region.getPageWidth() +
        "x" +
        region.getPageHeight() +
        " page arrived for a " +
        this.width +
        "x" +
        this.height +
        " page"
      );
    }
    if (this.closed) {
      return;
    }
    final byte[] source = region.getPixels();
    final int x = region.getX();
    final int y = region.getY();
    final int regionWidth = region.getWidth();
    final int rows = region.getHeight();
    final int rowBytes = regionWidth * HelperProtocol.PIXEL_BYTES;
    for (int row = 0; row < rows; row++) {
      final int target = ((y + row) * this.width + x) * HelperProtocol.PIXEL_BYTES;
      final int offset = row * rowBytes;
      this.pixels.put(target, source, offset, rowBytes);
    }
  }

  /**
   * Copies the page as a BGR image.
   *
   * @return the image, which the caller owns, or null once the canvas was closed
   */
  synchronized @Nullable ImageBuffer snapshot() {
    if (this.closed) {
      return null;
    }
    final Mat bgr = new Mat();
    opencv_imgproc.cvtColor(this.page, bgr, opencv_imgproc.COLOR_BGRA2BGR);
    return ImageBuffer.mat(bgr);
  }

  int getWidth() {
    return this.width;
  }

  int getHeight() {
    return this.height;
  }

  /**
   * Gets the native picture of the page, so tests can check that closing releases it.
   *
   * @return the picture
   */
  @VisibleForTesting
  Mat getPage() {
    return this.page;
  }

  /**
   * Frees the native memory of the page.
   */
  @Override
  public synchronized void close() {
    if (this.closed) {
      return;
    }
    this.closed = true;
    this.page.close();
  }
}
