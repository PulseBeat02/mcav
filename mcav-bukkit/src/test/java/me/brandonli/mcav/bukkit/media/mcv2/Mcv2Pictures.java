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

/**
 * The synthetic scene the encoder tests encode: a flat band, a checkerboard, a ramp and noisy waves, panned sideways a
 * given number of pixels per frame.
 */
final class Mcv2Pictures {

  private Mcv2Pictures() {}

  static byte[] scene(final int width, final int height, final int frame, final int panPerFrame) {
    final Random random = new Random(frame * 7919L);
    final byte[] rgb = new byte[width * height * 3];
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        final int sourceColumn = column + frame * panPerFrame;
        final int at = (row * width + column) * 3;
        final int value;
        if (row < height / 4) {
          value = 90;
        } else if (row < height / 2) {
          value = ((sourceColumn / 4 + row / 4) & 1) == 0 ? 40 : 200;
        } else if (column < width / 3) {
          value = 30 + ((sourceColumn * 3 + row) % 180);
        } else {
          value = (int) (120 + 60 * Math.sin(sourceColumn / 7.0) * Math.cos(row / 5.0)) + random.nextInt(8);
        }
        rgb[at] = (byte) value;
        rgb[at + 1] = (byte) Math.min(255, value + 20);
        rgb[at + 2] = (byte) Math.max(0, value - 30);
      }
    }
    return rgb;
  }
}
