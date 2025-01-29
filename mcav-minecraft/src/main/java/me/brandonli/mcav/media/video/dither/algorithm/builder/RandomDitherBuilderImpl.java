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

import me.brandonli.mcav.media.video.dither.algorithm.random.RandomDither;
import me.brandonli.mcav.media.video.dither.algorithm.random.RandomDitherImpl;
import me.brandonli.mcav.media.video.dither.palette.Palette;

/**
 * The {@code RandomDitherBuilderImpl} class is an implementation of the {@code RandomDitherBuilder}
 * interface, designed to construct instances of the {@code RandomDither} dithering algorithm.
 * This builder enables the configuration of the dithering parameters, such as the color palette
 * and the randomness weight, before creating a {@code RandomDither} object.
 * <p>
 * This implementation follows the builder design pattern, providing methods to set the
 * required parameters and ensuring a fluent API for creating dither configurations.
 * <p>
 * The {@code RandomDitherBuilderImpl} class encapsulates two main parameters:
 * - {@code Palette palette}: Defines the color palette used in the dithering process.
 * - {@code int weight}: Represents the degree of randomness in the dithering algorithm.
 */
public class RandomDitherBuilderImpl implements RandomDitherBuilder<RandomDither, RandomDitherBuilderImpl> {

  private Palette palette = Palette.DEFAULT_MAP_PALETTE;
  private int weight = RandomDither.NORMAL_WEIGHT;

  /**
   * {@inheritDoc}
   */
  @Override
  public RandomDither build() {
    return new RandomDitherImpl(this.palette, this.weight);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setPalette(final Palette palette) {
    this.palette = palette;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setWeight(final int weight) {
    this.weight = weight;
  }
}
