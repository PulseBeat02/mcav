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

import static java.util.Objects.requireNonNull;

import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Parses the optional flags of the video commands. yt-dlp options are written as
 * {@code --yt-dlp{format=bestvideo,no-playlist}}, where a comma inside a value is escaped as {@code \,} and a
 * closing brace as <code>\}</code>; every option becomes {@code --name} followed by its value as a separate
 * argument. Other backslashes are kept as they are.
 */
public final class VideoFlagsParser {

  private static final String YT_DLP_FLAG = "--yt-dlp";
  private static final String QUOTED_YT_DLP_FLAG = Pattern.quote(YT_DLP_FLAG);
  private static final Pattern YT_DLP_PATTERN = Pattern.compile(QUOTED_YT_DLP_FLAG + "\\{((?:[^\\\\}]|\\\\.)*)}");
  private static final Pattern UNESCAPED_COMMA = Pattern.compile("(?<!\\\\),");
  // keeps empty options, including a trailing one, which appendOption skips
  private static final Splitter OPTION_SPLITTER = Splitter.on(UNESCAPED_COMMA);

  /**
   * Constructs the parser, which keeps no state and can be shared.
   */
  public VideoFlagsParser() {
    // stateless
  }

  /**
   * Extracts the yt-dlp arguments from the flags.
   *
   * @param flags the flags argument of the command, may be empty or {@code null}
   * @return the arguments to pass to yt-dlp, empty if there are none
   * @throws IllegalArgumentException if an option with a value has an empty name
   */
  public String[] parseYTDLPFlags(final @Nullable String flags) {
    final String content = extractOptions(flags);
    if (content == null) {
      return new String[0];
    }

    final List<String> arguments = new ArrayList<>();
    final Iterable<String> options = OPTION_SPLITTER.split(content);
    for (final String option : options) {
      appendOption(option, arguments);
    }
    return arguments.toArray(new String[0]);
  }

  /**
   * Finds the options inside {@code --yt-dlp{...}}.
   *
   * @return the options, or {@code null} if the flags contain no yt-dlp options
   */
  private static @Nullable String extractOptions(final @Nullable String flags) {
    if (flags == null || flags.isBlank()) {
      return null;
    }

    final Matcher matcher = YT_DLP_PATTERN.matcher(flags);
    final boolean found = matcher.find();
    if (!found) {
      return null;
    }

    // the group always takes part in a match
    final String group = matcher.group(1);
    final String content = requireNonNull(group);
    if (content.isBlank()) {
      return null;
    }
    return content;
  }

  /**
   * Turns one {@code name=value} or {@code name} option into yt-dlp arguments.
   */
  private static void appendOption(final String option, final List<String> arguments) {
    final String commasUnescaped = option.replace("\\,", ",");
    final String unescaped = commasUnescaped.replace("\\}", "}");
    final String trimmed = unescaped.trim();
    if (trimmed.isEmpty()) {
      return;
    }

    final int equals = trimmed.indexOf('=');
    if (equals < 0) {
      arguments.add("--" + trimmed);
      return;
    }

    final String name = trimmed.substring(0, equals);
    final String value = trimmed.substring(equals + 1);
    final String cleanName = name.trim();
    final String cleanValue = value.trim();
    if (cleanName.isEmpty()) {
      throw new IllegalArgumentException("yt-dlp option name must not be empty");
    }
    arguments.add("--" + cleanName);
    arguments.add(cleanValue);
  }
}
