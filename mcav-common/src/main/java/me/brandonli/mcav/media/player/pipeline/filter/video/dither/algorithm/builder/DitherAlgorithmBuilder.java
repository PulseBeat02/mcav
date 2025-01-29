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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.Palette;

/**
 * DitherAlgorithmBuilder serves as a generic interface for constructing instances of
 * classes that implement the DitherAlgorithm interface. It provides a fluent API
 * for configuring a palette and other parameters required for building specific
 * dithering algorithms.
 *
 * @param <T> the type of DitherAlgorithm to be constructed.
 * @param <B> the type of the builder interface extending DitherAlgorithmBuilder.
 */
public interface DitherAlgorithmBuilder<T extends DitherAlgorithm, B extends DitherAlgorithmBuilder<T, B>> {
  /**
   * Constructs and returns an instance of the dither algorithm with the configured parameters.
   *
   * @return an instance of the dither algorithm corresponding to the parameters specified in the builder
   */
  T build();

  /**
   * Sets the specified {@link Palette} for this builder and returns the builder instance
   * for method-chaining purposes.
   *
   * @param palette the {@link Palette} to be used by the builder. This determines the set of colors
   *                to be utilized by the constructed dithering algorithm.
   * @return the builder instance after the palette has been set.
   */
  @SuppressWarnings("unchecked")
  default B withPalette(final Palette palette) {
    this.setPalette(palette);
    return (B) this;
  }

  /**
   * Configures the builder with a specific palette. The palette defines the set of colors
   * used in the dithering algorithm and impacts how the colors are mapped during processing.
   *
   * @param palette the Palette instance representing the set of colors to be used.
   *                If null, a default palette may be used depending on the implementation.
   */
  void setPalette(final Palette palette);
}
