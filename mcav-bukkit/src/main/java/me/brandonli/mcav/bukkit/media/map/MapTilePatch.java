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

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A rectangular update of the colors of a single map.
 *
 * <p>A map is a 128x128 grid of pixels, and every pixel is stored as one byte: an index into the Minecraft map
 * color palette. A patch replaces the colors inside one rectangle of that grid, which is exactly what a single
 * map data packet can carry. The colors of the rectangle are stored row by row, so the color of the pixel at
 * {@code (x + column, y + row)} is found at index {@code row * width + column}.
 *
 * <p>Instances are immutable and thread-safe after construction, but the color array is not copied for performance
 * reasons. Callers must not modify the array after passing it to {@link #of(int, int, int, int, int, byte[])}.
 *
 * @since 1.0.0
 */
public final class MapTilePatch {

  private final int mapId;
  private final int left;
  private final int top;
  private final int width;
  private final int height;
  private final byte[] colors;

  private MapTilePatch(final int mapId, final int left, final int top, final int width, final int height, final byte[] colors) {
    this.mapId = mapId;
    this.left = left;
    this.top = top;
    this.width = width;
    this.height = height;
    this.colors = colors;
  }

  /**
   * Returns a patch of the given rectangle and colors.
   *
   * @param mapId  the nonnegative id of the map to update
   * @param left   the x coordinate of the top left corner of the rectangle, from 0 to 127
   * @param top    the y coordinate of the top left corner of the rectangle, from 0 to 127
   * @param width  the positive width in pixels, with the rectangle ending at or before coordinate 128
   * @param height the positive height in pixels, with the rectangle ending at or before coordinate 128
   * @param colors the map palette indices of the rectangle, exactly {@code width * height} bytes long
   * @return the patch
   * @throws IllegalArgumentException if the map id is negative, the rectangle is empty or outside the map,
   *                                  or the color array has the wrong size
   * @throws NullPointerException     if {@code colors} is null
   * @since 1.0.0
   */
  public static MapTilePatch of(final int mapId, final int left, final int top, final int width, final int height, final byte[] colors) {
    Preconditions.checkNotNull(colors, "Colors must not be null!");
    Preconditions.checkArgument(mapId >= 0, "Map id %s must not be negative!", mapId);

    final boolean negativeOrigin = left < 0 || top < 0;
    final boolean emptySize = width <= 0 || height <= 0;
    final boolean exceedsMap = (long) left + width > MapLayout.MAP_SIZE || (long) top + height > MapLayout.MAP_SIZE;
    final boolean insideMap = !negativeOrigin && !emptySize && !exceedsMap;
    Preconditions.checkArgument(insideMap, "Patch is outside of the map bounds!");

    final int expectedLength = width * height;
    Preconditions.checkArgument(colors.length == expectedLength, "Patch colors must contain exactly width * height bytes!");

    return new MapTilePatch(mapId, left, top, width, height, colors);
  }

  /**
   * Returns the id of the map this patch updates.
   *
   * @return the map id
   * @since 1.0.0
   */
  public int getMapId() {
    return this.mapId;
  }

  /**
   * Returns the x coordinate of the top left corner of the rectangle inside the map.
   *
   * @return the x coordinate, from 0 to 127
   * @since 1.0.0
   */
  public int getX() {
    return this.left;
  }

  /**
   * Returns the y coordinate of the top left corner of the rectangle inside the map.
   *
   * @return the y coordinate, from 0 to 127
   * @since 1.0.0
   */
  public int getY() {
    return this.top;
  }

  /**
   * Returns the width of the rectangle.
   *
   * @return the width in pixels
   * @since 1.0.0
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Returns the height of the rectangle.
   *
   * @return the height in pixels
   * @since 1.0.0
   */
  public int getHeight() {
    return this.height;
  }

  /**
   * Returns the map palette indices of the rectangle, stored row by row. The returned array is not a copy and must
   * not be modified.
   *
   * @return the colors of the rectangle
   * @since 1.0.0
   */
  public byte[] getColors() {
    return this.colors;
  }

  /**
   * Returns the approximate number of bytes this patch occupies on the network, including the packet overhead.
   *
   * @return the size of the patch in bytes
   * @since 1.0.0
   */
  public int getEncodedSize() {
    return this.colors.length + MapLayout.PATCH_OVERHEAD;
  }

  /**
   * Returns whether {@code other} is a patch of the same map, rectangle and colors.
   *
   * @param other the object to compare with
   * @return whether every part matches
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final MapTilePatch patch)) {
      return false;
    }

    return (
      this.mapId == patch.mapId &&
      this.left == patch.left &&
      this.top == patch.top &&
      this.width == patch.width &&
      this.height == patch.height &&
      Arrays.equals(this.colors, patch.colors)
    );
  }

  /**
   * Returns a hash code built from every part.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    final int colorsHash = Arrays.hashCode(this.colors);
    return Objects.hash(this.mapId, this.left, this.top, this.width, this.height, colorsHash);
  }

  /**
   * Returns a debugging representation with the map id and the rectangle.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("mapId", this.mapId)
      .add("x", this.left)
      .add("y", this.top)
      .add("width", this.width)
      .add("height", this.height)
      .toString();
  }
}
