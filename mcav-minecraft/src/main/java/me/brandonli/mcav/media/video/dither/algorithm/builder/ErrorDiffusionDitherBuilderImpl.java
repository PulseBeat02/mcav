/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.video.dither.algorithm.builder;

import me.brandonli.mcav.media.video.dither.algorithm.error.*;
import me.brandonli.mcav.media.video.dither.palette.Palette;

/**
 * The ErrorDiffusionDitherBuilderImpl class is an implementation of the
 * {@link ErrorDiffusionDitherBuilder} interface. It serves as a builder for creating
 * specific implementations of the {@link ErrorDiffusionDither} class, which applies
 * error diffusion dithering algorithms to distribute quantization errors across
 * neighboring pixels in an image.
 * <p>
 * This implementation allows the configuration of a color palette, defined by
 * {@link Palette}, and a specific error diffusion algorithm through the
 * {@link Algorithm} enum.
 * <p>
 * By default, this builder uses the {@link Algorithm#FILTER_LITE} algorithm
 * unless another algorithm is explicitly set using the {@link #setAlgorithm} method.
 * When the {@link #build()} method is called, an appropriate instance of
 * {@link ErrorDiffusionDither} is created based on the selected algorithm.
 */
public class ErrorDiffusionDitherBuilderImpl implements ErrorDiffusionDitherBuilder<ErrorDiffusionDither, ErrorDiffusionDitherBuilderImpl> {

  private Palette palette = Palette.DEFAULT_MAP_PALETTE;
  private Algorithm algorithm = Algorithm.FILTER_LITE;

  /**
   * {@inheritDoc}
   */
  @Override
  public ErrorDiffusionDither build() {
    switch (this.algorithm) {
      case ATKINSON:
        return new AtkinsonDither(this.palette);
      case BURKES:
        return new BurkesDither(this.palette);
      case FILTER_LITE:
        return new FilterLiteDither(this.palette);
      case FLOYD_STEINBERG:
        return new FloydDither(this.palette);
      case JARVIS_JUDICE_NINKE:
        return new JarvisJudiceNinkeDither(this.palette);
      case STEVENSON_ARCE:
        return new StevensonArceDither(this.palette);
      case STUCKI:
        return new StuckiDither(this.palette);
      default:
        throw new InvalidErrorDiffusionAlgorithmException("Unknown algorithm!");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setPalette(final Palette palette) {
    this.palette = palette;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setAlgorithm(final Algorithm algorithm) {
    this.algorithm = algorithm;
  }
}
