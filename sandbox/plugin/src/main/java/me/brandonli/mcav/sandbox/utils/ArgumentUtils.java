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
package me.brandonli.mcav.sandbox.utils;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Stream;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * Parses the command arguments shared by the commands of the plugin.
 */
public final class ArgumentUtils {

  private ArgumentUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Parses a size written as {@code <width>x<height>}, such as {@code 1280x720} for a resolution in pixels or
   * {@code 5x5} for a wall of maps. The separator must be a lowercase {@code x}, and there must be no spaces or
   * units.
   *
   * @param argument the size as the player typed it
   * @return the width as the first value and the height as the second value, both at least 1
   * @throws NullPointerException     if the argument is {@code null}
   * @throws IllegalArgumentException if the text has no {@code x}, if either side is not a whole number (reported as
   *                                  the subclass {@link NumberFormatException}), or if either side is zero or
   *                                  negative
   */
  public static Pair<Integer, Integer> parseDimensions(final String argument) {
    Preconditions.checkNotNull(argument, "Argument must not be null");
    final String[] sides = argument.split("x", 2);
    if (sides.length != 2) {
      throw new IllegalArgumentException("Invalid dimensions format: " + argument);
    }

    final int width = Integer.parseInt(sides[0]);
    final int height = Integer.parseInt(sides[1]);
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("Dimensions must be positive integers: " + argument);
    }
    return Pair.pair(width, height);
  }

  /**
   * Resolves a player selector, such as a player name, {@code @a} or {@code @a[distance=..20]}, to the players it
   * matches right now. The result is a snapshot: players who join or come into range later do not see the media
   * until the command is run again.
   *
   * @param playerSelector the selector the command received
   * @return the unique ids of the matched players, as an unmodifiable collection
   * @throws NullPointerException if the selector is {@code null}
   */
  public static Collection<UUID> parsePlayerSelectors(final MultiplePlayerSelector playerSelector) {
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    final Collection<Player> players = playerSelector.values();
    final Stream<Player> playerStream = players.stream();
    final Stream<UUID> ids = playerStream.map(Entity::getUniqueId);
    return ids.toList();
  }
}
