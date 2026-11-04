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

// Copies decoded frames into images of an ImagePool, scaling them to the attached size when the decoder did not. FFmpeg
// scales while it converts the pixel format, which is the cheapest place to do it. Other decoders, such as the file
// reader of OpenCV, ignore the requested size, so their frames are scaled here, straight from the memory of the decoder
// into the pooled image, with area averaging when shrinking and bilinear interpolation when enlarging. Frames whose
// pixels do not live in native memory cannot be read by OpenCV directly; they are copied into an image of their own
// size first, which is kept for the next frame. Pooled images of another size are resized in place, which reuses their
// spare matrix, see MatImageBuffer.setSize(int, int). Once the sizes are settled, no memory is allocated per frame.
// Instances belong to the decoding thread of one session and are not thread-safe.
final class VideoFrameCopier {

  private final ImagePool pool;
  private final DimensionAttachableCallback dimensionCallback;

  private @Nullable MatImageBuffer unscaled;

  VideoFrameCopier(final ImagePool pool, final DimensionAttachableCallback dimensionCallback) {
    this.pool = pool;
    this.dimensionCallback = dimensionCallback;
  }

  MatImageBuffer copy(final Frame frame) {
    final int width = frame.imageWidth;
    final int height = frame.imageHeight;
    final @Nullable MatImageBuffer pooled = this.pool.acquire();

    // the size is read once. Asking whether the callback is attached and then asking it for the size lets a detach
    // in between answer with the empty fallback, and scaling a frame to 0x0 throws out of the decoding loop, which
    // ends playback; an empty size therefore means the frame is copied at its own size, exactly as a detached
    // callback does
    final Dimension target = this.dimensionCallback.retrieve();
    final int targetWidth = target.getWidth();
    final int targetHeight = target.getHeight();
    final boolean empty = target.isEmpty();
    final boolean sameSize = targetWidth == width && targetHeight == height;

    try {
      if (empty || sameSize) {
        return this.fill(pooled, frame, width, height);
      }

      return this.scale(frame, pooled, targetWidth, targetHeight);
    } catch (final RuntimeException exception) {
      if (pooled != null) {
        this.pool.recycle(pooled);
      }

      throw exception;
    }
  }

  // Releases the image unscaled frames were copied into. The copier can still be used afterward.
  void release() {
    final @Nullable MatImageBuffer image = this.unscaled;
    this.unscaled = null;

    if (image != null) {
      image.release();
    }
  }

  private MatImageBuffer fill(final @Nullable MatImageBuffer reusable, final Frame frame, final int width, final int height) {
    final MatImageBuffer target = this.ensureSize(reusable, width, height);
    final ByteBuffer data = target.getData();
    FramePixels.copyBgr(frame, data);
    target.invalidateCache();
    return target;
  }

  // Scales a frame into an image of the target size, reading the pixels of the decoder in place when they live in
  // native memory, and through a copy of the frame otherwise.
  private MatImageBuffer scale(final Frame frame, final @Nullable MatImageBuffer pooled, final int width, final int height) {
    final @Nullable ByteBuffer plane = this.getNativePlane(frame);
    if (plane != null && this.fitsPlane(frame, plane)) {
      try (final Mat decoded = this.wrapDecodedPixels(frame, plane)) {
        return this.resize(decoded, pooled, width, height);
      }
    }

    final int frameWidth = frame.imageWidth;
    final int frameHeight = frame.imageHeight;
    final MatImageBuffer source = this.fill(this.unscaled, frame, frameWidth, frameHeight);
    this.unscaled = source;
    final Mat sourceMatrix = source.getMat();
    return this.resize(sourceMatrix, pooled, width, height);
  }

  private Mat wrapDecodedPixels(final Frame frame, final ByteBuffer plane) {
    final ByteBuffer whole = plane.duplicate();
    whole.clear();
    try (final BytePointer pixels = new BytePointer(whole)) {
      return new Mat(frame.imageHeight, frame.imageWidth, opencv_core.CV_8UC3, pixels, frame.imageStride);
    }
  }

  private @Nullable ByteBuffer getNativePlane(final Frame frame) {
    final Buffer @Nullable [] image = frame.image;
    if (image == null || image.length == 0) {
      return null;
    }

    final Buffer plane = image[0];
    if (plane instanceof final ByteBuffer bytes && bytes.isDirect()) {
      return bytes;
    }

    return null;
  }

  // Checks that a frame has a positive size and that its plane holds every row it claims, so OpenCV never reads past
  // the end of the memory of the decoder.
  private boolean fitsPlane(final Frame frame, final ByteBuffer plane) {
    final int width = frame.imageWidth;
    final int height = frame.imageHeight;
    final long stride = frame.imageStride;
    final long rowBytes = (long) width * FramePixels.BGR_CHANNELS;
    final long neededBytes = (height - 1L) * stride + rowBytes;
    final int capacity = plane.capacity();
    return width > 0 && height > 0 && stride >= rowBytes && capacity >= neededBytes;
  }

  private MatImageBuffer resize(final Mat source, final @Nullable MatImageBuffer reusable, final int width, final int height) {
    final MatImageBuffer target = this.ensureSize(reusable, width, height);
    final Mat targetMatrix = target.getMat();
    final int sourceWidth = source.cols();
    final int sourceHeight = source.rows();
    final boolean shrinking = width < sourceWidth || height < sourceHeight;
    final int interpolation = shrinking ? opencv_imgproc.INTER_AREA : opencv_imgproc.INTER_LINEAR;
    try (final Size size = new Size(width, height)) {
      opencv_imgproc.resize(source, targetMatrix, size, 0, 0, interpolation);
    }

    target.invalidateCache();
    return target;
  }

  private MatImageBuffer ensureSize(final @Nullable MatImageBuffer reusable, final int width, final int height) {
    if (reusable != null) {
      reusable.setSize(width, height);
      return reusable;
    }

    final Mat matrix = new Mat(height, width, opencv_core.CV_8UC3);
    final ImageBuffer created = ImageBuffer.mat(matrix);
    return (MatImageBuffer) created;
  }

  @VisibleForTesting
  boolean hasUnscaledCopy() {
    return this.unscaled != null;
  }

  @VisibleForTesting
  @Nullable MatImageBuffer getUnscaledCopy() {
    return this.unscaled;
  }
}
