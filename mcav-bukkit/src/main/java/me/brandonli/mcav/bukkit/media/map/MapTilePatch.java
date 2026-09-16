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
package me.brandonli.mcav.bukkit.media.map;

import com.google.common.base.Preconditions;

/**
 * A rectangular update of the colors of a single map.
 *
 * <p>A map is a 128x128 grid of pixels, and every pixel is stored as one byte: an index into the Minecraft map
 * color palette. A patch replaces the colors inside one rectangle of that grid, which is exactly what a single
 * map data packet can carry. The colors of the rectangle are stored row by row, so the color of the pixel at
 * {@code (x + column, y + row)} is found at index {@code row * width + column}.
 *
 * <p>Instances are immutable after construction, but the color array is not copied for performance reasons.
 * Callers must not modify the array after passing it to the constructor.
 */
public final class MapTilePatch {

  private final int mapId;
  private final int x;
  private final int y;
  private final int width;
  private final int height;
  private final byte[] colors;

  /**
   * Constructs a new patch.
   *
   * @param mapId  the id of the map to update
   * @param x      the x coordinate of the top left corner of the rectangle, from 0 to 127
   * @param y      the y coordinate of the top left corner of the rectangle, from 0 to 127
   * @param width  the width of the rectangle in pixels
   * @param height the height of the rectangle in pixels
   * @param colors the map palette indices of the rectangle, exactly {@code width * height} bytes long
   * @throws IllegalArgumentException if the rectangle is not inside the map, or the color array has the wrong size
   */
  public MapTilePatch(final int mapId, final int x, final int y, final int width, final int height, final byte[] colors) {
    Preconditions.checkNotNull(colors, "Colors must not be null");
    Preconditions.checkArgument(mapId >= 0, "Map id must be non-negative");

    final boolean negativeOrigin = x < 0 || y < 0;
    final boolean emptySize = width <= 0 || height <= 0;
    final boolean exceedsMap = x + width > MapLayout.MAP_SIZE || y + height > MapLayout.MAP_SIZE;
    final boolean insideMap = !negativeOrigin && !emptySize && !exceedsMap;
    Preconditions.checkArgument(insideMap, "Patch is outside of the map bounds");

    final int expectedLength = width * height;
    Preconditions.checkArgument(colors.length == expectedLength, "Patch colors must contain exactly width * height bytes");

    this.mapId = mapId;
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
    this.colors = colors;
  }

  /**
   * Gets the id of the map this patch updates.
   *
   * @return the map id
   */
  public int getMapId() {
    return this.mapId;
  }

  /**
   * Gets the x coordinate of the top left corner of the rectangle inside the map.
   *
   * @return the x coordinate, from 0 to 127
   */
  public int getX() {
    return this.x;
  }

  /**
   * Gets the y coordinate of the top left corner of the rectangle inside the map.
   *
   * @return the y coordinate, from 0 to 127
   */
  public int getY() {
    return this.y;
  }

  /**
   * Gets the width of the rectangle.
   *
   * @return the width in pixels
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Gets the height of the rectangle.
   *
   * @return the height in pixels
   */
  public int getHeight() {
    return this.height;
  }

  /**
   * Gets the map palette indices of the rectangle, stored row by row. The returned array is not a copy and must
   * not be modified.
   *
   * @return the colors of the rectangle
   */
  public byte[] getColors() {
    return this.colors;
  }

  /**
   * Gets the approximate number of bytes this patch occupies on the network, including the packet overhead.
   *
   * @return the size of the patch in bytes
   */
  public int getEncodedSize() {
    return this.colors.length + MapLayout.PATCH_OVERHEAD;
  }
}
