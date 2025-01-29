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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * DefaultPalette is a specialized implementation of the ColorPalette class.
 */
public final class MapPalette extends ColorPalette {

  /**
   * Constructs a DefaultPalette instance initialized with a predefined set of ~128 colors.
   */
  public MapPalette() {
    super(getPaletteColors());
  }

  private static List<Integer> getPaletteColors() {
    final List<Integer> colors = new ArrayList<>();
    for (int i = 0; i < 256; ++i) {
      try {
        final byte index = (byte) i;
        final Color color = MapPaletteLoader.getColor(index);
        final int rgb = color.getRGB();
        colors.add(rgb);
      } catch (final IndexOutOfBoundsException e) {
        break;
      }
    }
    return colors;
  }
}
