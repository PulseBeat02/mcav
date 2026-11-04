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
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Builds error diffusion algorithms. Every call of {@code build()} creates a new algorithm. The temporal algorithm
 * remembers the previous frame of the video it dithers and must not be shared between players, so build one for
 * every player; the other algorithms are stateless and can be shared.
 *
 * <p>Builders are not thread-safe; use one per thread.
 *
 * @since 1.0.0
 */
public interface ErrorDiffusionDitherBuilder extends DitherAlgorithmBuilder {
  /**
   * Creates an error diffusion algorithm from the kernel, thresholds, strength and palette set on this builder.
   *
   * @return the algorithm
   * @since 1.0.0
   */
  @Override
  ErrorDiffusionDither build();

  /**
   * Sets the palette the error diffusion algorithm reduces images to. Defaults to the Minecraft map palette.
   *
   * @param palette the palette
   * @return this builder
   * @throws NullPointerException if {@code palette} is null
   * @since 1.0.0
   */
  @Override
  ErrorDiffusionDitherBuilder withPalette(final DitherPalette palette);

  /**
   * Sets the error diffusion algorithm. Defaults to {@link Algorithm#FILTER_LITE}.
   *
   * @param algorithm the algorithm
   * @return this builder
   * @throws NullPointerException if {@code algorithm} is null
   * @since 1.0.0
   */
  ErrorDiffusionDitherBuilder withAlgorithm(final Algorithm algorithm);

  /**
   * Sets how far a pixel may drift per channel before its color is recomputed. Only used by
   * {@link Algorithm#TEMPORAL_FLOYD_STEINBERG}.
   *
   * @param threshold the threshold from 0 to 255
   * @return this builder
   * @throws IllegalArgumentException if the threshold is outside 0 through 255
   * @since 1.0.0
   */
  ErrorDiffusionDitherBuilder withTemporalThreshold(final int threshold);

  /**
   * Sets the total error below which nothing is diffused. Only used by {@link Algorithm#TEMPORAL_FLOYD_STEINBERG}.
   *
   * @param threshold the sum of absolute channel errors, not negative, at or below which diffusion is skipped
   * @return this builder
   * @throws IllegalArgumentException if the threshold is negative
   * @since 1.0.0
   */
  ErrorDiffusionDitherBuilder withErrorThreshold(final int threshold);

  /**
   * Sets the fraction of the error that is diffused. Only used by {@link Algorithm#TEMPORAL_FLOYD_STEINBERG}.
   *
   * @param strength the strength from 0 to 1
   * @return this builder
   * @throws IllegalArgumentException if the strength is outside 0 through 1 or is NaN
   * @since 1.0.0
   */
  ErrorDiffusionDitherBuilder withErrorStrength(final float strength);

  /**
   * The error diffusion algorithms that can be built.
   *
   * <p>This enum is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  enum Algorithm {
    /**
     * High-contrast diffusion that only spreads three quarters of the error.
     *
     * @since 1.0.0
     */
    ATKINSON,

    /**
     * Seven taps over two rows.
     *
     * @since 1.0.0
     */
    BURKES,

    /**
     * Three taps, the fastest algorithm and a good default.
     *
     * @since 1.0.0
     */
    FILTER_LITE,

    /**
     * The classic four-tap algorithm.
     *
     * @since 1.0.0
     */
    FLOYD_STEINBERG,

    /**
     * Floyd-Steinberg that stays stable between video frames. The algorithm holds the state of one video, so every
     * player needs its own.
     *
     * @since 1.0.0
     */
    TEMPORAL_FLOYD_STEINBERG,

    /**
     * Twelve taps over three rows, very smooth.
     *
     * @since 1.0.0
     */
    JARVIS_JUDICE_NINKE,

    /**
     * Twelve taps on a hexagonal pattern over four rows.
     *
     * @since 1.0.0
     */
    STEVENSON_ARCE,

    /**
     * Twelve taps over three rows, sharper than Jarvis, Judice, and Ninke.
     *
     * @since 1.0.0
     */
    STUCKI,
  }
}
