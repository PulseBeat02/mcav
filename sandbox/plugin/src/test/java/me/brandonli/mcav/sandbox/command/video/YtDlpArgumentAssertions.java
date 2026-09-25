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
package me.brandonli.mcav.sandbox.command.video;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

/**
 * What may reach the command line of yt-dlp from the flags of a video command, shared by the property test and the
 * fuzz test of {@link VideoFlagsParser}: an argument that starts with a dash is always an accepted option, a switch never
 * takes the next argument, and an option with a value takes exactly one argument that yt-dlp cannot read as an option.
 */
final class YtDlpArgumentAssertions {

  /**
   * The accepted options that are switches; every other accepted option takes a value.
   */
  static final Set<String> SWITCHES = Set.of(
    "no-playlist",
    "yes-playlist",
    "prefer-free-formats",
    "no-prefer-free-formats",
    "check-formats",
    "check-all-formats",
    "no-check-formats",
    "video-multistreams",
    "no-video-multistreams",
    "audio-multistreams",
    "no-audio-multistreams",
    "format-sort-force",
    "no-format-sort-force",
    "geo-bypass",
    "no-geo-bypass",
    "no-match-filters"
  );

  private YtDlpArgumentAssertions() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Asserts that the arguments the parser produced from what a player typed are safe to hand to yt-dlp.
   *
   * @param typed     what the player typed
   * @param arguments what the parser produced
   */
  static void assertSafe(final String typed, final String[] arguments) {
    final List<String> supported = VideoFlagsParser.supportedOptions();
    int index = 0;
    while (index < arguments.length) {
      final String argument = arguments[index];
      final boolean option = argument.startsWith("--");
      assertTrue(option, () -> "'" + argument + "' reaches yt-dlp where an option belongs, from '" + typed + "'");
      final String name = argument.substring(2);
      final boolean accepted = supported.contains(name);
      assertTrue(accepted, () -> "the option " + name + " is not accepted, from '" + typed + "'");
      index++;
      if (SWITCHES.contains(name)) {
        continue;
      }
      final boolean hasValue = index < arguments.length;
      assertTrue(hasValue, () -> "the option " + name + " lost its value, from '" + typed + "'");
      final String value = arguments[index];
      final boolean readAsOption = value.startsWith("-");
      assertFalse(readAsOption, () -> "yt-dlp would read the value '" + value + "' as an option, from '" + typed + "'");
      index++;
    }
  }
}
