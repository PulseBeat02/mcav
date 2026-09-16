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
 * Atkinson error diffusion, the algorithm of the original Macintosh. It diffuses only three quarters of the error,
 * which increases contrast and gives a distinctive, slightly washed-out look in very light and very dark areas.
 */
public final class AtkinsonDither extends ErrorDiffusionDither {

  /**
   * Constructs a new Atkinson algorithm for the specified palette.
   *
   * @param palette the palette to reduce images to
   */
  public AtkinsonDither(final DitherPalette palette) {
    super(palette, DiffusionKernel.ATKINSON);
  }

  /**
   * Constructs a new Atkinson algorithm for the Minecraft map palette.
   */
  public AtkinsonDither() {
    this(DitherPalette.DEFAULT_MAP_PALETTE);
  }
}
