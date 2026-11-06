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
 *
 * <p>This class is not thread-safe, because it keeps the previous frame; use one instance per stream.
 *
 * @since 1.0.0
 */
public final class TemporalFloydSteinbergDither extends TemporalDitherAlgorithm {

  /**
   * Creates a new algorithm for the Minecraft map palette with default settings.
   *
   * @since 1.0.0
   */
  public TemporalFloydSteinbergDither() {
    this(DitherPalette.DEFAULT_MAP_PALETTE);
  }

  /**
   * Creates a new algorithm with default settings.
   *
   * @param palette the palette to reduce images to
   * @throws NullPointerException if {@code palette} is null
   * @since 1.0.0
   */
  public TemporalFloydSteinbergDither(final DitherPalette palette) {
    super(palette, DiffusionKernel.FLOYD_STEINBERG);
  }

  /**
   * Creates a new algorithm.
   *
   * @param palette           the palette to reduce images to
   * @param temporalThreshold how far a pixel may drift per channel, from 0 to 255, before its color is
   *                          recomputed
   * @param errorThreshold    the total error at or below which no error is diffused
   * @param errorStrength     the fraction of the error that is diffused, from 0 to 1
   * @throws NullPointerException     if {@code palette} is null
   * @throws IllegalArgumentException if the temporal threshold is outside 0 through 255, the error
   *                                  threshold is negative, or the strength is outside 0 through 1 or NaN
   * @since 1.0.0
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
