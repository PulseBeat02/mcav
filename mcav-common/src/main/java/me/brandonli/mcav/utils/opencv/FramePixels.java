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
import java.nio.Buffer;
import java.nio.ByteBuffer;
import org.bytedeco.javacv.Frame;

/**
 * Copies the pixels of decoded video frames into compact buffers.
 *
 * <p>Decoders pad every row of a frame to an alignment boundary, so a row can be longer than its pixels. JavaCV
 * derives {@link Frame#imageChannels} from the padded row length, which reports far too many channels for very
 * narrow frames, such as small animated GIFs. This class therefore trusts only the row stride and the known pixel
 * format of the grabber.
 */
public final class FramePixels {

  /**
   * The number of bytes of a pixel in the BGR24 format that the grabbers of this library decode to.
   */
  public static final int BGR_CHANNELS = 3;

  private FramePixels() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Copies the pixels of a frame decoded to BGR24 into a new compact, direct buffer without row padding.
   *
   * @param frame the frame, decoded to BGR24 with eight bits per channel
   * @return the pixels, three bytes per pixel in blue, green, red order, ready to be read from position zero
   * @throws IllegalArgumentException if the frame holds no 8-bit image or its rows are shorter than its width
   */
  public static ByteBuffer copyBgr(final Frame frame) {
    Preconditions.checkNotNull(frame, "Frame must not be null");
    final int width = frame.imageWidth;
    final int height = frame.imageHeight;
    final int size = width * height * BGR_CHANNELS;
    final ByteBuffer pixels = ByteBuffer.allocateDirect(size);
    copyBgr(frame, pixels);
    return pixels;
  }

  /**
   * Copies the pixels of a frame decoded to BGR24 into an existing buffer without row padding, so decoders can reuse
   * their target memory for every frame. The pixels are written from index zero on, whatever the position of the
   * target, and neither the position nor the limit of the target changes.
   *
   * @param frame  the frame, decoded to BGR24 with eight bits per channel
   * @param target the buffer that receives the pixels, three bytes per pixel in blue, green, red order; its capacity
   *               must be at least {@code width * height * 3} bytes
   * @throws IllegalArgumentException if the frame holds no 8-bit image, its rows are shorter than its width, or the
   *                                  target is too small
   */
  public static void copyBgr(final Frame frame, final ByteBuffer target) {
    Preconditions.checkNotNull(frame, "Frame must not be null");
    Preconditions.checkNotNull(target, "Target must not be null");
    final ByteBuffer source = getPlane(frame);
    final int width = frame.imageWidth;
    final int height = frame.imageHeight;
    final int stride = frame.imageStride;
    final int rowBytes = width * BGR_CHANNELS;
    Preconditions.checkArgument(stride >= rowBytes, "Row stride %s is shorter than a row of %s pixels", stride, width);
    final int size = rowBytes * height;
    final int capacity = target.capacity();
    Preconditions.checkArgument(capacity >= size, "Target holds %s bytes but the frame needs %s", capacity, size);
    copyRows(source, target, rowBytes, height, stride);
  }

  private static ByteBuffer getPlane(final Frame frame) {
    final Buffer[] image = frame.image;
    Preconditions.checkArgument(image != null && image.length > 0, "Frame does not contain an image");
    final Buffer plane = image[0];
    Preconditions.checkArgument(plane instanceof ByteBuffer, "Frame does not contain 8-bit pixels");
    return (ByteBuffer) plane;
  }

  private static void copyRows(final ByteBuffer source, final ByteBuffer target, final int rowBytes, final int height, final int stride) {
    final ByteBuffer view = source.duplicate();
    final ByteBuffer destination = target.duplicate();
    destination.clear();
    if (stride == rowBytes) {
      view.position(0);
      view.limit(rowBytes * height);
      destination.put(view);
      return;
    }
    for (int row = 0; row < height; row++) {
      final int start = row * stride;
      view.limit(start + rowBytes);
      view.position(start);
      destination.put(view);
    }
  }
}
