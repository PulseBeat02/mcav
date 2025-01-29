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
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.Palette;

/**
 * ErrorDiffusionDither is an abstract class that serves as a base for
 * implementing dithering algorithms based on the method of error diffusion.
 * Error diffusion is a technique used to reduce the color depth of an image
 * by distributing the quantization error of each pixel to its neighboring
 * pixels. This approach maintains visual quality while mapping image colors
 * to a restricted set of values defined by a palette.
 * <p>
 * Concrete subclasses should implement specific error diffusion algorithms
 * by defining how the error is calculated and propagated to neighboring
 * pixels. Implementations may vary based on the kernel and distribution weights
 * used during the error diffusion process.
 * <p>
 * Each instance of ErrorDiffusionDither operates on a specific color palette,
 * provided during construction, to map pixel colors within an image to the
 * nearest available color in the palette.
 * <p>
 * This class extends AbstractDitherAlgorithm, inheriting its foundational
 * functionality, such as palette management, while introducing essential
 * methods required for implementing error diffusion.
 */
public abstract class ErrorDiffusionDither extends AbstractDitherAlgorithm {

  /**
   * Constructs an instance of the {@code ErrorDiffusionDither} class with the specified color
   * palette. This constructor initializes the base functionality, setting up the palette to
   * be used by the error diffusion dithering algorithm for mapping pixel colors to the
   * nearest available color in the palette.
   *
   * @param palette the {@code Palette} object representing the set of colors to be used during
   *                the error diffusion dithering process. This palette defines the restricted
   *                color space to which image pixels will be quantized.
   */
  public ErrorDiffusionDither(final Palette palette) {
    super(palette);
  }
}
