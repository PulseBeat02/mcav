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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.PixelMapper;

/**
 * Defines a builder interface for the construction of {@code OrderedDither}.
 *
 * @param <T> the type of {@link BayerDither} that this builder will produce.
 * @param <B> the type of the builder itself, allowing for method chaining.
 */
public interface OrderedDitherBuilder<T extends BayerDither, B extends OrderedDitherBuilder<T, B>> extends DitherAlgorithmBuilder<T, B> {
  /**
   * Configures the builder with a specific {@link PixelMapper} representing a dither matrix
   * and returns the builder instance for method-chaining purposes.
   *
   * @param matrix the {@link PixelMapper} instance defining the dither matrix to be applied.
   *               This matrix determines how pixel data is transformed during the dithering process.
   * @return the builder instance after the dither matrix has been set.
   */
  @SuppressWarnings("unchecked")
  default B withDitherMatrix(final PixelMapper matrix) {
    this.setDitherMatrix(matrix);
    return (B) this;
  }

  /**
   * Sets the dither matrix to be used for ordered dithering.
   *
   * @param matrix the {@link PixelMapper} representing the dither matrix.
   *               The provided matrix defines how pixel values are transformed
   *               during the dithering process. It must not be null.
   */
  void setDitherMatrix(final PixelMapper matrix);
}
