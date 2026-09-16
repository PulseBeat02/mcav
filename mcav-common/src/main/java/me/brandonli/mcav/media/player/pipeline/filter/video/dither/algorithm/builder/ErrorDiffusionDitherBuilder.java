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

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.ErrorDiffusionDither;

/**
 * Builds error diffusion algorithms. Every call of {@code build()} creates a new algorithm. The temporal algorithm
 * remembers the previous frame of the video it dithers and must not be shared between players, so build one for
 * every player; the other algorithms are stateless and can be shared.
 *
 * @param <T> the type of algorithm the builder creates
 * @param <B> the type of the builder itself, for method chaining
 */
public interface ErrorDiffusionDitherBuilder<T extends ErrorDiffusionDither, B extends ErrorDiffusionDitherBuilder<T, B>>
  extends DitherAlgorithmBuilder<T, B> {
  /**
   * Sets the error diffusion algorithm. Defaults to {@link Algorithm#FILTER_LITE}.
   *
   * @param algorithm the algorithm
   * @return this builder
   */
  B withAlgorithm(final Algorithm algorithm);

  /**
   * Sets how far a pixel may drift per channel before its color is recomputed. Only used by
   * {@link Algorithm#TEMPORAL_FLOYD_STEINBERG}.
   *
   * @param threshold the threshold from 0 to 255
   * @return this builder
   */
  B withTemporalThreshold(final int threshold);

  /**
   * Sets the total error below which nothing is diffused. Only used by {@link Algorithm#TEMPORAL_FLOYD_STEINBERG}.
   *
   * @param threshold the threshold
   * @return this builder
   */
  B withErrorThreshold(final int threshold);

  /**
   * Sets the fraction of the error that is diffused. Only used by {@link Algorithm#TEMPORAL_FLOYD_STEINBERG}.
   *
   * @param strength the strength from 0 to 1
   * @return this builder
   */
  B withErrorStrength(final float strength);

  /**
   * The error diffusion algorithms that can be built.
   */
  enum Algorithm {
    /**
     * High-contrast diffusion that only spreads three quarters of the error.
     */
    ATKINSON,

    /**
     * Seven taps over two rows.
     */
    BURKES,

    /**
     * Three taps, the fastest algorithm and a good default.
     */
    FILTER_LITE,

    /**
     * The classic four-tap algorithm.
     */
    FLOYD_STEINBERG,

    /**
     * Floyd-Steinberg that stays stable between video frames. The algorithm holds the state of one video, so every
     * player needs its own.
     */
    TEMPORAL_FLOYD_STEINBERG,

    /**
     * Twelve taps over three rows, very smooth.
     */
    JARVIS_JUDICE_NINKE,

    /**
     * Twelve taps on a hexagonal pattern over four rows.
     */
    STEVENSON_ARCE,

    /**
     * Twelve taps over three rows, sharper than Jarvis, Judice, and Ninke.
     */
    STUCKI,
  }
}
