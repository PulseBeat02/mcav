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
import java.io.IOException;
import java.util.List;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;

/**
 * An animated image, such as a GIF, decoded into its individual frames.
 *
 * <p>All frames are decoded up front and kept in memory, which makes animations easy to play in a loop but
 * unsuitable for long videos; use a video player for those. The frames are owned by the animation and released
 * together with it.
 */
public interface DynamicImageBuffer extends Image {
  /**
   * Gets every frame of the animation in playback order.
   *
   * @return the frames, which must not be released individually
   */
  List<ImageBuffer> getFrames();

  /**
   * Gets the playback speed of the animation.
   *
   * @return the frame rate in frames per second
   */
  float getFrameRate();

  /**
   * Gets one frame of the animation.
   *
   * @param index the index of the frame, from 0 to {@code getFrameCount() - 1}
   * @return the frame, which must not be released individually
   */
  ImageBuffer getFrame(final int index);

  /**
   * Gets the number of frames of the animation.
   *
   * @return the frame count
   */
  int getFrameCount();

  /**
   * Decodes an animated image file.
   *
   * @param source the image file
   * @return the decoded animation
   * @throws IOException if the file cannot be decoded
   */
  static DynamicImageBuffer path(final FileSource source) throws IOException {
    Preconditions.checkNotNull(source, "Source must not be null");
    return new DynamicImageBufferImpl(source);
  }

  /**
   * Downloads and decodes an animated image.
   *
   * @param source the URL of the image
   * @return the decoded animation
   * @throws IOException if the image cannot be downloaded or decoded
   */
  static DynamicImageBuffer uri(final UriSource source) throws IOException {
    Preconditions.checkNotNull(source, "Source must not be null");
    return new DynamicImageBufferImpl(source);
  }
}
