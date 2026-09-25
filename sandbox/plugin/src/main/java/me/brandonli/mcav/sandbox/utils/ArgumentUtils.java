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

  /**
   * The largest width or height {@link #parseDimensions(String)} accepts, in pixels or blocks. 8192 pixels is the
   * native resolution of a wall of {@value #MAX_SCREEN_SIDE} maps, so no display of the plugin needs more.
   */
  public static final int MAX_SIDE = 8192;

  /**
   * The largest width or height of a wall of maps that {@link #parseScreenDimensions(String)} accepts, in maps. The
   * largest wall, 64 by 64, has 4096 maps, which is the number of packets a client accepts in one bundle, so a whole
   * frame of it, and clearing it, still reach every viewer at once.
   */
  public static final int MAX_SCREEN_SIDE = 64;

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
   *                                  the subclass {@link NumberFormatException}), or if either side is zero,
   *                                  negative or larger than {@link #MAX_SIDE}
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
    if (width > MAX_SIDE || height > MAX_SIDE) {
      throw new IllegalArgumentException("Dimensions must be at most %d on each side: %s".formatted(MAX_SIDE, argument));
    }
    return Pair.pair(width, height);
  }

  /**
   * Parses the size of a wall of maps written as {@code <width>x<height>}, such as {@code 5x5}. Besides the rules of
   * {@link #parseDimensions(String)}, a side may have at most {@value #MAX_SCREEN_SIDE} maps, because every map of a
   * wall is built on the main thread and sent to every viewer.
   *
   * @param argument the size as the player typed it
   * @return the width as the first value and the height as the second value, both at least 1
   * @throws NullPointerException     if the argument is {@code null}
   * @throws IllegalArgumentException if the text is not a valid size, or the wall is larger than the limits
   */
  public static Pair<Integer, Integer> parseScreenDimensions(final String argument) {
    final Pair<Integer, Integer> dimensions = parseDimensions(argument);
    final int width = dimensions.getFirst();
    final int height = dimensions.getSecond();
    if (width > MAX_SCREEN_SIDE || height > MAX_SCREEN_SIDE) {
      throw new IllegalArgumentException("A wall may be at most %d maps on each side: %s".formatted(MAX_SCREEN_SIDE, argument));
    }
    return dimensions;
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
