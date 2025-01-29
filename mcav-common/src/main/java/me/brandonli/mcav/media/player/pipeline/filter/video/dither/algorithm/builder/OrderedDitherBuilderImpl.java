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

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.OrderedDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.PixelMapper;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Implementation of the {@link OrderedDitherBuilder} interface for constructing instances
 * of {@link OrderedDither}.
 */
public class OrderedDitherBuilderImpl implements OrderedDitherBuilder<BayerDither, OrderedDitherBuilderImpl> {

  private DitherPalette palette = DitherPalette.DEFAULT_MAP_PALETTE;
  private PixelMapper ditherMatrix = PixelMapper.ofPixelMapper(
    BayerDither.NORMAL_2X2,
    BayerDither.NORMAL_2X2_MAX,
    PixelMapper.NORMAL_STRENGTH
  );

  /**
   * Default constructor for {@link OrderedDitherBuilderImpl}.
   */
  public OrderedDitherBuilderImpl() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public OrderedDither build() {
    return new OrderedDither(this.palette, this.ditherMatrix);
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
  public void setDitherMatrix(final PixelMapper matrix) {
    this.ditherMatrix = matrix;
  }
}
