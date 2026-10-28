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
package me.brandonli.mcav.plugin.command;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.plugin.utils.ArgumentUtils;
import me.brandonli.mcav.utils.immutable.Pair;

/**
 * Checks the arguments of the scoreboard commands before they load any media.
 */
public final class ScoreboardArguments {

  private ScoreboardArguments() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Returns whether a resolution such as {@code 24x16} is too tall for a scoreboard, which shows at most
   * {@value ScoreboardConfiguration#MAXIMUM_LINES} lines. A resolution that cannot be parsed is not too tall, so the
   * command reports it like any other invalid resolution.
   *
   * @param resolution the resolution as entered, whose height is the number of lines
   * @return whether the height is above the line limit
   * @throws NullPointerException if {@code resolution} is null
   */
  public static boolean isTooTall(final String resolution) {
    Preconditions.checkNotNull(resolution, "Resolution must not be null");
    final Pair<Integer, Integer> dimensions;
    try {
      dimensions = ArgumentUtils.parseDimensions(resolution);
    } catch (final IllegalArgumentException exception) {
      return false;
    }
    final int lines = dimensions.getSecond();
    return lines > ScoreboardConfiguration.MAXIMUM_LINES;
  }
}
