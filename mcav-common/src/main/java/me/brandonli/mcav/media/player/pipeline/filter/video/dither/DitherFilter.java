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

import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;

/**
 * A filter that applies a dithering effect to video frames using a specified dithering algorithm
 * and a result-processing callback. This class is designed to transform video frame data into
 * dithered byte arrays while enabling custom handling of the processed output.
 * <p>
 * The `DitherFilter` is immutable and operates using the provided instances of {@link DitherAlgorithm}
 * and {@link DitherResultStep}. It processes static video images (`StaticImage`) and associates them
 * with metadata from the input video, enabling frame-specific transformations.
 * <p>
 * Core Features:
 * - Utilizes a user-defined dithering algorithm to transform video frames.
 * - Handles the output of dithering through a callback mechanism.
 * - Processes video metadata to facilitate flexible transformations.
 * <p>
 * To construct an instance of this filter, use the {@code dither} factory method, which accepts the
 * necessary algorithm and callback as parameters.
 * <p>
 * This filter implements the {@link VideoFilter} interface, making it compatible with video processing
 * pipelines that support custom filter chaining or execution.
 * <p>
 * Thread-safety:
 * This class is thread-safe as long as the provided {@link DitherAlgorithm} and {@link DitherResultStep}
 * are implemented in a thread-safe manner.
 */
public final class DitherFilter implements VideoFilter {

  private final DitherAlgorithm algorithm;
  private final DitherResultStep callback;

  DitherFilter(final DitherAlgorithm algorithm, final DitherResultStep callback) {
    this.algorithm = algorithm;
    this.callback = callback;
  }

  /**
   * A factory method for creating a {@link VideoFilter} that applies a dithering effect to video frames.
   * The filter uses the specified {@link DitherAlgorithm} for transforming video frame data and processes
   * the dithered result via the provided {@link DitherResultStep} callback.
   *
   * @param algorithm the dithering algorithm to be used for the transformation of video frames
   * @param callback  the callback to handle the dithered video output and its associated metadata
   * @return a {@link VideoFilter} instance that applies the specified dithering algorithm and uses the callback for result processing
   */
  public static VideoFilter dither(final DitherAlgorithm algorithm, final DitherResultStep callback) {
    return new DitherFilter(algorithm, callback);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final ImageBuffer samples, final VideoMetadata metadata) {
    final byte[] bytes = this.algorithm.ditherIntoBytes(samples);
    this.callback.process(bytes, metadata);
  }
}
