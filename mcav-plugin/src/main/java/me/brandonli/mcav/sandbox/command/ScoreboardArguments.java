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
package me.brandonli.mcav.sandbox.command;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/**
 * Checks the arguments of the scoreboard commands before they load any media.
 */
public final class ScoreboardArguments {

  private ScoreboardArguments() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Checks that a resolution such as {@code 24x15} fits on a scoreboard, which shows at most
   * {@value ScoreboardConfiguration#MAX_LINES} lines, and tells the sender when it does not. A resolution that
   * cannot be parsed passes this check, so the command reports it like any other invalid resolution.
   *
   * @param sender     who ran the command
   * @param resolution the resolution as entered, whose height is the number of lines
   * @return true if the command may go on, false if the sender was told that the height is too large
   */
  public static boolean checkLines(final CommandSender sender, final String resolution) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(resolution, "Resolution must not be null");
    final Pair<Integer, Integer> dimensions;
    try {
      dimensions = ArgumentUtils.parseDimensions(resolution);
    } catch (final IllegalArgumentException exception) {
      return true;
    }
    final int lines = dimensions.getSecond();
    if (lines <= ScoreboardConfiguration.MAX_LINES) {
      return true;
    }
    final Component message = Message.SCOREBOARD_LINES.build();
    sender.sendMessage(message);
    return false;
  }
}
