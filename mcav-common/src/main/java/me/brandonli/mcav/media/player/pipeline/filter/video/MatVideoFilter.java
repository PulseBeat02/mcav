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

import com.google.common.base.Preconditions;
import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.image.MatImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * The base class of video filters implemented with OpenCV.
 *
 * <p>Subclasses implement {@link #modifyMat(Mat)} for operations that work in place on the 8-bit BGR matrix of
 * the frame, which covers most filters. Operations that cannot work in place, such as resizing or rotating, override
 * {@link #modifyImage(MatImageBuffer)} instead and write their result through
 * {@link MatImageBuffer#transformMat(java.util.function.BiConsumer)}, which reuses the spare matrix of the frame
 * instead of allocating one per frame. The base class invalidates the cached pixels of the frame after every
 * modification, so subclasses never have to.
 *
 * <p>Image buffers that are not backed by OpenCV are copied into a temporary matrix through their raw BGR pixels,
 * filtered, and copied back the same way if the filter changed them, which costs two copies per frame.
 *
 * <p>The base class keeps no state; each subclass states its own thread safety.
 *
 * @since 1.0.0
 */
public abstract class MatVideoFilter implements VideoFilter {

  /**
   * Constructs a new filter.
   *
   * @since 1.0.0
   */
  protected MatVideoFilter() {}

  /**
   * Applies the filter to a frame. Frames backed by OpenCV are modified in place through
   * {@link #modifyImage(MatImageBuffer)}; other frames are converted to a temporary matrix first, and the result is
   * written back only if the filter reported a change.
   *
   * @param samples  the frame to process
   * @param metadata the metadata of the original video, which OpenCV filters do not use
   * @return true if the filter changed the frame or may have changed it, false if it left the frame untouched
   * @throws NullPointerException  if the frame is null
   * @throws IllegalStateException if the image has been released
   * @since 1.0.0
   */
  @Override
  public boolean applyFilter(final ImageBuffer samples, final OriginalVideoMetadata metadata) {
    Preconditions.checkNotNull(samples, "Samples must not be null!");

    if (samples instanceof final MatImageBuffer matrixBuffer) {
      return this.modifyImage(matrixBuffer);
    }

    return this.applyToTemporaryMat(samples);
  }

  // Applies the filter to a frame that is not backed by OpenCV, through a temporary copy of the frame that is released
  // afterward. Both copies go through the raw BGR pixels every image buffer stores, see ImageBuffer.getData(), so
  // neither needs a conversion or an intermediate array.
  private boolean applyToTemporaryMat(final ImageBuffer samples) {
    final int width = samples.getWidth();
    final int height = samples.getHeight();
    final ByteBuffer data = samples.getData();
    // a slice, not a rewound duplicate: getData() promises a view of the pixels, so the pixels are what is left
    // between the position and the limit. Rewinding moves the position back but keeps the limit, which turns a view
    // that starts past zero into one that is too long, and the image buffer below then rejects it.
    final ByteBuffer pixels = data.slice();
    try (final ImageBuffer temporary = ImageBuffer.bytes(pixels, width, height)) {
      final MatImageBuffer temporaryMatrixBuffer = (MatImageBuffer) temporary;
      final boolean modified = this.modifyImage(temporaryMatrixBuffer);
      if (modified) {
        writeBack(temporaryMatrixBuffer, samples);
      }

      return modified;
    }
  }

  private static void writeBack(final MatImageBuffer result, final ImageBuffer samples) {
    final int width = result.getWidth();
    final int height = result.getHeight();
    final ByteBuffer data = result.getData();
    samples.updateData(data, width, height);
  }

  // Copies an image into a new 8-bit BGR matrix owned by the caller, for filters that keep an image, such as an
  // overlay, beyond the lifetime of the buffer it came from. Images that are not backed by OpenCV are converted through
  // a temporary buffer, which is released before this method returns.
  static Mat copyToMat(final ImageBuffer image) {
    if (image instanceof final MatImageBuffer matrixBuffer) {
      final Mat matrix = matrixBuffer.getMat();
      return matrix.clone();
    }

    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    try (final ImageBuffer converted = ImageBuffer.buffer(pixels, width, height)) {
      final MatImageBuffer convertedMatrixBuffer = (MatImageBuffer) converted;
      final Mat matrix = convertedMatrixBuffer.getMat();
      return matrix.clone();
    }
  }

  /**
   * Applies the filter to an OpenCV-backed frame. The default implementation calls {@link #modifyMat(Mat)} with
   * the matrix of the frame and invalidates the cached pixels if it reported a change.
   *
   * @param image the frame to modify
   * @return true if the frame was modified, false if it was left untouched
   * @since 1.0.0
   */
  protected boolean modifyImage(final MatImageBuffer image) {
    final Mat matrix = image.getMat();
    final boolean modified = this.modifyMat(matrix);
    if (modified) {
      image.invalidateCache();
    }

    return modified;
  }

  /**
   * Applies the filter in place to the 8-bit BGR matrix of a frame. The matrix must keep its size and type;
   * filters that cannot do that override {@link #modifyImage(MatImageBuffer)} instead.
   *
   * @param matrix the matrix of the frame
   * @return true if the matrix was modified, false if it was left untouched
   * @since 1.0.0
   */
  protected abstract boolean modifyMat(final Mat matrix);
}
