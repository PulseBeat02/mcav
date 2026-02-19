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

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * DitherAlgorithmBuilder serves as a generic interface for constructing instances of
 * classes that implement the DitherAlgorithm interface.
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
   * Sets the specified {@link DitherPalette} for this builder and returns the builder instance
   * for method-chaining purposes.
   *
   * @param palette the {@link DitherPalette} to be used by the builder. This determines the set of colors
   *                to be utilized by the constructed dithering algorithm.
   * @return the builder instance after the palette has been set.
   */
  @SuppressWarnings("unchecked")
  default B withPalette(final DitherPalette palette) {
    this.setPalette(palette);
    return (B) this;
  }

  /**
   * Configures the builder with a specific palette.
   *
   * @param palette the Palette instance representing the set of colors to be used.
   *                If null, a default palette may be used depending on the implementation.
   */
  void setPalette(final DitherPalette palette);

  /**
   * Sets the per-channel temporal skip threshold. Only honoured by builders that support
   * temporal coherence (e.g. {@link me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.ErrorDiffusionDitherBuilder}).
   *
   * @param threshold per-channel tolerance for reusing a previous palette index (≥ 0)
   * @return this builder
   */
  @SuppressWarnings("unchecked")
  default B withTemporalThreshold(final int threshold) {
    return (B) this;
  }

  /**
   * Sets the minimum total error below which diffusion is skipped. Only honoured by builders
   * that support temporal coherence.
   *
   * @param threshold minimum {@code |ΔR|+|ΔG|+|ΔB|} to trigger diffusion (≥ 0)
   * @return this builder
   */
  @SuppressWarnings("unchecked")
  default B withErrorThreshold(final int threshold) {
    return (B) this;
  }

  /**
   * Sets the fraction of quantisation error to diffuse. Only honoured by builders that support
   * temporal coherence.
   *
   * @param strength diffusion strength in [0.0, 1.0]; defaults to
   *                 {@link TemporalDitherAlgorithm#DEFAULT_ERROR_STRENGTH}
   * @return this builder
   */
  @SuppressWarnings("unchecked")
  default B withErrorStrength(final float strength) {
    return (B) this;
  }
}
