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
package me.brandonli.mcav.plugin.command.interaction;

import org.checkerframework.checker.nullness.qual.Nullable;

record WallPicture(int wallWidth, int wallHeight, int sourceWidth, int sourceHeight, boolean stretched) {
  private static final int MAP_PIXELS = 128;

  private static final int SIDES = 2;

  static WallPicture of(final int columns, final int rows, final int sourceWidth, final int sourceHeight, final boolean stretched) {
    return new WallPicture(columns * MAP_PIXELS, rows * MAP_PIXELS, sourceWidth, sourceHeight, stretched);
  }

  int @Nullable [] toSource(final int wallX, final int wallY) {
    if (this.stretched) {
      final int sourceX = (int) (((long) wallX * this.sourceWidth) / this.wallWidth);
      final int sourceY = (int) (((long) wallY * this.sourceHeight) / this.wallHeight);
      return new int[] { sourceX, sourceY };
    }

    // centred as the maps centre it (MapLayout): the same offset on both sides, rounded down
    final int sourceX = wallX - (this.wallWidth - this.sourceWidth) / SIDES;
    final int sourceY = wallY - (this.wallHeight - this.sourceHeight) / SIDES;
    final boolean inside = sourceX >= 0 && sourceY >= 0 && sourceX < this.sourceWidth && sourceY < this.sourceHeight;
    return inside ? new int[] { sourceX, sourceY } : null;
  }
}
