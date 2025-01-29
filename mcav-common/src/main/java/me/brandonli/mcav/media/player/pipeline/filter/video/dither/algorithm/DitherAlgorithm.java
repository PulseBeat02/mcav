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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm;

import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.*;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.ErrorDiffusionDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.NearestDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDitherImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.Palette;

/**
 * Interface representing a general dither algorithm. Implementations
 * of this interface provide various dithering techniques for images.
 * Dithering is a method used to approximate a color from a color palette
 * by merging pixels of available colors.
 */
public interface DitherAlgorithm {
  /**
   * Converts the given static image buffer into a byte array by applying a dithering algorithm.
   * Each byte in the resulting array represents a palette index corresponding to
   * the colors mapped during the dithering process.
   *
   * @param buffer the static image to be dithered, represented as a {@code StaticImage} object.
   *               This is the source data upon which the dithering operation is performed.
   * @param width  the width of the {@code buffer} in pixels. It is assumed that the width
   *               aligns with the actual structure of the image buffer.
   * @return a byte array containing the palette index for each pixel in the dithered image.
   * The size of this array corresponds to the total number of pixels in the image.
   */
  byte[] ditherIntoBytes(final StaticImage buffer, final int width);

  /**
   * Applies a dithering algorithm to the given pixel buffer. The dithering
   * process adjusts the pixel colors in the buffer to approximate colors
   * from a predefined palette, creating the illusion of intermediate shades.
   *
   * @param buffer an array of pixel data represented as integers. Each element
   *               corresponds to the color value of a pixel in the image.
   * @param width  the width of the image represented by the buffer. It is used
   *               for understanding the pixel layout in row-major order.
   */
  void dither(final int[] buffer, final int width);

  /**
   * Retrieves the palette associated with the dither algorithm. The palette
   * represents the set of colors used during the dithering process to approximate
   * the colors of an image.
   *
   * @return the {@code Palette} object used by the dither algorithm for color mapping
   * and processing.
   */
  Palette getPalette();

  /**
   * Creates and returns a builder for constructing an instance of
   * {@link ErrorDiffusionDither}, using the specified algorithm and palette.
   *
   * @return an {@link ErrorDiffusionDitherBuilder} to configure and build
   * an instance of {@link ErrorDiffusionDither}.
   */
  static ErrorDiffusionDitherBuilder<ErrorDiffusionDither, ErrorDiffusionDitherBuilderImpl> errorDiffusion() {
    return new ErrorDiffusionDitherBuilderImpl();
  }

  /**
   * Creates a builder for constructing instances of {@link RandomDitherImpl}, which
   * applies random noise to image data for dithering purposes.
   *
   * @return a builder for {@link RandomDitherImpl} instances, allowing customization
   * of the dithering algorithm's parameters such as the weight and palette.
   */
  static RandomDitherBuilder<RandomDither, RandomDitherBuilderImpl> random() {
    return new RandomDitherBuilderImpl();
  }

  /**
   * Creates and returns an instance of {@code OrderedDitherBuilderImpl}, which is used to build
   * a configured {@code OrderedDither} instance. The builder allows setting parameters like
   * the palette and dithering matrix before creating the final dithering algorithm.
   *
   * @return an instance of {@code OrderedDitherBuilderImpl} for constructing an {@code OrderedDither}.
   */
  static OrderedDitherBuilder<BayerDither, OrderedDitherBuilderImpl> ordered() {
    return new OrderedDitherBuilderImpl();
  }

  /**
   * Creates a builder for constructing instances of {@code NearestDither}.
   * This builder allows customization of parameters such as the palette,
   * used to configure and build the dithering algorithm.
   *
   * @return an instance of {@code NearestDitherBuilderImpl} for configuring and
   * building a {@code NearestDither} instance.
   */
  static NearestDitherBuilder<NearestDither, NearestDitherBuilderImpl> nearest() {
    return new NearestDitherBuilderImpl();
  }
}
