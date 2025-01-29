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

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.AbstractDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * ErrorDiffusionDither is an abstract class for implementing error diffusion dithering algorithms.
 */
public abstract class ErrorDiffusionDither extends AbstractDitherAlgorithm {

  /**
   * Constructs an instance of the {@code ErrorDiffusionDither} class with the specified color
   * palette.
   *
   * @param palette the {@code Palette} object representing the set of colors to be used during
   *                the error diffusion dithering process. This palette defines the restricted
   *                color space to which image pixels will be quantized.
   */
  public ErrorDiffusionDither(final DitherPalette palette) {
    super(palette);
  }
}
