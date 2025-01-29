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
package me.brandonli.mcav.media.video.dither.palette;

/**
 * The Palette interface defines the structure for managing and retrieving a set of colors
 * used in video dithering or other applications requiring indexed color palettes. It provides
 * methods to access the palette itself, a byte-indexed color map for efficient color lookup,
 * and a full-color map for detailed color mappings.
 * <p>
 * Implementations of this interface may utilize a predefined set of colors or allow for
 * custom color palettes to be defined dynamically.
 */
public interface Palette {
  /**
   * The DEFAULT variable represents the default implementation of the {@link Palette}
   * interface, instantiated as a {@link DefaultPalette}.
   * <p>
   * This default palette provides a pre-initialized, optimized set of RGB color values,
   * suitable for scenarios where a custom color palette is not specified. It is statically
   * defined to ensure consistency across color management and retrieval tasks.
   * <p>
   * The DEFAULT palette is especially useful in applications such as video dithering,
   * where a predefined indexed color structure facilitates efficient color mapping.
   *
   * @see Palette
   * @see DefaultPalette
   */
  Palette DEFAULT = new DefaultPalette();

  /**
   * Retrieves the palette consisting of an array of colors. Each color is represented
   * as an integer value, typically using an RGB encoding packed into a single integer.
   *
   * @return an array of integers where each integer represents a color from the palette
   * using RGB encoding.
   */
  int[] getPalette();

  /**
   * Returns the lookup table mapping 7-bit RGB values to their corresponding
   * palette indices, which can then be used to efficiently retrieve the colors
   * from the palette.
   * <p>
   * The color map provides a one-dimensional indexed array where the index is
   * calculated based on the RGB values. It is used to map colors in a reduced
   * RGB space to their nearest matching color in the palette.
   *
   * @return a byte array representing the color map. Each position in the array
   * corresponds to an RGB mapping and contains the index of the closest
   * color in the palette.
   */
  byte[] getColorMap();

  /**
   * Retrieves the full color mapping table, which provides a detailed mapping
   * of color indices to their respective RGB color values. Each entry in the
   * table represents an RGB color as an integer, precomputed for efficient
   * lookup during color operations.
   *
   * @return an array of integers where each element represents an RGB color
   * value encoded as a single integer. The array provides a dense
   * mapping of all potential color combinations, allowing for
   * fast and efficient color retrieval during processing.
   */
  int[] getFullColorMap();
}
