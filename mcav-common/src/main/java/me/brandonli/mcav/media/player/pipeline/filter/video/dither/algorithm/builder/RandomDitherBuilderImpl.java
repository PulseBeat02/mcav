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

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDitherImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * The default {@link RandomDitherBuilder}.
 */
public class RandomDitherBuilderImpl implements RandomDitherBuilder<RandomDither, RandomDitherBuilderImpl> {

  private DitherPalette palette;
  private int weight;

  /**
   * Constructs a builder that creates a normal-weight random dither on the Minecraft map palette.
   */
  public RandomDitherBuilderImpl() {
    this.palette = DitherPalette.DEFAULT_MAP_PALETTE;
    this.weight = RandomDither.NORMAL_WEIGHT;
  }

  /**
   * Creates a random dithering algorithm with the chosen palette and weight. The algorithm gives every thread its own
   * random number generator, so it can be shared.
   *
   * @return the algorithm
   */
  @Override
  public RandomDither build() {
    return new RandomDitherImpl(this.palette, this.weight);
  }

  /**
   * Sets the palette the algorithm reduces images to. Defaults to the Minecraft map palette.
   *
   * @param palette the palette
   * @return this builder
   */
  @Override
  public RandomDitherBuilderImpl withPalette(final DitherPalette palette) {
    Preconditions.checkNotNull(palette, "Palette must not be null");
    this.palette = palette;
    return this;
  }

  /**
   * Sets the largest noise value added to or subtracted from a channel. Defaults to
   * {@link RandomDither#NORMAL_WEIGHT}.
   *
   * @param weight the weight from 0 to 255
   * @return this builder
   * @throws IllegalArgumentException if the weight is outside the range from 0 to 255
   */
  @Override
  public RandomDitherBuilderImpl withWeight(final int weight) {
    Preconditions.checkArgument(weight >= 0 && weight <= 255, "Weight must be between 0 and 255");
    this.weight = weight;
    return this;
  }
}
