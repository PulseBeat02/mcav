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

import me.brandonli.mcav.bukkit.media.map.MapLayout;
import org.checkerframework.checker.nullness.qual.Nullable;

record WallPicture(int wallWidth, int wallHeight, int sourceWidth, int sourceHeight, boolean stretched) {
  private static final int SIDES = 2;

  static WallPicture of(final int columns, final int rows, final int sourceWidth, final int sourceHeight, final boolean stretched) {
    final int wallWidth = columns * MapLayout.MAP_SIZE;
    final int wallHeight = rows * MapLayout.MAP_SIZE;
    return new WallPicture(wallWidth, wallHeight, sourceWidth, sourceHeight, stretched);
  }

  int @Nullable [] toSource(final int wallX, final int wallY) {
    if (this.stretched) {
      final int sourceX = this.scale(wallX, this.sourceWidth, this.wallWidth);
      final int sourceY = this.scale(wallY, this.sourceHeight, this.wallHeight);
      return new int[] { sourceX, sourceY };
    }

    // centered as the maps center it (MapLayout): an odd difference rounds toward zero
    final int offsetX = (this.wallWidth - this.sourceWidth) / SIDES;
    final int offsetY = (this.wallHeight - this.sourceHeight) / SIDES;
    final int sourceX = wallX - offsetX;
    final int sourceY = wallY - offsetY;
    final boolean inside = sourceX >= 0 && sourceY >= 0 && sourceX < this.sourceWidth && sourceY < this.sourceHeight;
    return inside ? new int[] { sourceX, sourceY } : null;
  }

  private int scale(final int wallCoordinate, final int sourceSide, final int wallSide) {
    final long scaled = (long) wallCoordinate * sourceSide;
    final long divided = scaled / wallSide;
    return (int) divided;
  }
}
