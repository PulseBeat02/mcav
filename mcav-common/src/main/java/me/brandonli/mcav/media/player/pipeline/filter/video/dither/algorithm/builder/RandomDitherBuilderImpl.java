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

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDitherImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Implementation of the {@link RandomDitherBuilder} interface for constructing instances.
 */
public class RandomDitherBuilderImpl implements RandomDitherBuilder<RandomDither, RandomDitherBuilderImpl> {

  private DitherPalette palette = DitherPalette.DEFAULT_MAP_PALETTE;
  private int weight = RandomDither.NORMAL_WEIGHT;

  /**
   * Default constructor for {@link RandomDitherBuilderImpl}.
   */
  public RandomDitherBuilderImpl() {
    // no-op
  }

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
  public void setPalette(final DitherPalette palette) {
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
