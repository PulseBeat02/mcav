/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.source;

import java.nio.IntBuffer;
import java.util.function.Supplier;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;

/**
 * Represents a source of video frame data. This interface extends {@code DynamicSource},
 * indicating that the resources or data it provides can change dynamically over time.
 * It provides methods for accessing frame data as well as metadata about the video.
 */
public interface FrameSource extends DynamicSource {
  /**
   * Provides a supplier for accessing video frame data as a wrapped {@code IntBuffer}.
   * This method retrieves an array of integer frame samples from the underlying frame source
   * using {@link #supplyFrameSamples()}, and wraps it into an {@code IntBuffer} for easier processing.
   *
   * @return a {@code Supplier<IntBuffer>} that provides video frame data wrapped in an {@code IntBuffer}.
   */
  default Supplier<IntBuffer> getFrameSupplier() {
    return () -> {
      final int[] frameSamples = this.supplyFrameSamples().getFrameSamples();
      return IntBuffer.wrap(frameSamples);
    };
  }

  static FrameSource image(final ImageSupplier images, final VideoMetadata metadata) {
    return new FrameSourceImpl(images, metadata);
  }

  /**
   * Provides a supplier for accessing frame sample data in the form of an array of integers.
   * This method is used to obtain raw frame samples for video processing or rendering.
   *
   * @return a {@code Supplier} that supplies an array of integers representing the frame sample data.
   */
  SampleSupplier supplyFrameSamples();

  /**
   * Retrieves metadata associated with the video, such as its width, height, bitrate,
   * and frame rate. This information provides details about the video's characteristics
   * and properties.
   *
   * @return a {@code VideoMetadata} instance containing information about the video,
   * including its dimensions, bitrate, and frame rate
   */
  VideoMetadata getVideoMetadata();

  /**
   * {@inheritDoc}
   */
  @Override
  default String getName() {
    return "frame-source";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  default String getResource() {
    return this.getFrameSupplier().toString();
  }

  /**
   * Creates a new {@code FrameSource} implementation using the provided frame samples supplier
   * and video metadata.
   *
   * @param supplier a {@code Supplier} that provides frame samples as an {@code int[]} array
   *                 representing the pixel data of frames
   * @param metadata an instance of {@code VideoMetadata} containing video-specific properties
   *                 such as width, height, bitrate, and frame rate
   * @return a new {@code FrameSource} instance that encapsulates the provided supplier and metadata
   */
  static FrameSource supplier(final SampleSupplier supplier, final VideoMetadata metadata) {
    return new FrameSourceImpl(supplier, metadata);
  }
}
