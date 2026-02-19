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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.*;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Implementation of the {@link ErrorDiffusionDitherBuilder} interface.
 */
public class ErrorDiffusionDitherBuilderImpl implements ErrorDiffusionDitherBuilder<ErrorDiffusionDither, ErrorDiffusionDitherBuilderImpl> {

  private DitherPalette palette = DitherPalette.DEFAULT_MAP_PALETTE;
  private Algorithm algorithm = Algorithm.FILTER_LITE;

  private int temporalThreshold = TemporalDitherAlgorithm.DEFAULT_TEMPORAL_THRESHOLD;
  private int errorThreshold = TemporalDitherAlgorithm.DEFAULT_ERROR_THRESHOLD;
  private float errorStrength = TemporalDitherAlgorithm.DEFAULT_ERROR_STRENGTH;

  /** Constructs a new {@link ErrorDiffusionDitherBuilderImpl} with default settings. */
  public ErrorDiffusionDitherBuilderImpl() {
    // no-op
  }

  /** {@inheritDoc} */
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

  /** {@inheritDoc} */
  @Override
  public void setPalette(final DitherPalette palette) {
    this.palette = palette;
  }

  /** {@inheritDoc} */
  @Override
  public void setAlgorithm(final Algorithm algorithm) {
    this.algorithm = algorithm;
  }

  /** {@inheritDoc} */
  @Override
  public void setTemporalThreshold(final int threshold) {
    this.temporalThreshold = threshold;
  }

  /** {@inheritDoc} */
  @Override
  public void setErrorThreshold(final int threshold) {
    this.errorThreshold = threshold;
  }

  /** {@inheritDoc} */
  @Override
  public void setErrorStrength(final float strength) {
    this.errorStrength = strength;
  }
}
