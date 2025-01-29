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

import java.util.List;

/**
 * The Palette interface defines the structure for managing and retrieving a set of colors.
 */
public interface DitherPalette {
  /**
   * Represents a default palette used for mapping colors in video processing (map palette).
   */
  DitherPalette DEFAULT_MAP_PALETTE = new MapPalette();

  /**
   * Represents a predefined 8-bit color palette
   */
  DitherPalette EIGHT_BIT_PALETTE = colors(-65536, -16721606, -13158436, -1, -16777216, -1710797, -10691627, -5092136);

  /**
   * Initializes the palette system.
   */
  static void init() {
    // init
  }

  /**
   * Creates a new Palette instance using the provided array of colors.
   *
   * @param colors an array of integers where each integer represents a
   *               color, typically encoded as an RGB value.
   * @return a Palette object initialized with the specified colors.
   */
  static DitherPalette colors(final int... colors) {
    return new ColorPalette(colors);
  }

  /**
   * Creates a new {@code Palette} from a list of colors.
   *
   * @param colors a list of integers where each integer represents an RGB color.
   *               The list is used to define the set of colors included in the palette.
   * @return a {@code Palette} instance containing the specified list of colors.
   */
  static DitherPalette colors(final List<Integer> colors) {
    return new ColorPalette(colors);
  }

  /**
   * Retrieves the palette consisting of an array of colors.
   *
   * @return an array of integers representing the colors in the palette,
   */
  int[] getPalette();

  /**
   * Returns the lookup table mapping 7-bit RGB values to their corresponding
   * palette indices, which can then be used to efficiently retrieve the colors
   * from the palette.
   *
   * @return a byte array where each index corresponds to a 7-bit RGB value,
   */
  byte[] getColorMap();

  /**
   * Retrieves the full color mapping table, which provides a detailed mapping
   * of color indices to their respective RGB color values.
   *
   * @return an array of integers where each index corresponds to a color in the palette,
   */
  int[] getFullColorMap();
}
