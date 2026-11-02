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
package me.brandonli.mcav.bukkit.media.mcv2;

import java.util.Random;

final class Mcv2Pictures {

  private static final long SEED_MULTIPLIER = 7919L;

  private static final int MAXIMUM_CHANNEL = 255;

  private Mcv2Pictures() {}

  static byte[] scene(final int width, final int height, final int frame, final int panPerFrame) {
    final Random random = new Random(frame * SEED_MULTIPLIER);
    final byte[] rgb = new byte[width * height * Mcv2Decoder.CHANNELS];
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        final int sourceColumn = column + frame * panPerFrame;
        final int offset = (row * width + column) * Mcv2Decoder.CHANNELS;
        final int value = sceneValue(width, height, row, column, sourceColumn, random);
        rgb[offset] = (byte) value;
        rgb[offset + 1] = (byte) Math.min(MAXIMUM_CHANNEL, value + 20);
        rgb[offset + 2] = (byte) Math.max(0, value - 30);
      }
    }

    return rgb;
  }

  private static int sceneValue(
    final int width,
    final int height,
    final int row,
    final int column,
    final int sourceColumn,
    final Random random
  ) {
    if (row < height / 4) {
      return 90;
    }

    if (row < height / 2) {
      return ((sourceColumn / 4 + row / 4) & 1) == 0 ? 40 : 200;
    }

    if (column < width / 3) {
      return 30 + ((sourceColumn * 3 + row) % 180);
    }

    return (int) (120 + 60 * Math.sin(sourceColumn / 7.0) * Math.cos(row / 5.0)) + random.nextInt(8);
  }
}
