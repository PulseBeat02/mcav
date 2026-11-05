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
package me.brandonli.mcav.plugin.utils;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Stream;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * Parses the command arguments shared by the commands of the plugin.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class ArgumentUtils {

  private static final String PLAYER_SELECTOR_NULL_MESSAGE = "Player selector must not be null!";

  /**
   * The largest width or height {@link #parseDimensions(String)} accepts, in pixels or blocks. 8192 pixels is the
   * native resolution of a wall of {@value #MAXIMUM_SCREEN_SIDE} maps, so no display of the plugin needs more.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_SIDE = 8192;

  /**
   * The largest width or height of a wall of maps that {@link #parseScreenDimensions(String)} accepts, in maps. The
   * largest wall, 64 by 64, has 4096 maps, which is the number of packets a client accepts in one bundle, so a whole
   * frame of it, and clearing it, still reach every viewer at once.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_SCREEN_SIDE = 64;

  // resolveViewers keeps following this selector
  private static final String EVERYONE = "@a";

  private static final String DIMENSION_SEPARATOR = "x";
  private static final int DIMENSION_PARTS = 2;

  private ArgumentUtils() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
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
   *                                  negative or larger than {@link #MAXIMUM_SIDE}
   * @since 1.0.0
   */
  public static Pair<Integer, Integer> parseDimensions(final String argument) {
    Preconditions.checkNotNull(argument, "Argument must not be null!");

    final String[] sides = argument.split(DIMENSION_SEPARATOR, DIMENSION_PARTS);
    if (sides.length != DIMENSION_PARTS) {
      final String message = "Dimensions %s have an invalid format!".formatted(argument);
      throw new IllegalArgumentException(message);
    }

    final int width = Integer.parseInt(sides[0]);
    final int height = Integer.parseInt(sides[1]);
    if (width <= 0 || height <= 0) {
      final String message = "Dimensions %s must be positive integers!".formatted(argument);
      throw new IllegalArgumentException(message);
    }

    if (width > MAXIMUM_SIDE || height > MAXIMUM_SIDE) {
      final String message = "Dimensions %s must be at most %s on each side!".formatted(argument, MAXIMUM_SIDE);
      throw new IllegalArgumentException(message);
    }

    return Pair.pair(width, height);
  }

  /**
   * Parses the size of a wall of maps written as {@code <width>x<height>}, such as {@code 5x5}. Besides the rules of
   * {@link #parseDimensions(String)}, a side may have at most {@value #MAXIMUM_SCREEN_SIDE} maps, because every map of a
   * wall is built on the main thread and sent to every viewer.
   *
   * @param argument the size as the player typed it
   * @return the width as the first value and the height as the second value, both at least 1
   * @throws NullPointerException     if the argument is {@code null}
   * @throws IllegalArgumentException if the text is not a valid size, or the wall is larger than the limits
   * @since 1.0.0
   */
  public static Pair<Integer, Integer> parseScreenDimensions(final String argument) {
    final Pair<Integer, Integer> dimensions = parseDimensions(argument);
    final int width = dimensions.getFirst();
    final int height = dimensions.getSecond();
    if (width > MAXIMUM_SCREEN_SIDE || height > MAXIMUM_SCREEN_SIDE) {
      final String message = "Wall %s must be at most %s maps on each side!".formatted(argument, MAXIMUM_SCREEN_SIDE);
      throw new IllegalArgumentException(message);
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
   * @since 1.0.0
   */
  public static Collection<UUID> resolvePlayerIds(final MultiplePlayerSelector playerSelector) {
    Preconditions.checkNotNull(playerSelector, PLAYER_SELECTOR_NULL_MESSAGE);

    final Collection<Player> players = playerSelector.values();
    final Stream<Player> playerStream = players.stream();
    final Stream<UUID> ids = playerStream.map(Entity::getUniqueId);
    return ids.toList();
  }

  /**
   * Resolves the viewers of a wall of maps: for {@code @a} exactly, every player online, including those who join
   * while the wall plays; for any other selector the players it matches right now, as
   * {@link #resolvePlayerIds(MultiplePlayerSelector)} does.
   *
   * @param playerSelector the selector the command received
   * @param online         the players online
   * @return the unique ids of the viewers, a live view for {@code @a}
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  public static Collection<UUID> resolveViewers(final MultiplePlayerSelector playerSelector, final OnlinePlayers online) {
    Preconditions.checkNotNull(playerSelector, PLAYER_SELECTOR_NULL_MESSAGE);
    Preconditions.checkNotNull(online, "Online players must not be null!");

    final String input = playerSelector.inputString();
    if (EVERYONE.equals(input)) {
      return online.getPlayers();
    }

    return resolvePlayerIds(playerSelector);
  }
}
