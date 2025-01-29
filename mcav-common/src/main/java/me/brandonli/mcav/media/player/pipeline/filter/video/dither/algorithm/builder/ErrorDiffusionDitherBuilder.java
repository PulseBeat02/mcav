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
  /**
   * Enum representing various error diffusion algorithms that can be used in error-diffusion dithering.
   */
  enum Algorithm {
    /** Atkinson **/
    ATKINSON,

    /** Burkes **/
    BURKES,

    /** Filter Lite **/
    FILTER_LITE,

    /** Floyd-Steinberg **/
    FLOYD_STEINBERG,

    /** Jarvis-Judice-Ninke **/
    JARVIS_JUDICE_NINKE,

    /** Stevenson-Arce **/
    STEVENSON_ARCE,

    /** Stucki **/
    STUCKI,
  }

  /**
   * Configures the builder with the specified error diffusion algorithm and returns the builder instance
   * for method-chaining purposes.
   *
   * @param algorithm the error diffusion algorithm to be used, represented by an enum {@link Algorithm}.
   *                  This determines the algorithm's behavior for distributing quantization errors.
   * @return the builder instance after the algorithm has been set.
   */
  @SuppressWarnings("unchecked")
  default B withAlgorithm(final Algorithm algorithm) {
    this.setAlgorithm(algorithm);
    return (B) this;
  }

  /**
   * Configures the builder with a specific error diffusion algorithm to be used
   * in the dithering process.
   *
   * @param algorithm the error diffusion algorithm to use. Valid values are defined
   *                  in the {@link Algorithm} enum, such as ATKINSON, FLOYD_STEINBERG,
   *                  JARVIS_JUDICE_NINKE, and others.
   */
  void setAlgorithm(final Algorithm algorithm);
}
