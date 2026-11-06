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
 * Jarvis, Judice, and Ninke error diffusion, which spreads the error over twelve neighbors in three rows. It
 * produces very smooth results at roughly three times the cost of Floyd-Steinberg.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class JarvisJudiceNinkeDither extends ErrorDiffusionDither {

  /**
   * Creates a new Jarvis-Judice-Ninke algorithm for the specified palette.
   *
   * @param palette the palette to reduce images to
   * @throws NullPointerException if {@code palette} is null
   * @since 1.0.0
   */
  public JarvisJudiceNinkeDither(final DitherPalette palette) {
    super(palette, DiffusionKernel.JARVIS_JUDICE_NINKE);
  }

  /**
   * Creates a new Jarvis-Judice-Ninke algorithm for the Minecraft map palette.
   *
   * @since 1.0.0
   */
  public JarvisJudiceNinkeDither() {
    this(DitherPalette.DEFAULT_MAP_PALETTE);
  }
}
