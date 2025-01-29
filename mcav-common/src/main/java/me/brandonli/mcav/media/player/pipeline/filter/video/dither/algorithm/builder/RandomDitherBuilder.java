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

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDither;

/**
 * The RandomDitherBuilder interface defines a fluent API for constructing
 * instances of classes that implement the RandomDither algorithm. This builder interface
 * extends the DitherAlgorithmBuilder, providing additional functionality for setting
 * and configuring the degree of randomness (weight) in the dithering algorithm.
 *
 * @param <T> the type parameter representing an implementation of RandomDither to be built.
 * @param <B> the type parameter representing the concrete builder implementation extending RandomDitherBuilder.
 */
public interface RandomDitherBuilder<T extends RandomDither, B extends RandomDitherBuilder<T, B>> extends DitherAlgorithmBuilder<T, B> {
  /**
   * Configures the builder with a specific weight for the randomness factor
   * in the dithering process and returns the builder instance for method-chaining purposes.
   *
   * @param weight the degree of randomness to be applied in the dithering algorithm. A higher value
   *               represents a greater degree of randomness, influencing the output of the dithering process.
   * @return the builder instance after the randomness weight has been set.
   */
  @SuppressWarnings("unchecked")
  default B withWeight(final int weight) {
    this.setWeight(weight);
    return (B) this;
  }

  /**
   * Sets the weight parameter used to configure the degree of randomness in the dithering algorithm.
   * The weight value typically controls how strongly the random noise influences the output.
   *
   * @param weight the degree of randomness to be applied in the dithering process.
   *               Higher values generally result in a more pronounced randomization effect.
   */
  void setWeight(int weight);
}
