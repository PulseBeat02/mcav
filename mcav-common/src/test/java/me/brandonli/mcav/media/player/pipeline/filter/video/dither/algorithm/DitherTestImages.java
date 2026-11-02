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

public final class DitherTestImages {

  public static final DitherPalette BLACK_WHITE = DitherPalette.colors(0x000000, 0xFFFFFF);

  private static final int CHANNEL_MAXIMUM = 255;

  private static final int OPAQUE_ALPHA = 0xFF000000;
  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final int WHITE_INDEX = 1;

  private DitherTestImages() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static int[] gradient(final int width, final int height) {
    final int[] pixels = new int[width * height];
    final int steps = Math.max(1, width - 1);
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        final int value = (column * CHANNEL_MAXIMUM) / steps;
        pixels[row * width + column] = OPAQUE_ALPHA | (value << RED_SHIFT) | (value << GREEN_SHIFT) | value;
      }
    }

    return pixels;
  }

  public static double whiteRatio(final byte[] indices) {
    long white = 0;
    for (final byte index : indices) {
      if (index == WHITE_INDEX) {
        white++;
      }
    }

    return white / (double) indices.length;
  }
}
