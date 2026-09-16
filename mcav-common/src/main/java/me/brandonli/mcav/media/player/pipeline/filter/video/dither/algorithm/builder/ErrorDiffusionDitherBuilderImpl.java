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

import com.google.common.base.Preconditions;
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

/**
 * The default {@link ErrorDiffusionDitherBuilder}.
 */
public class ErrorDiffusionDitherBuilderImpl implements ErrorDiffusionDitherBuilder<ErrorDiffusionDither, ErrorDiffusionDitherBuilderImpl> {

  private DitherPalette palette;
  private Algorithm algorithm;
  private int temporalThreshold;
  private int errorThreshold;
  private float errorStrength;

  /**
   * Constructs a builder that creates Filter Lite on the Minecraft map palette.
   */
  public ErrorDiffusionDitherBuilderImpl() {
    this.palette = DitherPalette.DEFAULT_MAP_PALETTE;
    this.algorithm = Algorithm.FILTER_LITE;
    this.temporalThreshold = TemporalDitherAlgorithm.DEFAULT_TEMPORAL_THRESHOLD;
    this.errorThreshold = TemporalDitherAlgorithm.DEFAULT_ERROR_THRESHOLD;
    this.errorStrength = TemporalDitherAlgorithm.DEFAULT_ERROR_STRENGTH;
  }

  /**
   * Creates the chosen error diffusion algorithm. Every call creates a new algorithm, so a temporal algorithm built
   * here can be given to exactly one player.
   *
   * @return the algorithm
   */
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

  /**
   * Sets the palette the algorithm reduces images to. Defaults to the Minecraft map palette.
   *
   * @param palette the palette
   * @return this builder
   */
  @Override
  public ErrorDiffusionDitherBuilderImpl withPalette(final DitherPalette palette) {
    Preconditions.checkNotNull(palette, "Palette must not be null");
    this.palette = palette;
    return this;
  }

  /**
   * Sets the error diffusion algorithm. Defaults to {@link Algorithm#FILTER_LITE}.
   *
   * @param algorithm the algorithm
   * @return this builder
   */
  @Override
  public ErrorDiffusionDitherBuilderImpl withAlgorithm(final Algorithm algorithm) {
    Preconditions.checkNotNull(algorithm, "Algorithm must not be null");
    this.algorithm = algorithm;
    return this;
  }

  /**
   * Sets how far a pixel may drift per channel before its color is recomputed. Only used by
   * {@link Algorithm#TEMPORAL_FLOYD_STEINBERG}. Defaults to {@link TemporalDitherAlgorithm#DEFAULT_TEMPORAL_THRESHOLD}.
   *
   * @param threshold the threshold from 0 to 255
   * @return this builder
   * @throws IllegalArgumentException if the threshold is outside the range from 0 to 255
   */
  @Override
  public ErrorDiffusionDitherBuilderImpl withTemporalThreshold(final int threshold) {
    Preconditions.checkArgument(threshold >= 0 && threshold <= 255, "Temporal threshold must be between 0 and 255");
    this.temporalThreshold = threshold;
    return this;
  }

  /**
   * Sets the total error below which nothing is diffused. Only used by {@link Algorithm#TEMPORAL_FLOYD_STEINBERG}.
   * Defaults to {@link TemporalDitherAlgorithm#DEFAULT_ERROR_THRESHOLD}.
   *
   * @param threshold the threshold, which must not be negative
   * @return this builder
   * @throws IllegalArgumentException if the threshold is negative
   */
  @Override
  public ErrorDiffusionDitherBuilderImpl withErrorThreshold(final int threshold) {
    Preconditions.checkArgument(threshold >= 0, "Error threshold must not be negative");
    this.errorThreshold = threshold;
    return this;
  }

  /**
   * Sets the fraction of the error that is diffused. Only used by {@link Algorithm#TEMPORAL_FLOYD_STEINBERG}.
   * Defaults to {@link TemporalDitherAlgorithm#DEFAULT_ERROR_STRENGTH}.
   *
   * @param strength the strength from 0 to 1
   * @return this builder
   * @throws IllegalArgumentException if the strength is outside the range from 0 to 1
   */
  @Override
  public ErrorDiffusionDitherBuilderImpl withErrorStrength(final float strength) {
    Preconditions.checkArgument(strength >= 0 && strength <= 1, "Error strength must be between 0 and 1");
    this.errorStrength = strength;
    return this;
  }
}
