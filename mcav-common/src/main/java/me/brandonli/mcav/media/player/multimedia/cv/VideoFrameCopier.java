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

// Copies frames into pooled images and scales those the decoder did not, such as OpenCV's reader; one decoding thread owns it.
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

    // read the size once: a detach in between answers empty, and an empty size copies the frame at its own size
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

  // The copier stays usable afterward.
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

  // Keeps OpenCV from reading past the end of the decoder's memory.
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
