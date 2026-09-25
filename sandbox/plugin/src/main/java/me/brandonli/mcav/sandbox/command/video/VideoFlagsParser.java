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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Parses the optional flags of the video commands. yt-dlp options are written as
 * {@code --yt-dlp{format=bestvideo,no-playlist}}, where a comma inside a value is escaped as {@code \,} and a
 * closing brace as <code>\}</code>; every option becomes {@code --name} followed by its value as a separate
 * argument. Other backslashes are kept as they are.
 *
 * <p>Only the options of {@link #supportedOptions()} are accepted, because the options of yt-dlp also cover reading
 * and writing files of the server, running programs of its own and sending credentials, none of which belongs in a
 * chat command. Every accepted option only selects which stream of a page is played. An option outside the list, an
 * option given a value it does not take, one missing the value it needs, or a value that yt-dlp would read as
 * another option is refused, and the command tells the sender instead of running yt-dlp.
 */
public final class VideoFlagsParser {

  private static final String YT_DLP_OPENING = "--yt-dlp{";
  private static final char ESCAPE = '\\';
  private static final char CLOSING_BRACE = '}';
  private static final Pattern UNESCAPED_COMMA = Pattern.compile("(?<!\\\\),");
  // keeps empty options, including a trailing one, which appendOption skips
  private static final Splitter OPTION_SPLITTER = Splitter.on(UNESCAPED_COMMA);

  /**
   * The options that are a switch, so they must not be given a value.
   */
  private static final Set<String> FLAG_OPTIONS = Set.of(
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

  /**
   * The options that need a value, which is passed to yt-dlp as the argument after the option.
   */
  private static final Set<String> VALUE_OPTIONS = Set.of(
    "format",
    "format-sort",
    "playlist-items",
    "match-filter",
    "break-match-filters",
    "geo-bypass-country",
    "geo-bypass-ip-block",
    "retries",
    "extractor-retries",
    "socket-timeout",
    "source-address",
    "add-header",
    "user-agent",
    "referer"
  );

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
   * Finds the options inside {@code --yt-dlp{...}}: the first <code>--yt-dlp{</code> whose options end with a closing
   * brace that is not escaped. A backslash escapes the character after it, except the end of a line. Visible for
   * testing.
   *
   * <p>The options are found by scanning rather than with a regular expression: the expression that did this recursed
   * once per character, so options of about 1600 characters overflowed the stack of a thread of the default size.
   *
   * @return the options, or {@code null} if the flags contain no yt-dlp options
   */
  @VisibleForTesting
  static @Nullable String extractOptions(final @Nullable String flags) {
    if (flags == null || flags.isBlank()) {
      return null;
    }

    int opening = flags.indexOf(YT_DLP_OPENING);
    while (opening >= 0) {
      final int start = opening + YT_DLP_OPENING.length();
      final int end = findClosingBrace(flags, start);
      if (end >= 0) {
        final String content = flags.substring(start, end);
        return content.isBlank() ? null : content;
      }
      opening = flags.indexOf(YT_DLP_OPENING, opening + 1);
    }
    return null;
  }

  /**
   * Finds the first closing brace from an index on that is not escaped.
   *
   * @return the index of the brace, or -1 if the options never end, including when a backslash has nothing after it
   * but the end of a line or of the text
   */
  private static int findClosingBrace(final String text, final int from) {
    final int length = text.length();
    int index = from;
    while (index < length) {
      final char current = text.charAt(index);
      if (current == CLOSING_BRACE) {
        return index;
      }
      if (current != ESCAPE) {
        index++;
        continue;
      }
      final int escaped = index + 1;
      final boolean escapes = escaped < length && !isLineTerminator(text.charAt(escaped));
      if (!escapes) {
        return -1;
      }
      index = escaped + 1;
    }
    return -1;
  }

  // the characters that end a line, which a backslash never escapes
  private static boolean isLineTerminator(final char character) {
    return character == '\n' || character == '\r' || character == '\u0085' || character == '\u2028' || character == '\u2029';
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
      requireFlagOption(trimmed);
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
    requireValueOption(cleanName, cleanValue);
    arguments.add("--" + cleanName);
    arguments.add(cleanValue);
  }

  private static void requireFlagOption(final String name) {
    final boolean flag = FLAG_OPTIONS.contains(name);
    if (flag) {
      return;
    }
    final boolean needsValue = VALUE_OPTIONS.contains(name);
    if (needsValue) {
      throw new IllegalArgumentException("The yt-dlp option " + name + " needs a value, written as " + name + "=value");
    }
    throw unsupported(name);
  }

  private static void requireValueOption(final String name, final String value) {
    final boolean takesValue = VALUE_OPTIONS.contains(name);
    if (!takesValue) {
      final boolean flag = FLAG_OPTIONS.contains(name);
      if (flag) {
        throw new IllegalArgumentException("The yt-dlp option " + name + " takes no value");
      }
      throw unsupported(name);
    }
    // yt-dlp reads an argument that starts with a dash as an option of its own, whatever option it follows
    final boolean readAsOption = value.startsWith("-");
    if (readAsOption) {
      throw new IllegalArgumentException("The value of the yt-dlp option " + name + " must not start with a dash: " + value);
    }
  }

  private static IllegalArgumentException unsupported(final String name) {
    final String supported = String.join(", ", supportedOptions());
    return new IllegalArgumentException("Unsupported yt-dlp option " + name + "; the video commands accept " + supported);
  }

  /**
   * Gets the names of the yt-dlp options the video commands accept, in alphabetical order. A switch is written as
   * {@code name} and an option with a value as {@code name=value}.
   *
   * @return the option names, as an unmodifiable list
   */
  public static List<String> supportedOptions() {
    final List<String> names = new ArrayList<>(FLAG_OPTIONS);
    names.addAll(VALUE_OPTIONS);
    Collections.sort(names);
    return Collections.unmodifiableList(names);
  }
}
