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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.RandomDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.UniformNoiseDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

final class DefaultRandomDitherBuilder implements RandomDitherBuilder {

  private static final int MAXIMUM_WEIGHT = 255;

  private DitherPalette palette;
  private int weight;

  DefaultRandomDitherBuilder() {
    this.palette = DitherPalette.DEFAULT_MAP_PALETTE;
    this.weight = RandomDither.NORMAL_WEIGHT;
  }

  @Override
  public RandomDither build() {
    return new UniformNoiseDither(this.palette, this.weight);
  }

  @Override
  public RandomDitherBuilder palette(final DitherPalette palette) {
    Preconditions.checkNotNull(palette, "Palette must not be null!");

    this.palette = palette;
    return this;
  }

  @Override
  public RandomDitherBuilder weight(final int weight) {
    Preconditions.checkArgument(weight >= 0 && weight <= MAXIMUM_WEIGHT, "Weight must be between 0 and 255 inclusive!");

    this.weight = weight;
    return this;
  }
}
