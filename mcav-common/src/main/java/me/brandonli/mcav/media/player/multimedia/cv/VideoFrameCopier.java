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
package me.brandonli.mcav.media.player.multimedia.cv;

import com.google.common.annotations.VisibleForTesting;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.image.MatImageBuffer;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.utils.immutable.Dimension;
import me.brandonli.mcav.utils.opencv.FramePixels;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.Frame;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Copies decoded frames into images of an {@link ImagePool}, scaling them to the attached size when the decoder did
 * not.
 *
 * <p>FFmpeg scales while it converts the pixel format, which is the cheapest place to do it. Other decoders, such as
 * the file reader of OpenCV, ignore the requested size, so their frames are scaled here, straight from the memory of
 * the decoder into the pooled image, with area averaging when shrinking and bilinear interpolation when enlarging.
 * Frames whose pixels do not live in native memory cannot be read by OpenCV directly; they are copied into an image of
 * their own size first, which is kept for the next frame. Pooled images of another size are resized in place, which
 * reuses their spare matrix, see {@link MatImageBuffer#setSize(int, int)}. Once the sizes are settled, no memory is
 * allocated per frame.
 *
 * <p>Instances belong to the decoding thread of one session and are not thread-safe.
 */
final class VideoFrameCopier {

  private final ImagePool pool;
  private final DimensionAttachableCallback dimensionCallback;

  private @Nullable MatImageBuffer unscaled;

  /**
   * Constructs a new copier.
   *
   * @param pool              the pool the images come from
   * @param dimensionCallback provides the size frames are scaled to, if attached
   */
  VideoFrameCopier(final ImagePool pool, final DimensionAttachableCallback dimensionCallback) {
    this.pool = pool;
    this.dimensionCallback = dimensionCallback;
  }

  /**
   * Copies a frame decoded to 8-bit BGR into an image from the pool, scaled to the attached size if it has another
   * size.
   *
   * @param frame the frame, which the decoder may reuse as soon as this method returns
   * @return the image, which the caller hands back to the pool once it is no longer used
   * @throws IllegalArgumentException if the frame does not hold 8-bit pixels of a positive size
   */
  MatImageBuffer copy(final Frame frame) {
    final int width = frame.imageWidth;
    final int height = frame.imageHeight;
    final MatImageBuffer pooled = this.pool.acquire();
    final boolean scale = this.dimensionCallback.isAttached();
    if (!scale) {
      return fill(pooled, frame, width, height);
    }
    final Dimension target = this.dimensionCallback.retrieve();
    final int targetWidth = target.getWidth();
    final int targetHeight = target.getHeight();
    final boolean sameSize = targetWidth == width && targetHeight == height;
    if (sameSize) {
      return fill(pooled, frame, width, height);
    }
    return this.scale(frame, pooled, targetWidth, targetHeight);
  }

  /**
   * Releases the image unscaled frames were copied into. The copier can still be used afterward.
   */
  void release() {
    final MatImageBuffer image = this.unscaled;
    this.unscaled = null;
    if (image != null) {
      image.release();
    }
  }

  /**
   * Checks whether the copier holds a copy of an unscaled frame, which only frames outside of native memory need.
   *
   * @return true if an unscaled copy is kept for the next frame
   */
  @VisibleForTesting
  boolean hasUnscaledCopy() {
    return this.unscaled != null;
  }

  private static MatImageBuffer fill(final @Nullable MatImageBuffer reusable, final Frame frame, final int width, final int height) {
    final MatImageBuffer target = ensureSize(reusable, width, height);
    final ByteBuffer data = target.getData();
    FramePixels.copyBgr(frame, data);
    target.invalidateCache();
    return target;
  }

  /**
   * Scales a frame into an image of the target size, reading the pixels of the decoder in place when they live in
   * native memory, and through a copy of the frame otherwise.
   */
  private MatImageBuffer scale(final Frame frame, final @Nullable MatImageBuffer pooled, final int width, final int height) {
    final ByteBuffer plane = getNativePlane(frame);
    if (plane != null && fitsPlane(frame, plane)) {
      try (final Mat decoded = wrapDecodedPixels(frame, plane)) {
        return resize(decoded, pooled, width, height);
      }
    }
    // the pixels are not 8-bit BGR rows in native memory, so FramePixels copies and validates them instead
    final int frameWidth = frame.imageWidth;
    final int frameHeight = frame.imageHeight;
    final MatImageBuffer source = fill(this.unscaled, frame, frameWidth, frameHeight);
    this.unscaled = source;
    final Mat sourceMat = source.getMat();
    return resize(sourceMat, pooled, width, height);
  }

  /**
   * Creates a matrix header over the pixels of a frame, which uses the memory of the decoder without copying it.
   *
   * @param frame the frame, whose plane {@link #fitsPlane(Frame, ByteBuffer)} accepted
   * @param plane the native plane of the frame
   * @return the header, which the caller must close before the decoder reuses its memory
   */
  private static Mat wrapDecodedPixels(final Frame frame, final ByteBuffer plane) {
    final ByteBuffer whole = plane.duplicate();
    whole.clear();
    try (final BytePointer pixels = new BytePointer(whole)) {
      return new Mat(frame.imageHeight, frame.imageWidth, opencv_core.CV_8UC3, pixels, frame.imageStride);
    }
  }

  private static @Nullable ByteBuffer getNativePlane(final Frame frame) {
    final Buffer[] image = frame.image;
    if (image == null || image.length == 0) {
      return null;
    }
    final Buffer plane = image[0];
    if (plane instanceof final ByteBuffer bytes && bytes.isDirect()) {
      return bytes;
    }
    return null;
  }

  /**
   * Checks that a frame has a positive size and that its plane holds every row it claims, so OpenCV never reads past
   * the end of the memory of the decoder.
   */
  private static boolean fitsPlane(final Frame frame, final ByteBuffer plane) {
    final int width = frame.imageWidth;
    final int height = frame.imageHeight;
    final long stride = frame.imageStride;
    final long rowBytes = (long) width * FramePixels.BGR_CHANNELS;
    final long neededBytes = (height - 1L) * stride + rowBytes;
    final int capacity = plane.capacity();
    return width > 0 && height > 0 && stride >= rowBytes && capacity >= neededBytes;
  }

  private static MatImageBuffer resize(final Mat source, final @Nullable MatImageBuffer reusable, final int width, final int height) {
    final MatImageBuffer target = ensureSize(reusable, width, height);
    final Mat targetMat = target.getMat();
    final int sourceWidth = source.cols();
    final int sourceHeight = source.rows();
    final boolean shrinking = width < sourceWidth || height < sourceHeight;
    final int interpolation = shrinking ? opencv_imgproc.INTER_AREA : opencv_imgproc.INTER_LINEAR;
    try (final Size size = new Size(width, height)) {
      opencv_imgproc.resize(source, targetMat, size, 0, 0, interpolation);
    }
    target.invalidateCache();
    return target;
  }

  /**
   * Returns the reusable image resized to the wanted size, which reuses its spare matrix when it has that size, or a
   * new image if there is none.
   */
  private static MatImageBuffer ensureSize(final @Nullable MatImageBuffer reusable, final int width, final int height) {
    if (reusable != null) {
      reusable.setSize(width, height);
      return reusable;
    }
    final Mat mat = new Mat(height, width, opencv_core.CV_8UC3);
    final ImageBuffer created = ImageBuffer.mat(mat);
    return (MatImageBuffer) created;
  }
}
