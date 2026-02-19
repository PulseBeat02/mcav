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

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.List;
import javax.imageio.ImageIO;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.utils.IOUtils;

public final class PaletteGenerator {

  private static final String INPUT = "palette.json";
  private static final String OUTPUT = "palette.png";

  public static void main(final String[] args) throws Exception {
    final Type t = new TypeToken<List<List<Integer>>>() {}.getType();
    final Gson gson = GsonProvider.getSimple();
    final List<List<Integer>> colors;
    try (final Reader reader = IOUtils.getResourceAsStreamReader(INPUT)) {
      colors = gson.fromJson(reader, t);
      if (colors == null) {
        throw new AssertionError("Invalid palette data");
      }
    }

    final BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    for (int i = 0; i < 256; i++) {
      final List<Integer> c = colors.get(i);
      final int r = c.get(0);
      final int g = c.get(1);
      final int b = c.get(2);
      final int x = i & 15;
      final int y = i >> 4;
      final int argb = (0xFF << 24) | (r << 16) | (g << 8) | b;
      img.setRGB(x, y, argb);
    }

    final File file = new File(OUTPUT);
    ImageIO.write(img, "png", file);
  }
}
