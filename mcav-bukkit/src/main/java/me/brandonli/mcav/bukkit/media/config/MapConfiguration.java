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
package me.brandonli.mcav.bukkit.media.config;

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.image.MapImage;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.bukkit.media.result.MapResult;

/**
 * Describes a screen made of maps, for use with {@link MapImage}, {@link MapResult} and {@link CompressedMapResult}.
 *
 * <p>The screen is a grid of {@code mapBlockWidth x mapBlockHeight} maps with consecutive ids, starting at
 * {@link #getMap()} for the top left map and counting row by row. A map holds 128x128 pixels, so the screen has a
 * native resolution of {@code 128 * mapBlockWidth} by {@code 128 * mapBlockHeight} pixels. Images that are
 * smaller than the screen are centered, and images that are larger are cropped equally on every side.
 *
 * <p>The viewers collection is not copied. It is read for every frame, so a concurrent collection can be passed
 * to add or remove viewers while media is playing.
 *
 * {@snippet :
 * final int firstMapId = 0;
 * final int mapBlockWidth = 5;
 * final int mapBlockHeight = 5;
 * final MapConfigurationBuilder builder = MapConfiguration.builder();
 * final MapConfiguration configuration = builder
 *   .viewers(viewers)
 *   .map(firstMapId)
 *   .mapBlockWidth(mapBlockWidth)
 *   .mapBlockHeight(mapBlockHeight)
 *   .build();
 * }
 *
 * <p>This configuration does not create map items, reserve map ids or place item frames. The caller supplies
 * the display surfaces and ensures the consecutive ids belong to this screen. Resizing is disabled by default.
 *
 * <p>This class is immutable and thread-safe, except for the viewer collection, which it shares with the caller instead
 * of copying.
 *
 * @since 1.0.0
 */
public final class MapConfiguration {

  private final Collection<UUID> viewers;
  private final int map;
  private final int mapBlockWidth;
  private final int mapBlockHeight;
  private final int mapWidthResolution;
  private final int mapHeightResolution;
  private final boolean shouldResize;

  MapConfiguration(
    final Collection<UUID> viewers,
    final int map,
    final int mapBlockWidth,
    final int mapBlockHeight,
    final int mapWidthResolution,
    final int mapHeightResolution,
    final boolean shouldResize
  ) {
    this.viewers = viewers;
    this.map = map;
    this.mapBlockWidth = mapBlockWidth;
    this.mapBlockHeight = mapBlockHeight;
    this.mapWidthResolution = mapWidthResolution;
    this.mapHeightResolution = mapHeightResolution;
    this.shouldResize = shouldResize;
  }

  /**
   * Creates a new builder for a map configuration.
   *
   * @return a new builder
   * @since 1.0.0
   */
  public static MapConfigurationBuilder builder() {
    return new DefaultMapConfigurationBuilder();
  }

  /**
   * Returns whether frames should be resized to the configured resolution before they are displayed.
   *
   * @return true if frames should be resized, false otherwise
   * @since 1.0.0
   */
  public boolean shouldResize() {
    return this.shouldResize;
  }

  /**
   * Returns the players who see the maps.
   *
   * @return the original mutable-or-immutable collection supplied to the builder, without a copy; use a
   *         concurrent collection if another thread changes membership during rendering
   * @since 1.0.0
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Returns the id of the top left map of the screen.
   *
   * @return the id of the first map
   * @since 1.0.0
   */
  public int getMap() {
    return this.map;
  }

  /**
   * Returns the width of the screen in maps.
   *
   * @return the number of maps horizontally
   * @since 1.0.0
   */
  public int getMapBlockWidth() {
    return this.mapBlockWidth;
  }

  /**
   * Returns the height of the screen in maps.
   *
   * @return the number of maps vertically
   * @since 1.0.0
   */
  public int getMapBlockHeight() {
    return this.mapBlockHeight;
  }

  /**
   * Returns the width in pixels that images are resized to if {@link #shouldResize()} is set. Defaults to the native
   * width of the screen.
   *
   * @return the target width in pixels
   * @since 1.0.0
   */
  public int getMapWidthResolution() {
    return this.mapWidthResolution;
  }

  /**
   * Returns the height in pixels that images are resized to if {@link #shouldResize()} is set. Defaults to the native
   * height of the screen.
   *
   * @return the target height in pixels
   * @since 1.0.0
   */
  public int getMapHeightResolution() {
    return this.mapHeightResolution;
  }
}
