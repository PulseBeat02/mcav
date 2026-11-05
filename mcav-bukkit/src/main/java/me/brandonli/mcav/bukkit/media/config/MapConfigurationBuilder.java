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
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;

/**
 * Builds map configurations. The map id, the grid size, and the viewers are required.
 *
 * <p>Builders are mutable and not thread-safe. Setters retain reference arguments; numeric ranges and
 * required fields are checked by {@link #build()}, which may be called again to create another configuration.
 *
 * @since 1.0.0
 */
public interface MapConfigurationBuilder {
  /**
   * Sets whether frames and images are resized to the configured resolution before they are displayed. Without
   * resizing, images smaller than the maps are centered and larger ones are cropped.
   *
   * <p>For videos, prefer resizing in the media player with its {@link DimensionAttachableCallback}, which is faster
   * because it happens before any other filter runs. Images shown with {@link DisplayableImage} have no player, so
   * this option is the way to fit them to the maps.
   *
   * @param shouldResize true to resize frames and images, false to show them at their original size
   * @return this builder
   * @since 1.0.0
   */
  MapConfigurationBuilder resize(final boolean shouldResize);

  /**
   * Sets the players who see the maps. The collection is not copied, see {@link MapConfiguration}.
   *
   * @param viewers the UUIDs of the viewers
   * @return this builder
   * @throws NullPointerException if the viewers are null
   * @since 1.0.0
   */
  MapConfigurationBuilder viewers(final Collection<UUID> viewers);

  /**
   * Sets the id of the top left map of the screen.
   *
   * @param map the id of the first map, which must be non-negative
   * @return this builder
   * @since 1.0.0
   */
  MapConfigurationBuilder map(final int map);

  /**
   * Sets the width of the screen in maps.
   *
   * @param mapBlockWidth the number of maps horizontally, which must be positive
   * @return this builder
   * @since 1.0.0
   */
  MapConfigurationBuilder mapBlockWidth(final int mapBlockWidth);

  /**
   * Sets the height of the screen in maps.
   *
   * @param mapBlockHeight the number of maps vertically, which must be positive
   * @return this builder
   * @since 1.0.0
   */
  MapConfigurationBuilder mapBlockHeight(final int mapBlockHeight);

  /**
   * Sets the width in pixels that images are resized to. Defaults to {@code 128 * mapBlockWidth}.
   *
   * @param mapWidthResolution the target width in pixels, which must not be negative; 0 selects the default
   * @return this builder
   * @since 1.0.0
   */
  MapConfigurationBuilder mapWidthResolution(final int mapWidthResolution);

  /**
   * Sets the height in pixels that images are resized to. Defaults to {@code 128 * mapBlockHeight}.
   *
   * @param mapHeightResolution the target height in pixels, which must not be negative; 0 selects the default
   * @return this builder
   * @since 1.0.0
   */
  MapConfigurationBuilder mapHeightResolution(final int mapHeightResolution);

  /**
   * Builds the map configuration. The builder is not changed, so it can be reused to build more configurations.
   *
   * @return the map configuration
   * @throws IllegalArgumentException if the first map id is negative, a grid dimension is nonpositive,
   *                                  a requested resolution is negative, or a default resolution would overflow an integer
   * @throws IllegalStateException    if the viewers were never set
   * @since 1.0.0
   */
  MapConfiguration build();
}
