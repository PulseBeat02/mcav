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

import com.google.common.base.Preconditions;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import org.bytedeco.javacv.Frame;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * A single image, such as one frame of a video, backed by native memory.
 *
 * <p>Image buffers always store pixels as 8-bit BGR without padding, which is the layout OpenCV and FFmpeg work
 * with natively, so every video filter can operate on them without conversion. Pixels are also available as
 * packed ARGB integers through {@link #getPixels()}, which is the layout the dithering algorithms and the
 * Minecraft displays consume. The integer view is computed lazily and cached until the image changes.
 *
 * <p>The array returned by {@link #getPixels()} is shared, not copied, because it is read once per frame by every
 * display. It must be treated as read-only: a caller that writes into it changes what every later caller sees,
 * including every later frame of a {@link me.brandonli.mcav.media.source.frame.RepeatingFrameSource}, which hands
 * out the same arrays again on every loop. Use {@link #copyPixels()} to get an array you may modify, or
 * {@link #getReadOnlyPixels()} to pass the pixels to code you do not trust without copying them.
 *
 * <p>Buffers hold native memory and must be released with {@link #release()} or {@link #close()} when they are no
 * longer needed. They are not thread-safe.
 *
 * <pre><code>
 *   final Path file = Path.of("picture.png");
 *   final FileSource source = FileSource.path(file);
 *   try (final ImageBuffer image = ImageBuffer.path(source)) {
 *     final int[] pixels = image.getPixels();       // shared: read it, never write it
 *     final int[] editable = image.copyPixels();    // private copy: write it freely
 *     // ...
 *   }
 * </code></pre>
 */
public interface ImageBuffer extends Image {
  /**
   * Wraps an OpenCV matrix. The matrix is converted to 8-bit BGR if it uses another layout, and the buffer takes
   * ownership of it.
   *
   * @param mat the non-null, nonempty matrix with 1, 3 or 4 channels; ownership transfers on success
   * @return the image buffer
   * @throws IllegalArgumentException if the matrix is empty or has a channel count other than 1, 3 or 4
   * @throws NullPointerException if {@code mat} is null
   */
  static ImageBuffer mat(final Mat mat) {
    Preconditions.checkNotNull(mat, "Mat must not be null");
    return new MatImageBuffer(mat);
  }

  /**
   * Creates an image from raw 8-bit BGR pixels without padding. The bytes are copied.
   * Dimensions must be positive and their packed BGR representation must fit within {@link Integer#MAX_VALUE} bytes.
   *
   * @param data   the pixels, exactly {@code width * height * 3} bytes
   * @param width  the width in pixels
   * @param height the height in pixels
   * @return the image buffer
   * @throws IllegalArgumentException if dimensions are nonpositive, the packed BGR size exceeds
   *                                  {@link Integer#MAX_VALUE}, or the byte count differs from that size
   * @throws NullPointerException if {@code data} is null
   */
  static ImageBuffer bytes(final byte[] data, final int width, final int height) {
    Preconditions.checkNotNull(data, "Data must not be null");
    return new MatImageBuffer(data, width, height);
  }

  /**
   * Creates an image from raw 8-bit BGR pixels without padding. The bytes are copied from the current position of
   * the buffer to its limit. Dimensions must be positive and their packed BGR representation must fit within
   * {@link Integer#MAX_VALUE} bytes. The input buffer's position and limit are unchanged.
   *
   * @param data   the pixels, exactly {@code width * height * 3} bytes remaining
   * @param width  the width in pixels
   * @param height the height in pixels
   * @return the image buffer
   * @throws IllegalArgumentException if dimensions are nonpositive, the packed BGR size exceeds
   *                                  {@link Integer#MAX_VALUE}, or the byte count differs from that size
   * @throws NullPointerException if {@code data} is null
   */
  static ImageBuffer bytes(final ByteBuffer data, final int width, final int height) {
    Preconditions.checkNotNull(data, "Data must not be null");
    return new MatImageBuffer(data, width, height);
  }

  /**
   * Decodes an encoded image, such as the bytes of a PNG or JPEG file.
   *
   * @param bytes the encoded image
   * @return the image buffer
   * @throws IllegalArgumentException if the bytes are not a supported image format
   * @throws NullPointerException if {@code bytes} is null
   */
  static ImageBuffer bytes(final byte[] bytes) {
    Preconditions.checkNotNull(bytes, "Bytes must not be null");
    return new MatImageBuffer(bytes);
  }

  /**
   * Converts a JavaCV frame. Frames with three channels are treated as BGR, frames with four channels as BGRA, and
   * frames with one channel as grayscale.
   *
   * @param frame the non-null frame containing an image; its pixels are copied and the caller keeps ownership
   * @return the image buffer
   * @throws IllegalArgumentException if the frame has no image or has a channel count other than 1, 3 or 4
   * @throws NullPointerException if {@code frame} is null
   */
  static ImageBuffer frame(final Frame frame) {
    Preconditions.checkNotNull(frame, "Frame must not be null");
    return new MatImageBuffer(frame);
  }

  /**
   * Downloads and decodes an image from a URL.
   *
   * @param source the URL of the image
   * @return the image buffer
   * @throws java.io.UncheckedIOException if the image cannot be downloaded
   * @throws IllegalArgumentException     if the file is not a supported image format
   * @throws NullPointerException if {@code source} is null
   */
  static ImageBuffer uri(final UriSource source) {
    Preconditions.checkNotNull(source, "Source must not be null");
    return new MatImageBuffer(source);
  }

  /**
   * Decodes an image file.
   *
   * @param source the image file
   * @return the image buffer
   * @throws IllegalArgumentException if the file does not exist or is not a supported image format
   * @throws NullPointerException if {@code source} is null
   */
  static ImageBuffer path(final FileSource source) {
    Preconditions.checkNotNull(source, "Source must not be null");
    return new MatImageBuffer(source);
  }

  /**
   * Creates an image from packed ARGB pixels, one integer per pixel laid out row by row. The alpha channel is
   * discarded. Dimensions must be positive and the resulting packed BGR representation must fit within
   * {@link Integer#MAX_VALUE} bytes, even though the supplied pixels are integers.
   *
   * @param data   the pixels, exactly {@code width * height} integers
   * @param width  the width in pixels
   * @param height the height in pixels
   * @return the image buffer
   * @throws IllegalArgumentException if dimensions are nonpositive, the packed BGR size exceeds
   *                                  {@link Integer#MAX_VALUE}, or the pixel count differs from the dimensions
   * @throws NullPointerException if {@code data} is null
   */
  static ImageBuffer buffer(final int[] data, final int width, final int height) {
    Preconditions.checkNotNull(data, "Data must not be null");
    return new MatImageBuffer(data, width, height);
  }

  /**
   * Copies a Java image. Every image type is supported; layouts that cannot be copied directly are read
   * through {@link BufferedImage#getRGB(int, int, int, int, int[], int, int)}. Alpha is discarded.
   *
   * @param image the image to copy
   * @return the image buffer
   * @throws IllegalArgumentException if the image dimensions exceed the maximum packed BGR buffer size
   * @throws NullPointerException if {@code image} is null
   */
  static ImageBuffer image(final BufferedImage image) {
    Preconditions.checkNotNull(image, "Image must not be null");
    return new MatImageBuffer(image);
  }

  /**
   * Copies the image into a new Java image of type {@link BufferedImage#TYPE_3BYTE_BGR}.
   *
   * @return a new Java image
   * @throws IllegalStateException if the image was released
   */
  BufferedImage toBufferedImage();

  /**
   * Replaces the content of this buffer with a Java image, resizing the buffer if necessary.
   *
   * @param image the image to copy
   * @throws NullPointerException if {@code image} is null
   * @throws IllegalStateException if the image was released
   */
  void setAsBufferedImage(final BufferedImage image);

  /**
   * Sets the color of one pixel.
   *
   * @param column the zero-based column, less than {@link #getWidth()}
   * @param row    the zero-based row, less than {@link #getHeight()}
   * @param value  the non-null blue, green and red components in that order; the first three supplied
   *               values are rounded and clamped to 0 through 255, missing components are unchanged,
   *               and additional components are ignored
   * @throws IllegalArgumentException if the row or column is outside the image
   * @throws NullPointerException if {@code value} is null
   * @throws IllegalStateException if the image was released
   */
  void setPixel(final int column, final int row, final double[] value);

  /**
   * Gets the color of one pixel.
   *
   * @param column the zero-based column, less than {@link #getWidth()}
   * @param row    the zero-based row, less than {@link #getHeight()}
   * @return a new three-element array containing blue, green and red, each from 0 to 255
   * @throws IllegalArgumentException if the row or column is outside the image
   * @throws IllegalStateException if the image was released
   */
  double[] getPixel(final int column, final int row);

  /**
   * Gets every pixel as packed ARGB integers, laid out row by row, without copying them.
   *
   * <p><strong>The array is shared and must be treated as read-only.</strong> It is computed once and cached, and
   * every caller receives the same instance until the image changes. Writing into it does not change the image
   * itself, but it silently changes what every later caller of this method sees, and frame sources that replay the
   * same image, such as {@link me.brandonli.mcav.media.source.frame.RepeatingFrameSource}, then show the damage on
   * every later frame. The array is also replaced, not updated, when the image changes, so a caller that keeps it
   * holds the old pixels.
   *
   * <p>This method is meant for the per-frame path, where copying every frame would be too slow. Call
   * {@link #copyPixels()} for an array you may modify or keep, and {@link #getReadOnlyPixels()} for a view that
   * rejects writes without copying.
   *
   * @return the shared pixels of the image, which must not be modified
   * @throws IllegalStateException if the image was released
   */
  int[] getPixels();

  /**
   * Copies every pixel into a new array of packed ARGB integers, laid out row by row. Unlike {@link #getPixels()},
   * the returned array belongs to the caller: it may be modified and kept, and it never changes when the image
   * changes. Every call allocates and fills a new array, so prefer {@link #getPixels()} in code that only reads
   * the pixels once per frame.
   *
   * @return a new array with the pixels of the image
   * @throws IllegalStateException if the image was released
   */
  default int[] copyPixels() {
    final int[] shared = this.getPixels();
    return shared.clone();
  }

  /**
   * Gets a read-only view of the shared pixels of {@link #getPixels()}, as packed ARGB integers laid out row by row.
   * The view does not copy the pixels, so it costs almost nothing, and every attempt to write through it throws a
   * {@link java.nio.ReadOnlyBufferException}. Hand it to code that must not be able to corrupt the shared array.
   *
   * <p>The view starts at position zero with a limit of {@link #getPixelCount()}. Like the array it wraps, it keeps
   * showing the old pixels after the image changes, so call this method again for the new pixels.
   *
   * @return a read-only view of the pixels of the image
   * @throws IllegalStateException if the image was released
   */
  default IntBuffer getReadOnlyPixels() {
    final int[] shared = this.getPixels();
    final IntBuffer wrapped = IntBuffer.wrap(shared);
    return wrapped.asReadOnlyBuffer();
  }

  /**
   * Gets the width of the image.
   *
   * @return the width in pixels
   * @throws IllegalStateException if the image was released
   */
  int getWidth();

  /**
   * Gets the height of the image.
   *
   * @return the height in pixels
   * @throws IllegalStateException if the image was released
   */
  int getHeight();

  /**
   * Gets the number of pixels of the image.
   *
   * @return the width multiplied by the height
   * @throws IllegalStateException if the image was released
   */
  int getPixelCount();

  /**
   * Gets a view of the raw 8-bit BGR pixels of the image, without padding. The pixels are the bytes between the
   * position and the limit of the returned buffer. The buffer shares memory with the image: changes to it change the
   * image, and it becomes invalid when the image is resized, transformed or released.
   *
   * @return a direct buffer over the pixels
   * @throws IllegalStateException if the image was released
   */
  ByteBuffer getData();

  /**
   * Replaces the pixels of the image with raw 8-bit BGR pixels, reusing the native memory when the dimensions did
   * not change. The bytes are copied from the current position of the buffer to its limit. Dimensions must be
   * positive and their packed BGR representation must fit within {@link Integer#MAX_VALUE} bytes. The source
   * position and limit are unchanged.
   *
   * @param data   the pixels, exactly {@code width * height * 3} bytes remaining
   * @param width  the new width in pixels
   * @param height the new height in pixels
   * @throws NullPointerException if {@code data} is null
   * @throws IllegalArgumentException if dimensions are nonpositive, the packed BGR size exceeds
   *                                  {@link Integer#MAX_VALUE}, or the input count differs from the dimensions
   * @throws IllegalStateException if the image was released
   */
  void updateData(final ByteBuffer data, final int width, final int height);

  /**
   * Replaces the pixels of the image with packed ARGB pixels, reusing the native memory when the dimensions did
   * not change. The alpha channel is discarded. Dimensions must be positive and the resulting packed BGR
   * representation must fit within {@link Integer#MAX_VALUE} bytes.
   *
   * @param pixels the pixels, exactly {@code width * height} integers laid out row by row
   * @param width  the new width in pixels
   * @param height the new height in pixels
   * @throws NullPointerException if {@code pixels} is null
   * @throws IllegalArgumentException if dimensions are nonpositive, the packed BGR size exceeds
   *                                  {@link Integer#MAX_VALUE}, or the input count differs from the dimensions
   * @throws IllegalStateException if the image was released
   */
  void updateArgb(final int[] pixels, final int width, final int height);

  /**
   * Discards the cached ARGB pixel array, so the next call to {@link #getPixels()} reads the image again. Video
   * filters call this after modifying the image through {@link #getData()} or through OpenCV directly.
   */
  void invalidateCache();

  /**
   * Creates a deep copy of this image that is independent of it.
   *
   * @return a new image buffer with the same pixels
   * @throws IllegalStateException if the image was released
   */
  ImageBuffer copy();

  /**
   * Releases the native memory of the image. Calling this method more than once has no effect, and every other
   * operation accessing native pixels or dimensions throws {@link IllegalStateException} afterward.
   * {@link #invalidateCache()} remains harmless. Previously returned native views must no longer be used.
   */
  void release();

  /**
   * Releases the native memory of the image, see {@link #release()}.
   */
  @Override
  default void close() {
    this.release();
  }
}
