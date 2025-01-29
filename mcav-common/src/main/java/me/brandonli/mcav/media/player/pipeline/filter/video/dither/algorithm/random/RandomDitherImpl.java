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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random;

import java.nio.ByteBuffer;
import java.util.Random;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.AbstractDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Implementation of the {@code RandomDither} algorithm.
 */
public final class RandomDitherImpl extends AbstractDitherAlgorithm implements RandomDither {

  private static final Random RANDOM = new Xoroshiro128PlusRandom();

  private final int min;
  private final int max;

  /**
   * Constructs an instance of the {@code RandomDither} class with the specified palette
   * and weight.
   *
   * @param palette the {@code Palette} object representing the color palette to be used
   *                during the dithering process. It provides the set of colors against
   *                which pixel values will be mapped.
   * @param weight  the integer value specifying the randomness range for dithering.
   *                The weight influences the distribution of random noise applied to the pixels.
   */
  public RandomDitherImpl(final DitherPalette palette, final int weight) {
    super(palette);
    this.min = -weight;
    this.max = weight + 1;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image) {
    final DitherPalette palette = this.getPalette();
    final int width = image.getWidth();
    final int[] buffer = image.getPixels();
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
   * {@inheritDoc}
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    final DitherPalette palette = this.getPalette();
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
