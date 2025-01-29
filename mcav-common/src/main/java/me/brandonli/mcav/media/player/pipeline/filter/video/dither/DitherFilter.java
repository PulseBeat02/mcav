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
 * and a result-processing callback.
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
