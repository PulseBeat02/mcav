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
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.NearestDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.NearestDitherImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * The default {@link NearestDitherBuilder}.
 */
public class NearestDitherBuilderImpl implements NearestDitherBuilder<NearestDither, NearestDitherBuilderImpl> {

  private DitherPalette palette;

  /**
   * Constructs a builder that creates a nearest color algorithm on the Minecraft map palette.
   */
  public NearestDitherBuilderImpl() {
    this.palette = DitherPalette.DEFAULT_MAP_PALETTE;
  }

  /**
   * Creates a nearest color algorithm for the chosen palette.
   *
   * @return the algorithm, which is stateless and can be shared
   */
  @Override
  public NearestDither build() {
    return new NearestDitherImpl(this.palette);
  }

  /**
   * Sets the palette the algorithm reduces images to. Defaults to the Minecraft map palette.
   *
   * @param palette the palette
   * @return this builder
   */
  @Override
  public NearestDitherBuilderImpl withPalette(final DitherPalette palette) {
    Preconditions.checkNotNull(palette, "Palette must not be null");
    this.palette = palette;
    return this;
  }
}
