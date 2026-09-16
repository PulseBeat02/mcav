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
 * Builds ordered dithering algorithms.
 *
 * @param <T> the type of algorithm the builder creates
 * @param <B> the type of the builder itself, for method chaining
 */
public interface OrderedDitherBuilder<T extends BayerDither, B extends OrderedDitherBuilder<T, B>> extends DitherAlgorithmBuilder<T, B> {
  /**
   * Sets the threshold pattern. Defaults to a 2x2 Bayer matrix at normal strength.
   *
   * @param mapper the threshold pattern
   * @return this builder
   */
  B withDitherMatrix(final PixelMapper mapper);
}
