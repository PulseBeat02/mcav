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
 * Stucki error diffusion, which spreads the error over twelve neighbors in three rows. It is a sharper variant of
 * Jarvis, Judice, and Ninke.
 */
public final class StuckiDither extends ErrorDiffusionDither {

  /**
   * Constructs a new Stucki algorithm for the specified palette.
   *
   * @param palette the palette to reduce images to
   */
  public StuckiDither(final DitherPalette palette) {
    super(palette, DiffusionKernel.STUCKI);
  }

  /**
   * Constructs a new Stucki algorithm for the Minecraft map palette.
   */
  public StuckiDither() {
    this(DitherPalette.DEFAULT_MAP_PALETTE);
  }
}
