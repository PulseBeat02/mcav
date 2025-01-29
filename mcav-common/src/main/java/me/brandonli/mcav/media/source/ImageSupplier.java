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
package me.brandonli.mcav.media.source;

import java.awt.image.BufferedImage;

/**
 *
 */
@FunctionalInterface
public interface ImageSupplier {
  /**
   * Supplies a {@link java.awt.image.BufferedImage} representing the image data.
   *
   * @return a {@link java.awt.image.BufferedImage} representing the image data
   */
  BufferedImage getImage();

  default SampleSupplier toSampleSupplier() {
    return () -> {
      final BufferedImage image = this.getImage();
      final int width = image.getWidth();
      final int height = image.getHeight();
      final int[] pixels = new int[width * height];
      image.getRGB(0, 0, width, height, pixels, 0, width);
      return pixels;
    };
  }
}
