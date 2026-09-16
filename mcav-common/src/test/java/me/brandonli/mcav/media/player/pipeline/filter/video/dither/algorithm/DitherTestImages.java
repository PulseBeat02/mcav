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

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Shared inputs of the dithering tests.
 */
public final class DitherTestImages {

  /**
   * A palette with only black and white, which makes the brightness of a dithered image easy to measure.
   */
  public static final DitherPalette BLACK_WHITE = DitherPalette.colors(0x000000, 0xFFFFFF);

  private DitherTestImages() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Creates a horizontal gray gradient from black on the left to white on the right.
   *
   * @param width  the width
   * @param height the height
   * @return the pixels as packed ARGB
   */
  public static int[] gradient(final int width, final int height) {
    final int[] pixels = new int[width * height];
    final int steps = Math.max(1, width - 1);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int value = (x * 255) / steps;
        pixels[y * width + x] = 0xFF000000 | (value << 16) | (value << 8) | value;
      }
    }
    return pixels;
  }

  /**
   * Counts the indices that point at white in {@link #BLACK_WHITE}.
   *
   * @param indices the palette indices
   * @return the share of white pixels, from 0 to 1
   */
  public static double whiteRatio(final byte[] indices) {
    long white = 0;
    for (final byte index : indices) {
      if (index == 1) {
        white++;
      }
    }
    return white / (double) indices.length;
  }
}
