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
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.OrderedDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.OrderedDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.PixelMapper;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

final class DefaultOrderedDitherBuilder implements OrderedDitherBuilder {

  private DitherPalette palette;
  private PixelMapper mapper;

  DefaultOrderedDitherBuilder() {
    this.palette = DitherPalette.DEFAULT_MAP_PALETTE;
    this.mapper = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, BayerDither.NORMAL_2X2_MAXIMUM, PixelMapper.NORMAL_STRENGTH);
  }

  @Override
  public OrderedDither build() {
    return new OrderedDither(this.palette, this.mapper);
  }

  @Override
  public OrderedDitherBuilder palette(final DitherPalette palette) {
    Preconditions.checkNotNull(palette, "Palette must not be null!");

    this.palette = palette;
    return this;
  }

  @Override
  public OrderedDitherBuilder ditherMatrix(final PixelMapper mapper) {
    Preconditions.checkNotNull(mapper, "Pixel mapper must not be null!");

    this.mapper = mapper;
    return this;
  }
}
