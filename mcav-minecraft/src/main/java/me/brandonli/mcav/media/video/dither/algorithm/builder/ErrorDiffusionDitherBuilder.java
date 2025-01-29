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
package me.brandonli.mcav.media.video.dither.algorithm.builder;

import me.brandonli.mcav.media.video.dither.algorithm.error.ErrorDiffusionDither;

/**
 * The ErrorDiffusionDitherBuilder interface defines a builder for constructing
 * error diffusion dithering algorithms, which distribute quantization errors
 * to neighboring pixels to create a visually smoother result. It extends
 * {@link DitherAlgorithmBuilder} to allow configuration of a palette
 * and an error diffusion algorithm.
 *
 * @param <T> the concrete type of the {@link ErrorDiffusionDither} being built.
 * @param <B> the concrete type of the builder implementing this interface.
 */
public interface ErrorDiffusionDitherBuilder<T extends ErrorDiffusionDither, B extends ErrorDiffusionDitherBuilder<T, B>>
  extends DitherAlgorithmBuilder<T, B> {
  /**
   * Enum representing various error diffusion algorithms that can be used in dithering.
   * Each algorithm has its own unique method of distributing quantization errors to
   * neighboring pixels, affecting the visual quality of the final image.
   */
  enum Algorithm {
    ATKINSON,
    BURKES,
    FILTER_LITE,
    FLOYD_STEINBERG,
    JARVIS_JUDICE_NINKE,
    STEVENSON_ARCE,
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
   * in the dithering process. The algorithm determines how quantization errors
   * are propagated to neighboring pixels, affecting the visual quality of the
   * final image.
   *
   * @param algorithm the error diffusion algorithm to use. Valid values are defined
   *                  in the {@link Algorithm} enum, such as ATKINSON, FLOYD_STEINBERG,
   *                  JARVIS_JUDICE_NINKE, and others.
   */
  void setAlgorithm(final Algorithm algorithm);
}
