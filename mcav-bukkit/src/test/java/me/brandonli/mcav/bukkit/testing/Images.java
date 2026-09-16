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
package me.brandonli.mcav.bukkit.testing;

import java.util.Arrays;
import me.brandonli.mcav.media.image.ImageBuffer;

/**
 * Creates small images for tests.
 */
public final class Images {

  private Images() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Creates an image filled with one color.
   *
   * @param width  the width
   * @param height the height
   * @param argb   the color as packed ARGB
   * @return the image
   */
  public static ImageBuffer solid(final int width, final int height, final int argb) {
    final int[] pixels = new int[width * height];
    Arrays.fill(pixels, argb);
    return ImageBuffer.buffer(pixels, width, height);
  }
}
