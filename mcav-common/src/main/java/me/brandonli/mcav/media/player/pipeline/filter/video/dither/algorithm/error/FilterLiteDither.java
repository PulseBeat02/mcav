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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Filter Lite error diffusion, also known as Sierra Lite. It spreads the error over only three neighbors, which
 * makes it the fastest error diffusion while looking almost identical to Floyd-Steinberg. This is the recommended
 * algorithm for video.
 */
public final class FilterLiteDither extends ErrorDiffusionDither {

  /**
   * Constructs a new Filter Lite algorithm for the specified palette.
   *
   * @param palette the palette to reduce images to
   */
  public FilterLiteDither(final DitherPalette palette) {
    super(palette, DiffusionKernel.FILTER_LITE);
  }

  /**
   * Constructs a new Filter Lite algorithm for the Minecraft map palette.
   */
  public FilterLiteDither() {
    this(DitherPalette.DEFAULT_MAP_PALETTE);
  }
}
