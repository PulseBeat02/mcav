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
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.OrderedDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.PixelMapper;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * The default {@link OrderedDitherBuilder}.
 */
public class OrderedDitherBuilderImpl implements OrderedDitherBuilder<BayerDither, OrderedDitherBuilderImpl> {

  private DitherPalette palette;
  private PixelMapper mapper;

  /**
   * Constructs a builder that creates a 2x2 Bayer dither on the Minecraft map palette.
   */
  public OrderedDitherBuilderImpl() {
    this.palette = DitherPalette.DEFAULT_MAP_PALETTE;
    this.mapper = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, BayerDither.NORMAL_2X2_MAX, PixelMapper.NORMAL_STRENGTH);
  }

  /**
   * Creates an ordered dithering algorithm with the chosen palette and threshold pattern.
   *
   * @return the algorithm, which is stateless and can be shared
   */
  @Override
  public OrderedDither build() {
    return new OrderedDither(this.palette, this.mapper);
  }

  /**
   * Sets the palette the algorithm reduces images to. Defaults to the Minecraft map palette.
   *
   * @param palette the palette
   * @return this builder
   */
  @Override
  public OrderedDitherBuilderImpl withPalette(final DitherPalette palette) {
    Preconditions.checkNotNull(palette, "Palette must not be null");
    this.palette = palette;
    return this;
  }

  /**
   * Sets the threshold pattern. Defaults to a 2x2 Bayer matrix at normal strength.
   *
   * @param mapper the threshold pattern
   * @return this builder
   */
  @Override
  public OrderedDitherBuilderImpl withDitherMatrix(final PixelMapper mapper) {
    Preconditions.checkNotNull(mapper, "Pixel mapper must not be null");
    this.mapper = mapper;
    return this;
  }
}
