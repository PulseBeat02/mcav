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

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.NearestDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Builds nearest color algorithms, which have no settings besides the palette.
 *
 * <p>Builders are not thread-safe; use one per thread.
 *
 * @since 1.0.0
 */
public interface NearestDitherBuilder extends DitherAlgorithmBuilder {
  /**
   * Creates a nearest color algorithm from the palette set on this builder.
   *
   * @return the algorithm
   * @since 1.0.0
   */
  @Override
  NearestDither build();

  /**
   * Sets the palette the nearest color algorithm reduces images to. Defaults to the Minecraft map palette.
   *
   * @param palette the palette
   * @return this builder
   * @throws NullPointerException if {@code palette} is null
   * @since 1.0.0
   */
  @Override
  NearestDitherBuilder palette(final DitherPalette palette);
}
