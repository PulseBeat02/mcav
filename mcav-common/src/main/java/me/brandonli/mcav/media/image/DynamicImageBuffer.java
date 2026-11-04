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
import java.util.List;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.http.DownloadException;

/**
 * An animated image, such as a GIF, decoded into its individual frames.
 *
 * <p>All frames are decoded up front and kept in memory, which makes animations easy to play in a loop but
 * unsuitable for long videos; use a video player for those. The frames are owned by the animation and released
 * together with it.
 *
 * <p>Implementations are not thread-safe.
 *
 * @since 1.0.0
 */
public interface DynamicImageBuffer extends Image {
  /**
   * Returns every frame of the animation in playback order.
   *
   * @return an unmodifiable list of borrowed frames; the images must not be released individually, and the image data becomes invalid when the animation closes
   * @since 1.0.0
   */
  List<ImageBuffer> getFrames();

  /**
   * Returns the playback speed of the animation.
   *
   * @return the decoder's positive finite reported rate in frames per second, or a rate derived from
   *         frame timestamps; the fallback is 10 frames per second when neither is available
   * @since 1.0.0
   */
  float getFrameRate();

  /**
   * Returns one frame of the animation.
   *
   * @param index the index of the frame, from 0 to {@code getFrameCount() - 1}
   * @return the frame, which must not be released individually
   * @throws IndexOutOfBoundsException if {@code index} is negative or at least {@link #getFrameCount()}
   * @since 1.0.0
   */
  ImageBuffer getFrame(final int index);

  /**
   * Returns the number of frames of the animation.
   *
   * @return the frame count
   * @since 1.0.0
   */
  int getFrameCount();

  /**
   * Decodes an animated image file.
   *
   * @param source the image file
   * @return the decoded animation
   * @throws ImageDecodingException   if the file cannot be decoded
   * @throws IllegalArgumentException if decoding succeeds but yields no image frames
   * @throws NullPointerException     if {@code source} is null
   * @since 1.0.0
   */
  static DynamicImageBuffer path(final FileSource source) {
    Preconditions.checkNotNull(source, "Source must not be null!");

    return new FfmpegDynamicImageBuffer(source);
  }

  /**
   * Downloads and decodes an animated image.
   *
   * @param source the URL of the image
   * @return the decoded animation
   * @throws ImageDecodingException   if the downloaded file cannot be decoded
   * @throws DownloadException        if the image cannot be downloaded, including when it is larger than
   *                                  {@link me.brandonli.mcav.utils.IoUtils#MAXIMUM_IMAGE_BYTES}
   * @throws FileOperationException   if the downloaded image cannot be moved into the cache folder
   * @throws IllegalArgumentException if decoding succeeds but yields no image frames
   * @throws NullPointerException     if {@code source} is null
   * @since 1.0.0
   */
  static DynamicImageBuffer uri(final UriSource source) {
    Preconditions.checkNotNull(source, "Source must not be null!");

    return new FfmpegDynamicImageBuffer(source);
  }
}
