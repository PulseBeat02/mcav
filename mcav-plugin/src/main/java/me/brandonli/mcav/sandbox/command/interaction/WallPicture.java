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
package me.brandonli.mcav.sandbox.command.interaction;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * How the picture of a source sits on its wall of maps, which turns the pixel a player clicks on the wall into the
 * pixel of the source. An MCV2 screen, and the dithered maps of its viewers without the pack, stretch the picture over
 * the wall; plain dithered maps show it at its own size in the middle of the wall, cut off where it is larger than the
 * wall, with an empty border where it is smaller.
 *
 * @param wallWidth    the width of the wall in pixels, 128 a map
 * @param wallHeight   the height of the wall in pixels
 * @param sourceWidth  the width of the source in pixels
 * @param sourceHeight the height of the source in pixels
 * @param stretched    whether the picture is stretched over the wall
 */
record WallPicture(int wallWidth, int wallHeight, int sourceWidth, int sourceHeight, boolean stretched) {
  private static final int MAP_PIXELS = 128;

  /**
   * Describes the picture of a source on a wall of maps.
   *
   * @param columns      the maps across the wall
   * @param rows         the maps down the wall
   * @param sourceWidth  the width of the source in pixels
   * @param sourceHeight the height of the source in pixels
   * @param stretched    whether the picture is stretched over the wall
   * @return the picture
   */
  static WallPicture of(final int columns, final int rows, final int sourceWidth, final int sourceHeight, final boolean stretched) {
    return new WallPicture(columns * MAP_PIXELS, rows * MAP_PIXELS, sourceWidth, sourceHeight, stretched);
  }

  /**
   * Gets the pixel of the source at a pixel of the wall.
   *
   * @param wallX the column of the pixel on the wall, from its left edge
   * @param wallY the row of the pixel on the wall, from its top edge
   * @return the column and row of the source's pixel, or null for a pixel of the empty border around a smaller picture
   */
  int @Nullable [] toSource(final int wallX, final int wallY) {
    if (this.stretched) {
      final int sourceX = (int) (((long) wallX * this.sourceWidth) / this.wallWidth);
      final int sourceY = (int) (((long) wallY * this.sourceHeight) / this.wallHeight);
      return new int[] { sourceX, sourceY };
    }
    // centred as the maps centre it (MapLayout): the same offset on both sides, rounded down
    final int sourceX = wallX - (this.wallWidth - this.sourceWidth) / 2;
    final int sourceY = wallY - (this.wallHeight - this.sourceHeight) / 2;
    final boolean inside = sourceX >= 0 && sourceY >= 0 && sourceX < this.sourceWidth && sourceY < this.sourceHeight;
    return inside ? new int[] { sourceX, sourceY } : null;
  }
}
