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
 * Stevenson and Arce error diffusion, which spreads the error over twelve neighbors on a hexagonal pattern across
 * four rows. It produces the least visible patterns of all kernels at the highest cost.
 */
public final class StevensonArceDither extends ErrorDiffusionDither {

  /**
   * Constructs a new Stevenson-Arce algorithm for the specified palette.
   *
   * @param palette the palette to reduce images to
   */
  public StevensonArceDither(final DitherPalette palette) {
    super(palette, DiffusionKernel.STEVENSON_ARCE);
  }

  /**
   * Constructs a new Stevenson-Arce algorithm for the Minecraft map palette.
   */
  public StevensonArceDither() {
    this(DitherPalette.DEFAULT_MAP_PALETTE);
  }
}
