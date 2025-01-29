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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither;

import me.brandonli.mcav.media.player.metadata.VideoMetadata;

/**
 * Represents a functional interface used to handle the result of a dithering operation.
 * This interface is specifically designed to process dithered video frame data and its associated metadata.
 * It serves as a callback mechanism, enabling custom post-processing or transfer of processed frames.
 * <p>
 * Implementing this interface allows integrations with video processing pipelines
 * to handle the output of dithering algorithms in a flexible and reusable manner.
 * <p>
 * The {@code process} method is expected to be implemented to define the behavior
 * for handling the dithered frame data along with its video metadata.
 */
@FunctionalInterface
public interface DitherResultStep {
  /**
   * Processes the dithered video output and its associated metadata.
   *
   * @param dithered a byte array representing the dithered video data
   * @param metadata the metadata object containing video properties such as width, height, bitrate, and frame rate
   */
  void process(final byte[] dithered, final VideoMetadata metadata);
}
