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
 * Floyd-Steinberg error diffusion that stays stable between video frames, see {@link TemporalDitherAlgorithm}.
 * This is the recommended algorithm for video on maps, especially together with delta encoding.
 */
public final class TemporalFloydSteinbergDither extends TemporalDitherAlgorithm {

  /**
   * Constructs a new algorithm for the Minecraft map palette with default settings.
   */
  public TemporalFloydSteinbergDither() {
    this(DitherPalette.DEFAULT_MAP_PALETTE);
  }

  /**
   * Constructs a new algorithm with default settings.
   *
   * @param palette the palette to reduce images to
   */
  public TemporalFloydSteinbergDither(final DitherPalette palette) {
    super(palette, DiffusionKernel.FLOYD_STEINBERG);
  }

  /**
   * Constructs a new algorithm.
   *
   * @param palette           the palette to reduce images to
   * @param temporalThreshold how far a pixel may drift per channel, from 0 to 255, before its color is
   *                          recomputed
   * @param errorThreshold    the total error below which no error is diffused
   * @param errorStrength     the fraction of the error that is diffused, from 0 to 1
   */
  public TemporalFloydSteinbergDither(
    final DitherPalette palette,
    final int temporalThreshold,
    final int errorThreshold,
    final float errorStrength
  ) {
    super(palette, DiffusionKernel.FLOYD_STEINBERG, temporalThreshold, errorThreshold, errorStrength);
  }
}
