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

import java.io.IOException;
import java.util.List;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;

/**
 * Represents a dynamic image, which is a sequence of static images (frames) that can be displayed
 * in a specific order.
 * <p>
 * This interface provides methods to retrieve the frames, frame rate, and the total number of frames
 * in the dynamic image.
 */
public interface DynamicImageBuffer extends Image {
  /**
   * Retrieves the frames of the dynamic image.
   *
   * @return a list of static images representing the frames
   */
  List<ImageBuffer> getFrames();

  /**
   * Retrieves the frame rate of the dynamic image.
   *
   * @return the frame rate in frames per second
   */
  float getFrameRate();

  /**
   * Retrieves a specific frame from the dynamic image.
   *
   * @param index the index of the frame to retrieve
   * @return the static image at the specified index
   */
  ImageBuffer getFrame(int index);

  /**
   * Retrieves the total number of frames in the dynamic image.
   *
   * @return the total number of frames
   */
  int getFrameCount();

  /**
   * Creates a new instance of {@link DynamicImageBuffer} from a {@link FileSource}.
   *
   * @param source the file source to create the dynamic image from
   * @return a new instance of {@link DynamicImageBuffer}
   * @throws IOException if an I/O error occurs
   */
  static DynamicImageBuffer path(final FileSource source) throws IOException {
    return new DynamicImageBufferImpl(source);
  }

  /**
   * Creates a new instance of {@link DynamicImageBuffer} from a {@link UriSource}.
   *
   * @param source the URI source to create the dynamic image from
   * @return a new instance of {@link DynamicImageBuffer}
   * @throws IOException if an I/O error occurs
   */
  static DynamicImageBuffer uri(final UriSource source) throws IOException {
    return new DynamicImageBufferImpl(source);
  }
}
