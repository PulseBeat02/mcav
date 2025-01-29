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
package me.brandonli.mcav.sandbox.utils;

import me.brandonli.mcav.utils.immutable.Pair;

public final class ArgumentUtils {

  private ArgumentUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static Pair<Integer, Integer> parseDimensions(final String argument) {
    final String[] split = argument.split("x", 2);
    if (split.length != 2) {
      throw new IllegalArgumentException("Invalid dimensions format: " + argument);
    }
    final int x = Integer.parseInt(split[0]);
    final int y = Integer.parseInt(split[1]);
    if (x <= 0 || y <= 0) {
      throw new IllegalArgumentException("Dimensions must be positive integers: " + argument);
    }
    return Pair.pair(x, y);
  }
}
