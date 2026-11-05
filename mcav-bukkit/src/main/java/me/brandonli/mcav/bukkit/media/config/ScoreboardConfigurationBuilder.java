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

/**
 * Builds scoreboard configurations. Every value is required.
 *
 * <p>Builders are mutable and not thread-safe. Setters retain reference arguments; numeric ranges and
 * required fields are checked by {@link #build()}, which may be called again to create another configuration.
 *
 * @since 1.0.0
 */
public interface ScoreboardConfigurationBuilder {
  /**
   * Sets the players who see the scoreboard. The collection is not copied, see {@link ScoreboardConfiguration}.
   *
   * @param viewers the UUIDs of the viewers
   * @return this builder
   * @throws NullPointerException if the viewers are null
   * @since 1.0.0
   */
  ScoreboardConfigurationBuilder viewers(final Collection<UUID> viewers);

  /**
   * Sets the text drawn for every pixel.
   *
   * <p>The text must be nonempty when {@link #build()} is called. Multiple characters are allowed, but
   * change the visual width of a pixel; no font or character-width validation is performed.
   *
   * @param character the pixel text, usually a single character such as {@code █}
   * @return this builder
   * @throws NullPointerException if the character is null
   * @since 1.0.0
   */
  ScoreboardConfigurationBuilder character(final String character);

  /**
   * Sets the height of the image in lines.
   *
   * @param lines the number of lines, from 1 to {@link ScoreboardConfiguration#MAXIMUM_LINES}
   * @return this builder
   * @since 1.0.0
   */
  ScoreboardConfigurationBuilder lines(final int lines);

  /**
   * Sets the width of the image in characters.
   *
   * @param width the width in characters, which must be positive
   * @return this builder
   * @since 1.0.0
   */
  ScoreboardConfigurationBuilder width(final int width);

  /**
   * Builds the scoreboard configuration.
   *
   * @return the scoreboard configuration
   * @throws IllegalArgumentException if a size is out of range or the character is empty
   * @throws IllegalStateException    if the viewers or the character were never set
   * @since 1.0.0
   */
  ScoreboardConfiguration build();
}
