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

public final class PaletteGenerator {

  private static final String OUTPUT_FILE_NAME = "palette.png";

  private static final String IMAGE_FORMAT = "png";

  private static final int GRID_SIDE = 16;
  private static final int COLOR_LIMIT = GRID_SIDE * GRID_SIDE;

  private PaletteGenerator() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static void main() throws IOException {
    final int[] colors = MapPaletteLoader.getColors();
    final int count = Math.min(colors.length, COLOR_LIMIT);
    final BufferedImage image = new BufferedImage(GRID_SIDE, GRID_SIDE, BufferedImage.TYPE_INT_ARGB);
    for (int index = 0; index < count; index++) {
      final int column = index % GRID_SIDE;
      final int row = index / GRID_SIDE;
      final int color = colors[index];
      image.setRGB(column, row, color);
    }

    final File file = new File(OUTPUT_FILE_NAME);
    ImageIO.write(image, IMAGE_FORMAT, file);
  }
}
