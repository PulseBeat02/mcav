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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random;

import java.nio.ByteBuffer;
import java.util.Random;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.AbstractDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.Palette;

/**
 * The {@code RandomDither} class is an implementation of a dithering algorithm
 * that applies randomized color modifications to pixel data. This class extends
 * {@code AbstractDitherAlgorithm} and utilizes a stochastic approach to alter
 * pixel colors before mapping them to the nearest available color in the palette.
 * <p>
 * The algorithm aims to introduce randomness in color adjustments to create a
 * dithering effect that reduces color banding in an image. This is achieved
 * by adjusting the RGB values of each pixel by a random value within a defined weight range.
 * <p>
 * Instances of this class can be configured with three predefined weight levels:
 * {@link #LIGHT_WEIGHT}, {@link #NORMAL_WEIGHT}, and {@link #HEAVY_WEIGHT},
 * determining the intensity of random adjustments.
 * <p>
 * Thread-safety is not guaranteed for this class. For multi-threaded access,
 * external synchronization may be required.
 */
public final class RandomDitherImpl extends AbstractDitherAlgorithm implements RandomDither {

  private static final Random RANDOM = new Xoroshiro128PlusRandom();

  private final int min;
  private final int max;

  /**
   * Constructs an instance of the {@code RandomDither} class with the specified palette
   * and weight. The weight determines the range of randomness introduced during the
   * dithering process.
   *
   * @param palette the {@code Palette} object representing the color palette to be used
   *                during the dithering process. It provides the set of colors against
   *                which pixel values will be mapped.
   * @param weight  the integer value specifying the randomness range for dithering.
   *                The weight influences the distribution of random noise applied to the pixels.
   */
  public RandomDitherImpl(final Palette palette, final int weight) {
    super(palette);
    this.min = -weight;
    this.max = weight + 1;
  }

  /**
   * Applies a dithering algorithm to the given {@link StaticImage} and converts it to an array of bytes.
   *
   * @param image the input static image containing pixel data
   * @return a byte array representing the dithered image data
   */
  @Override
  public byte[] ditherIntoBytes(final StaticImage image) {
    final Palette palette = this.getPalette();
    final int width = image.getWidth();
    final int[] buffer = image.getAllPixels();
    final int length = buffer.length;
    final int height = length / width;
    final ByteBuffer data = ByteBuffer.allocate(length);
    for (int y = 0; y < height; y++) {
      final int yIndex = y * width;
      for (int x = 0; x < width; x++) {
        final int index = yIndex + x;
        final int color = buffer[index];
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = (color) & 0xFF;
        r = (r += this.random()) > 255 ? 255 : Math.max(r, 0);
        g = (g += this.random()) > 255 ? 255 : Math.max(g, 0);
        b = (b += this.random()) > 255 ? 255 : Math.max(b, 0);
        data.put(index, DitherUtils.getBestColor(palette, r, g, b));
      }
    }
    return data.array();
  }

  /**
   * Applies dithering to the provided buffer using a pseudo-random noise algorithm
   * and a predefined color palette. The method iterates over each pixel in the buffer,
   * modifies its RGB components with random noise, and replaces it with the best matching
   * color from the palette.
   *
   * @param buffer an array of integers representing the pixel data of an image, where
   *               each integer encodes an RGB color.
   * @param width  the width of the image represented by the buffer, used to calculate
   *               the height and properly index the pixel data during processing.
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    final Palette palette = this.getPalette();
    final int height = buffer.length / width;
    for (int y = 0; y < height; y++) {
      final int yIndex = y * width;
      for (int x = 0; x < width; x++) {
        final int index = yIndex + x;
        final int color = buffer[index];
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = (color) & 0xFF;
        r = (r += this.random()) > 255 ? 255 : Math.max(r, 0);
        g = (g += this.random()) > 255 ? 255 : Math.max(g, 0);
        b = (b += this.random()) > 255 ? 255 : Math.max(b, 0);
        buffer[index] = DitherUtils.getBestColorNormal(palette, r, g, b);
      }
    }
  }

  private int random() {
    return this.min + RANDOM.nextInt(this.max - this.min);
  }
}
