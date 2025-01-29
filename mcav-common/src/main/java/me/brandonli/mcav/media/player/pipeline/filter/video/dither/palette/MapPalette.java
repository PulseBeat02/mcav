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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * DefaultPalette is a specialized implementation of the ColorPalette class, which provides
 * a default color palette containing 100+ colors extracted using the MapPalette utility.
 * This palette is pre-initialized during construction with an optimized list of RGB values.
 * <p>
 * The colors in the palette are generated using the MapPalette.getColor method, which maps
 * byte indices to their corresponding Color values. These colors are then converted to their
 * RGB integer representation and stored in the palette.
 * <p>
 * The DefaultPalette is primarily used when no custom color palette is provided and serves
 * as a reusable, predefined set of indexed colors that Minecraft maps support.
 */
public final class MapPalette extends ColorPalette {

  /**
   * Constructs a DefaultPalette instance initialized with a predefined set of ~128 colors.
   * These colors are derived using the MapPalette utility, which maps byte indices to RGB
   * color values. The resulting color list is passed to the parent class, ColorPalette,
   * for further initialization of the palette and associated mapping structures.
   * <p>
   * The DefaultPalette is intended to provide a reusable, standardized color palette
   * for scenarios where no custom palette is specified.
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
