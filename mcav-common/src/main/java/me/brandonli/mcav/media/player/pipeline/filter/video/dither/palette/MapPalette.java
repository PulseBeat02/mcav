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

/**
 * The color palette of Minecraft maps, as loaded by {@link MapPaletteLoader}. The first four indices of the map
 * palette are transparent and are therefore reserved. Use {@link DitherPalette#DEFAULT_MAP_PALETTE} instead of
 * creating instances, because every instance builds its own lookup tables.
 */
public final class MapPalette extends ColorPalette {

  /**
   * The number of leading map colors that are transparent.
   */
  public static final int TRANSPARENT_INDICES = 4;

  /**
   * Constructs a new map palette and builds its lookup tables, which takes about a second.
   */
  public MapPalette() {
    final int[] colors = MapPaletteLoader.getColors();
    super(colors, TRANSPARENT_INDICES);
  }
}
