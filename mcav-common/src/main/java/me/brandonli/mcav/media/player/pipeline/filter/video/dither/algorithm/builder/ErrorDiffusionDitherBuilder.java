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
 * Defines a builder for constructing error diffusion dithering algorithms.
 *
 * @param <T> the concrete type of the {@link ErrorDiffusionDither} being built.
 * @param <B> the concrete type of the builder implementing this interface.
 */
public interface ErrorDiffusionDitherBuilder<T extends ErrorDiffusionDither, B extends ErrorDiffusionDitherBuilder<T, B>>
  extends DitherAlgorithmBuilder<T, B> {
  /** Error diffusion kernel variants. */
  enum Algorithm {
    /** Atkinson */
    ATKINSON,
    /** Burkes */
    BURKES,
    /** Filter Lite */
    FILTER_LITE,
    /** Floyd-Steinberg */
    FLOYD_STEINBERG,
    /** Temporally-coherent, strip-parallel Floyd-Steinberg */
    TEMPORAL_FLOYD_STEINBERG,
    /** Jarvis-Judice-Ninke */
    JARVIS_JUDICE_NINKE,
    /** Stevenson-Arce */
    STEVENSON_ARCE,
    /** Stucki */
    STUCKI,
  }

  /**
   * Sets the error diffusion kernel.
   *
   * @param algorithm the kernel to use
   * @return this builder
   */
  @SuppressWarnings("unchecked")
  default B withAlgorithm(final Algorithm algorithm) {
    this.setAlgorithm(algorithm);
    return (B) this;
  }

  /**
   * Sets the per-channel temporal skip threshold (only used with {@link Algorithm#TEMPORAL_FLOYD_STEINBERG}).
   *
   * @param threshold per-channel tolerance for reusing a previous palette index (≥ 0)
   * @return this builder
   */
  @SuppressWarnings("unchecked")
  default B withTemporalThreshold(final int threshold) {
    this.setTemporalThreshold(threshold);
    return (B) this;
  }

  /**
   * Sets the minimum total error below which diffusion is skipped (only used with
   * {@link Algorithm#TEMPORAL_FLOYD_STEINBERG}).
   *
   * @param threshold minimum {@code |ΔR|+|ΔG|+|ΔB|} to trigger diffusion (≥ 0)
   * @return this builder
   */
  @SuppressWarnings("unchecked")
  default B withErrorThreshold(final int threshold) {
    this.setErrorThreshold(threshold);
    return (B) this;
  }

  /**
   * Sets the fraction of quantisation error to diffuse (only used with
   * {@link Algorithm#TEMPORAL_FLOYD_STEINBERG}).
   *
   * @param strength diffusion strength in [0.0, 1.0]
   * @return this builder
   */
  @SuppressWarnings("unchecked")
  default B withErrorStrength(final float strength) {
    this.setErrorStrength(strength);
    return (B) this;
  }

  /** @param algorithm the kernel to use */
  void setAlgorithm(final Algorithm algorithm);

  /** @param threshold per-channel temporal skip tolerance */
  void setTemporalThreshold(final int threshold);

  /** @param threshold minimum total error to trigger diffusion */
  void setErrorThreshold(final int threshold);

  /** @param strength diffusion strength in [0.0, 1.0] */
  void setErrorStrength(final float strength);
}
