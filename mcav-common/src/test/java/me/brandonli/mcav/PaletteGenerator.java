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
package me.brandonli.mcav;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.MapPaletteLoader;

/**
 * Renders the Minecraft map palette as a 16 by 16 image, one pixel per color, for documentation.
 */
public final class PaletteGenerator {

  private static final String OUTPUT = "palette.png";

  static void main() throws IOException {
    final int[] colors = MapPaletteLoader.getColors();
    final int count = Math.min(colors.length, 256);
    final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    for (int index = 0; index < count; index++) {
      final int x = index & 15;
      final int y = index >> 4;
      final int color = colors[index];
      image.setRGB(x, y, color);
    }
    final File file = new File(OUTPUT);
    ImageIO.write(image, "png", file);
  }
}
