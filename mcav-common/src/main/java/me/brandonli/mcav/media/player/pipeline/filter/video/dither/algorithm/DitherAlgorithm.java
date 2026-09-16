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

import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.ErrorDiffusionDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.ErrorDiffusionDitherBuilderImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.NearestDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.NearestDitherBuilderImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.OrderedDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.OrderedDitherBuilderImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.RandomDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.RandomDitherBuilderImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.ErrorDiffusionDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FilterLiteDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FloydDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalFloydSteinbergDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.NearestDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.NearestDitherImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomNumberProvider;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Reduces images to the colors of a {@link DitherPalette} while preserving the impression of the original colors.
 *
 * <p>Dithering algorithms are the last step before an image is shown on a Minecraft map, whose 248 colors could
 * not represent a photo otherwise. Two outputs are available: {@link #ditherIntoBytes(ImageBuffer)} produces the
 * palette indices maps expect, and {@link #dither(int[], int)} replaces the pixels of an image with the palette
 * colors, for previews and for palettes that are not map palettes.
 *
 * <p>Use the static factory methods to pick an algorithm:
 *
 * <ul>
 *   <li>{@link #filterLite()} is the fastest error diffusion and a good default for video.</li>
 *   <li>{@link #floydSteinberg()} gives the classic, slightly finer error diffusion.</li>
 *   <li>{@link #temporalFloydSteinberg()} keeps unchanged areas stable between frames, which cuts bandwidth
 *       dramatically with {@code CompressedMapResult}.</li>
 *   <li>{@link #ordered()} and {@link #random()} trade quality for speed with patterns and noise.</li>
 *   <li>{@link #nearest()} picks the closest color without dithering, which is the fastest option.</li>
 * </ul>
 *
 * <p>Most algorithms are stateless and can be shared by any number of players and threads. The exceptions are:
 *
 * <ul>
 *   <li>{@link TemporalDitherAlgorithm Temporal algorithms}, created by {@link #temporalFloydSteinberg()} or with
 *       {@link ErrorDiffusionDitherBuilder.Algorithm#TEMPORAL_FLOYD_STEINBERG}, remember the previous frame of the
 *       video they dither. They must not be shared between players: create one for every player.</li>
 *   <li>Random dithering created with an explicit {@link RandomNumberProvider} hands that one provider to every
 *       thread, so it is only thread-safe if the provider is. Random dithering built without a provider uses a
 *       generator per thread and can be shared.</li>
 * </ul>
 */
public interface DitherAlgorithm {
  /**
   * Dithers an image into palette indices, one byte per pixel laid out row by row. The image is not modified.
   *
   * @param buffer the image to dither
   * @return the palette index of every pixel
   */
  byte[] ditherIntoBytes(final ImageBuffer buffer);

  /**
   * Dithers pixels in place, replacing every pixel with the ARGB value of a palette color.
   *
   * @param buffer the ARGB pixels laid out row by row, which are replaced with palette colors
   * @param width  the width of the image, which must divide the length of the buffer
   */
  void dither(final int[] buffer, final int width);

  /**
   * Gets the palette the algorithm reduces images to.
   *
   * @return the palette
   */
  DitherPalette getPalette();

  /**
   * Creates a builder for error diffusion algorithms, which spread the rounding error of every pixel to its
   * neighbors and give the highest quality.
   *
   * @return the builder, which defaults to Filter Lite on the map palette
   */
  static ErrorDiffusionDitherBuilder<ErrorDiffusionDither, ErrorDiffusionDitherBuilderImpl> errorDiffusion() {
    return new ErrorDiffusionDitherBuilderImpl();
  }

  /**
   * Creates a builder for random dithering, which adds noise before picking the closest color.
   *
   * @return the builder, which defaults to a normal weight on the map palette
   */
  static RandomDitherBuilder<RandomDither, RandomDitherBuilderImpl> random() {
    return new RandomDitherBuilderImpl();
  }

  /**
   * Creates a builder for ordered dithering, which adds a repeating threshold pattern before picking the closest
   * color.
   *
   * @return the builder, which defaults to a 2x2 Bayer matrix on the map palette
   */
  static OrderedDitherBuilder<BayerDither, OrderedDitherBuilderImpl> ordered() {
    return new OrderedDitherBuilderImpl();
  }

  /**
   * Creates a builder for nearest color mapping, which does not dither at all.
   *
   * @return the builder, which defaults to the map palette
   */
  static NearestDitherBuilder<NearestDither, NearestDitherBuilderImpl> nearest() {
    return new NearestDitherBuilderImpl();
  }

  /**
   * Creates a Filter Lite error diffusion algorithm on the map palette. Filter Lite is the fastest error
   * diffusion and looks almost identical to Floyd-Steinberg.
   *
   * @return the algorithm
   */
  static ErrorDiffusionDither filterLite() {
    return new FilterLiteDither(DitherPalette.DEFAULT_MAP_PALETTE);
  }

  /**
   * Creates a Floyd-Steinberg error diffusion algorithm on the map palette.
   *
   * @return the algorithm
   */
  static ErrorDiffusionDither floydSteinberg() {
    return new FloydDither(DitherPalette.DEFAULT_MAP_PALETTE);
  }

  /**
   * Creates a nearest color algorithm on the map palette, which is the fastest option.
   *
   * @return the algorithm
   */
  static NearestDither nearestColor() {
    return new NearestDitherImpl(DitherPalette.DEFAULT_MAP_PALETTE);
  }

  /**
   * Creates a temporally stable Floyd-Steinberg algorithm on the map palette with default settings. Pixels that
   * barely changed since the previous frame keep their previous color, which stops dithering noise from
   * flickering and lets {@code CompressedMapResult} skip unchanged parts of the picture.
   *
   * @return a new algorithm, which holds the state of one video and must not be shared between players
   */
  static TemporalDitherAlgorithm temporalFloydSteinberg() {
    return new TemporalFloydSteinbergDither();
  }

  /**
   * Creates a temporally stable Floyd-Steinberg algorithm.
   *
   * @param palette           the palette to reduce images to
   * @param temporalThreshold how far a pixel may drift per channel, from 0 to 255, before its color is
   *                          recomputed; larger values are more stable but react slower to changes
   * @param errorThreshold    the total error below which no error is diffused, which suppresses noise
   * @param errorStrength     the fraction of the error that is diffused, from 0 to 1
   * @return a new algorithm, which holds the state of one video and must not be shared between players
   */
  static TemporalDitherAlgorithm temporalFloydSteinberg(
    final DitherPalette palette,
    final int temporalThreshold,
    final int errorThreshold,
    final float errorStrength
  ) {
    return new TemporalFloydSteinbergDither(palette, temporalThreshold, errorThreshold, errorStrength);
  }
}
