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
import org.bukkit.Location;

/**
 * Builds block configurations. Every value is required.
 *
 * <p>Builders are mutable and not thread-safe. Setters retain reference arguments; numeric ranges and
 * required fields are checked by {@link #build()}, which may be called again to create another configuration.
 *
 * @since 1.0.0
 */
public interface BlockConfigurationBuilder {
  /**
   * Sets the players who see the block wall. The collection is not copied, see {@link BlockConfiguration}.
   *
   * @param viewers the UUIDs of the viewers
   * @return this builder
   * @throws NullPointerException if the viewers are null
   * @since 1.0.0
   */
  BlockConfigurationBuilder viewers(final Collection<UUID> viewers);

  /**
   * Sets the bottom center of the wall.
   *
   * <p>The location is stored without copying. Its world is checked when {@link #build()} is called.
   *
   * @param position the position of the wall, which must have a world
   * @return this builder
   * @throws NullPointerException if the position is null
   * @since 1.0.0
   */
  BlockConfigurationBuilder position(final Location position);

  /**
   * Sets the width of the wall in blocks.
   *
   * @param blockWidth the width in blocks, which must be positive
   * @return this builder
   * @since 1.0.0
   */
  BlockConfigurationBuilder blockWidth(final int blockWidth);

  /**
   * Sets the height of the wall in blocks.
   *
   * @param blockHeight the height in blocks, which must be positive
   * @return this builder
   * @since 1.0.0
   */
  BlockConfigurationBuilder blockHeight(final int blockHeight);

  /**
   * Builds the block configuration.
   *
   * @return the block configuration
   * @throws IllegalArgumentException if a size is not positive or the position has no world
   * @throws IllegalStateException    if the viewers or the position were never set
   * @since 1.0.0
   */
  BlockConfiguration build();
}
