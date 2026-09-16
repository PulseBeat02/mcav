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

/**
 * The part of a single map that is covered by the image of a {@link MapLayout}.
 *
 * <p>The region has two coordinate systems. The local coordinates describe where the covered rectangle lies
 * inside the 128x128 map, and the source coordinates describe which pixel of the image is drawn at the local
 * origin. When the image is smaller than the map grid, maps at the border are only partly covered, and maps that
 * the image does not reach at all have an empty region.
 */
public final class MapRegion {

  private final int localX;
  private final int localY;
  private final int width;
  private final int height;
  private final int sourceX;
  private final int sourceY;

  /**
   * Constructs a new region.
   *
   * @param localX  the x coordinate of the covered rectangle inside the map
   * @param localY  the y coordinate of the covered rectangle inside the map
   * @param width   the width of the covered rectangle, or zero if the map is not covered
   * @param height  the height of the covered rectangle, or zero if the map is not covered
   * @param sourceX the x coordinate of the image pixel that is drawn at {@code localX}
   * @param sourceY the y coordinate of the image pixel that is drawn at {@code localY}
   */
  public MapRegion(final int localX, final int localY, final int width, final int height, final int sourceX, final int sourceY) {
    this.localX = localX;
    this.localY = localY;
    this.width = width;
    this.height = height;
    this.sourceX = sourceX;
    this.sourceY = sourceY;
  }

  /**
   * Gets the x coordinate of the covered rectangle inside the map.
   *
   * @return the local x coordinate
   */
  public int getLocalX() {
    return this.localX;
  }

  /**
   * Gets the y coordinate of the covered rectangle inside the map.
   *
   * @return the local y coordinate
   */
  public int getLocalY() {
    return this.localY;
  }

  /**
   * Gets the width of the covered rectangle.
   *
   * @return the width in pixels, or zero if the map is not covered
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Gets the height of the covered rectangle.
   *
   * @return the height in pixels, or zero if the map is not covered
   */
  public int getHeight() {
    return this.height;
  }

  /**
   * Gets the x coordinate of the image pixel that is drawn at the local x coordinate.
   *
   * @return the source x coordinate
   */
  public int getSourceX() {
    return this.sourceX;
  }

  /**
   * Gets the y coordinate of the image pixel that is drawn at the local y coordinate.
   *
   * @return the source y coordinate
   */
  public int getSourceY() {
    return this.sourceY;
  }

  /**
   * Checks whether the image does not cover the map at all.
   *
   * @return true if the region is empty
   */
  public boolean isEmpty() {
    return this.width <= 0 || this.height <= 0;
  }
}
