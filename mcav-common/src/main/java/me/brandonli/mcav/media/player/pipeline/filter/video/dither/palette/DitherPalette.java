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

import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Objects;

/**
 * A fixed set of at most 256 colors that images are reduced to, together with lookup tables that find the closest
 * palette color for any RGB color in constant time.
 *
 * <p>The lookup tables use 7 bits per channel, so they hold two million entries and take about a second to build.
 * Palettes are therefore built once and shared; use {@link #DEFAULT_MAP_PALETTE} for Minecraft maps rather than
 * creating new instances. Palettes are immutable and thread-safe.
 *
 * <p>Colors are packed as opaque ARGB integers, {@code 0xFFRRGGBB}. A palette may reserve its first indices for
 * transparency; those indices are never returned by the lookup tables.
 */
public interface DitherPalette {
  /**
   * The Minecraft map palette, whose first four indices are transparent.
   */
  DitherPalette DEFAULT_MAP_PALETTE = new MapPalette();

  /**
   * A palette of eight basic colors: red, green, blue, white, black, yellow, cyan, and purple.
   */
  DitherPalette EIGHT_BIT_PALETTE = colors(0xFFFF0000, 0xFF00D93A, 0xFF3737DC, 0xFFFFFFFF, 0xFF000000, 0xFFE5E533, 0xFF5CDBD5, 0xFFB24CD8);

  /**
   * Builds the default map palette ahead of time, so the first dithering operation does not pay for it.
   */
  static void init() {
    // touching the palette forces the class initialization that builds its lookup tables
    Objects.requireNonNull(DEFAULT_MAP_PALETTE, "Map palette");
  }

  /**
   * Creates a palette from RGB colors. The alpha channel of the colors is ignored, and no index is reserved for
   * transparency.
   *
   * @param colors the colors, at most 256
   * @return the palette
   */
  static DitherPalette colors(final int... colors) {
    Preconditions.checkNotNull(colors, "Colors must not be null");
    return new ColorPalette(colors, 0);
  }

  /**
   * Creates a palette from RGB colors. The alpha channel of the colors is ignored, and no index is reserved for
   * transparency.
   *
   * @param colors the colors, at most 256, none of them null
   * @return the palette
   */
  static DitherPalette colors(final List<Integer> colors) {
    Preconditions.checkNotNull(colors, "Colors must not be null");

    final int size = colors.size();
    final int[] array = new int[size];
    for (int index = 0; index < size; index++) {
      final Integer color = colors.get(index);
      Preconditions.checkNotNull(color, "Color %s must not be null", index);
      array[index] = color;
    }
    return new ColorPalette(array, 0);
  }

  /**
   * Gets the colors of the palette as opaque ARGB integers, indexed by palette index. Reserved transparent
   * indices hold zero.
   *
   * @return the colors, which must not be modified
   */
  int[] getPalette();

  /**
   * Gets the number of colors, including reserved indices.
   *
   * @return the palette size
   */
  int getSize();

  /**
   * Gets the number of leading indices that are reserved for transparency and never returned by the lookup tables.
   *
   * @return the reserved index count
   */
  int getReservedIndices();

  /**
   * Gets the lookup table from 7-bit RGB colors to palette indices. The table is indexed with
   * {@code ((r >> 1) << 14) | ((g >> 1) << 7) | (b >> 1)}.
   *
   * @return the index lookup table, which must not be modified
   */
  byte[] getColorMap();

  /**
   * Gets the lookup table from 7-bit RGB colors to the closest palette color as opaque ARGB. The table is indexed
   * like {@link #getColorMap()}.
   *
   * @return the color lookup table, which must not be modified
   */
  int[] getFullColorMap();
}
