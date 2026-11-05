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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.ErrorDiffusionDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.AtkinsonDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.BurkesDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.ErrorDiffusionDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FilterLiteDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FloydDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.JarvisJudiceNinkeDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.StevensonArceDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.StuckiDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalFloydSteinbergDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

final class DefaultErrorDiffusionDitherBuilder implements ErrorDiffusionDitherBuilder {

  private static final int MAXIMUM_TEMPORAL_THRESHOLD = 255;

  private DitherPalette palette;
  private Algorithm algorithm;
  private int temporalThreshold;
  private int errorThreshold;
  private float errorStrength;

  DefaultErrorDiffusionDitherBuilder() {
    this.palette = DitherPalette.DEFAULT_MAP_PALETTE;
    this.algorithm = Algorithm.FILTER_LITE;
    this.temporalThreshold = TemporalDitherAlgorithm.DEFAULT_TEMPORAL_THRESHOLD;
    this.errorThreshold = TemporalDitherAlgorithm.DEFAULT_ERROR_THRESHOLD;
    this.errorStrength = TemporalDitherAlgorithm.DEFAULT_ERROR_STRENGTH;
  }

  @Override
  public ErrorDiffusionDither build() {
    return switch (this.algorithm) {
      case ATKINSON -> new AtkinsonDither(this.palette);
      case BURKES -> new BurkesDither(this.palette);
      case FILTER_LITE -> new FilterLiteDither(this.palette);
      case FLOYD_STEINBERG -> new FloydDither(this.palette);
      case TEMPORAL_FLOYD_STEINBERG -> new TemporalFloydSteinbergDither(
        this.palette,
        this.temporalThreshold,
        this.errorThreshold,
        this.errorStrength
      );
      case JARVIS_JUDICE_NINKE -> new JarvisJudiceNinkeDither(this.palette);
      case STEVENSON_ARCE -> new StevensonArceDither(this.palette);
      case STUCKI -> new StuckiDither(this.palette);
    };
  }

  @Override
  public ErrorDiffusionDitherBuilder palette(final DitherPalette palette) {
    Preconditions.checkNotNull(palette, "Palette must not be null!");

    this.palette = palette;
    return this;
  }

  @Override
  public ErrorDiffusionDitherBuilder algorithm(final Algorithm algorithm) {
    Preconditions.checkNotNull(algorithm, "Algorithm must not be null!");

    this.algorithm = algorithm;
    return this;
  }

  @Override
  public ErrorDiffusionDitherBuilder temporalThreshold(final int threshold) {
    Preconditions.checkArgument(
      threshold >= 0 && threshold <= MAXIMUM_TEMPORAL_THRESHOLD,
      "Temporal threshold must be between 0 and 255 inclusive!"
    );

    this.temporalThreshold = threshold;
    return this;
  }

  @Override
  public ErrorDiffusionDitherBuilder errorThreshold(final int threshold) {
    Preconditions.checkArgument(threshold >= 0, "Error threshold must not be negative!");

    this.errorThreshold = threshold;
    return this;
  }

  @Override
  public ErrorDiffusionDitherBuilder errorStrength(final float strength) {
    Preconditions.checkArgument(strength >= 0 && strength <= 1, "Error strength must be between 0 and 1 inclusive!");

    this.errorStrength = strength;
    return this;
  }
}
