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
package me.brandonli.mcav.plugin.command.video;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

final class VideoFlagsParserTest {

  private static final String FLAG_OPENING = "--yt-dlp{";
  private static final String FLAG_CLOSING = "}";
  private static final String FORMAT_FLAG_OPENING = "--yt-dlp{format=";
  private static final String ESCAPING_FORMAT_FLAG = "--yt-dlp{format=best\\";

  private static final String FORMAT_OPTION = "--format";
  private static final String BEST_FORMAT = "best";
  private static final String NO_PLAYLIST_OPTION = "--no-playlist";
  private static final String MATCH_FILTER_OPTION = "--match-filter";

  private final VideoFlagsParser parser = new VideoFlagsParser();

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = { "", "   ", "--loop", "--yt-dlp", "--yt-dlp{format=best", "--yt-dlp{format=best\\}", "yt-dlp{format=best}" })
  void shouldReturnNoArgumentsWithoutYtdlpOptions(final String flags) {
    final String[] arguments = this.parser.parseYtdlpFlags(flags);
    assertArrayEquals(new String[0], arguments);
  }

  @ParameterizedTest
  @ValueSource(ints = { 0x0A, 0x0D, 0x85, 0x2028, 0x2029 })
  void shouldNotLetABackslashEscapeTheEndOfALine(final int lineBreak) {
    final String character = Character.toString(lineBreak);
    final String[] arguments = this.parser.parseYtdlpFlags(ESCAPING_FORMAT_FLAG + character + FLAG_CLOSING);
    assertArrayEquals(new String[0], arguments);
  }

  @Test
  void shouldNeverEndOptionsThatEndInABackslash() {
    final String[] arguments = this.parser.parseYtdlpFlags(ESCAPING_FORMAT_FLAG);
    assertArrayEquals(new String[0], arguments);
  }

  @Test
  void shouldLookForTheOptionsAgainAfterOptionsThatNeverEnd() {
    final String[] arguments = this.parser.parseYtdlpFlags("--yt-dlp{format=worst\\\n --yt-dlp{format=best}");
    final String[] expected = { FORMAT_OPTION, BEST_FORMAT };
    assertArrayEquals(expected, arguments);
  }

  @ParameterizedTest
  @ValueSource(strings = { "--yt-dlp{}", "--yt-dlp{   }" })
  void shouldReturnNoArgumentsForEmptyOptions(final String flags) {
    final String[] arguments = this.parser.parseYtdlpFlags(flags);
    assertArrayEquals(new String[0], arguments);
  }

  @ParameterizedTest
  @ValueSource(strings = { "--yt-dlp{=best}", "--yt-dlp{  = best}", "--yt-dlp{format=best,=value}" })
  void shouldRejectEmptyOptionNames(final String flags) {
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> this.parser.parseYtdlpFlags(flags));

    final String message = exception.getMessage();
    assertEquals("yt-dlp option name must not be empty!", message);
  }

  @Test
  void shouldTurnEveryOptionIntoArguments() {
    final String[] arguments = this.parser.parseYtdlpFlags("--yt-dlp{format=bestvideo,no-playlist}");
    final String[] expected = { FORMAT_OPTION, "bestvideo", NO_PLAYLIST_OPTION };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void shouldKeepEscapedCommasInsideValues() {
    final String[] arguments = this.parser.parseYtdlpFlags("--yt-dlp{format=bv\\,ba,no-playlist}");
    final String[] expected = { FORMAT_OPTION, "bv,ba", NO_PLAYLIST_OPTION };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void shouldTrimNamesAndValues() {
    final String[] arguments = this.parser.parseYtdlpFlags("--yt-dlp{ format = best ,  no-playlist }");
    final String[] expected = { FORMAT_OPTION, BEST_FORMAT, NO_PLAYLIST_OPTION };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void shouldSkipEmptyOptions() {
    final String[] arguments = this.parser.parseYtdlpFlags("--yt-dlp{no-playlist,, ,geo-bypass}");
    final String[] expected = { NO_PLAYLIST_OPTION, "--geo-bypass" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void shouldSkipSeparatorsAtTheStartAndTheEnd() {
    final String[] arguments = this.parser.parseYtdlpFlags("--yt-dlp{,format=best,no-playlist,}");
    final String[] expected = { FORMAT_OPTION, BEST_FORMAT, NO_PLAYLIST_OPTION };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void shouldKeepAnEscapedCommaAtTheEndOfAValue() {
    final String[] arguments = this.parser.parseYtdlpFlags("--yt-dlp{format=bv\\,}");
    final String[] expected = { FORMAT_OPTION, "bv," };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void shouldSplitOnlyAtTheFirstEqualsSign() {
    final String[] arguments = this.parser.parseYtdlpFlags("--yt-dlp{add-header=Referer=https://example.com}");
    final String[] expected = { "--add-header", "Referer=https://example.com" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void shouldKeepAnEmptyValue() {
    final String[] arguments = this.parser.parseYtdlpFlags("--yt-dlp{format=}");
    final String[] expected = { FORMAT_OPTION, "" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void shouldFindTheOptionsBetweenOtherFlags() {
    final String[] arguments = this.parser.parseYtdlpFlags("--loop --yt-dlp{format=worst} --muted");
    final String[] expected = { FORMAT_OPTION, "worst" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void shouldKeepEscapedClosingBracesInsideValues() {
    final String[] arguments = this.parser.parseYtdlpFlags("--yt-dlp{match-filter=title~='{a\\}',no-playlist} --muted");
    final String[] expected = { MATCH_FILTER_OPTION, "title~='{a}'", NO_PLAYLIST_OPTION };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void shouldKeepOtherBackslashesAsTheyAre() {
    final String[] arguments = this.parser.parseYtdlpFlags("--yt-dlp{match-filter=title~=C:\\videos\\%(title)s.mp4}");
    final String[] expected = { MATCH_FILTER_OPTION, "title~=C:\\videos\\%(title)s.mp4" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void shouldAcceptEverySupportedOption() {
    final List<String> supported = VideoFlagsParser.supportedOptions();
    for (final String option : supported) {
      final String[] withValue = this.parseOrEmpty("%s=value".formatted(option));
      final String[] asSwitch = this.parseOrEmpty(option);
      final boolean takesValue = Arrays.equals(new String[] { "--%s".formatted(option), "value" }, withValue);
      final boolean isSwitch = Arrays.equals(new String[] { "--%s".formatted(option) }, asSwitch);
      assertTrue(takesValue != isSwitch, "%s must be either a switch or an option with a value".formatted(option));
    }
  }

  private String[] parseOrEmpty(final String option) {
    try {
      return this.parser.parseYtdlpFlags(FLAG_OPENING + option + FLAG_CLOSING);
    } catch (final IllegalArgumentException exception) {
      return new String[0];
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "exec", "output", "paths", "config-location", "cookies", "downloader", "load-info-json" })
  void shouldRefuseOptionsOutsideTheSupportedList(final String option) {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      this.parser.parseYtdlpFlags("%s%s=value}".formatted(FLAG_OPENING, option))
    );

    final String message = refused.getMessage();
    final boolean namesTheOption = message.startsWith("yt-dlp option %s is not supported, ".formatted(option));
    assertTrue(namesTheOption, message);
    final IllegalArgumentException asSwitch = assertThrows(IllegalArgumentException.class, () ->
      this.parser.parseYtdlpFlags(FLAG_OPENING + option + FLAG_CLOSING)
    );

    final String switchMessage = asSwitch.getMessage();

    final boolean prefixed = switchMessage.startsWith("yt-dlp option %s is not supported, ".formatted(option));
    assertTrue(prefixed, switchMessage);
  }

  @Test
  void shouldRefuseAValueForASwitch() {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      this.parser.parseYtdlpFlags("--yt-dlp{no-playlist=https://example.com/other}")
    );

    final String message = refused.getMessage();
    assertEquals("yt-dlp option no-playlist takes no value!", message);
  }

  @Test
  void shouldRefuseASwitchThatNeedsAValue() {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      this.parser.parseYtdlpFlags("--yt-dlp{format}")
    );

    final String message = refused.getMessage();
    assertEquals("yt-dlp option format needs a value, written as format=value!", message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "-x", "--exec", "-" })
  void shouldRefuseValuesThatYtdlpWouldReadAsAnotherOption(final String value) {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      this.parser.parseYtdlpFlags(FORMAT_FLAG_OPENING + value + FLAG_CLOSING)
    );

    final String message = refused.getMessage();
    final String expected = "Value %s of the yt-dlp option format must not start with a dash!".formatted(value);
    assertEquals(expected, message);
  }

  @Test
  void shouldListExactlyTheDocumentedOptionsInAlphabeticalOrder() {
    final List<String> documented = new ArrayList<>(YtdlpArgumentAssertions.SWITCHES);
    documented.addAll(YtdlpArgumentAssertions.VALUE_OPTIONS);
    Collections.sort(documented);

    final List<String> supported = VideoFlagsParser.supportedOptions();
    assertEquals(documented, supported);

    final boolean runsAProgram = supported.contains("exec");
    assertFalse(runsAProgram, "no option may run a program of the server");

    final boolean writesAFile = supported.contains("output");
    assertFalse(writesAFile, "no option may write a file of the server");
  }

  @Test
  void shouldReturnTheSupportedOptionsAsAnUnmodifiableList() {
    final List<String> supported = VideoFlagsParser.supportedOptions();
    assertThrows(UnsupportedOperationException.class, supported::clear);
  }

  @Test
  void shouldEndTheOptionsAtTheFirstUnescapedClosingBrace() {
    final String[] arguments = this.parser.parseYtdlpFlags("--yt-dlp{format=best}extra}");
    final String[] expected = { FORMAT_OPTION, BEST_FORMAT };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void shouldFindOptionsOfAnyLengthWithoutOverflowingTheStack() {
    final String value = "b".repeat(100_000);
    final String escaped = "b\\,".repeat(20_000);

    final String[] arguments = this.parser.parseYtdlpFlags(FORMAT_FLAG_OPENING + value + FLAG_CLOSING);
    final String[] escapedArguments = this.parser.parseYtdlpFlags(FORMAT_FLAG_OPENING + escaped + FLAG_CLOSING);

    final String[] expected = { FORMAT_OPTION, value };
    final String[] expectedEscaped = { FORMAT_OPTION, "b,".repeat(20_000) };
    assertArrayEquals(expected, arguments);
    assertArrayEquals(expectedEscaped, escapedArguments);
  }
}
