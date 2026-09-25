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

/**
 * Tests {@link VideoFlagsParser}.
 */
final class VideoFlagsParserTest {

  private final VideoFlagsParser parser = new VideoFlagsParser();

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = { "", "   ", "--loop", "--yt-dlp", "--yt-dlp{format=best", "--yt-dlp{format=best\\}", "yt-dlp{format=best}" })
  void returnsNoArgumentsWithoutYtdlpOptions(final String flags) {
    final String[] arguments = this.parser.parseYTDLPFlags(flags);
    assertArrayEquals(new String[0], arguments);
  }

  /**
   * A backslash escapes the character after it, but not the end of a line, like the regular expression the scan of
   * the options replaced: options whose closing brace follows a backslash and a line break never end there.
   */
  @ParameterizedTest
  @ValueSource(ints = { 0x0A, 0x0D, 0x85, 0x2028, 0x2029 })
  void aBackslashDoesNotEscapeTheEndOfALine(final int lineBreak) {
    final String character = Character.toString(lineBreak);
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{format=best\\" + character + "}");
    assertArrayEquals(new String[0], arguments);
  }

  @Test
  void optionsThatEndInABackslashNeverEnd() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{format=best\\");
    assertArrayEquals(new String[0], arguments);
  }

  @Test
  void looksForTheOptionsAgainAfterOptionsThatNeverEnd() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{format=worst\\\n --yt-dlp{format=best}");
    final String[] expected = { "--format", "best" };
    assertArrayEquals(expected, arguments);
  }

  @ParameterizedTest
  @ValueSource(strings = { "--yt-dlp{}", "--yt-dlp{   }" })
  void returnsNoArgumentsForEmptyOptions(final String flags) {
    final String[] arguments = this.parser.parseYTDLPFlags(flags);
    assertArrayEquals(new String[0], arguments);
  }

  @ParameterizedTest
  @ValueSource(strings = { "--yt-dlp{=best}", "--yt-dlp{  = best}", "--yt-dlp{format=best,=value}" })
  void rejectsEmptyOptionNames(final String flags) {
    assertThrows(IllegalArgumentException.class, () -> this.parser.parseYTDLPFlags(flags));
  }

  @Test
  void turnsEveryOptionIntoArguments() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{format=bestvideo,no-playlist}");
    final String[] expected = { "--format", "bestvideo", "--no-playlist" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void keepsEscapedCommasInsideValues() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{format=bv\\,ba,no-playlist}");
    final String[] expected = { "--format", "bv,ba", "--no-playlist" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void trimsNamesAndValues() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{ format = best ,  no-playlist }");
    final String[] expected = { "--format", "best", "--no-playlist" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void skipsEmptyOptions() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{no-playlist,, ,geo-bypass}");
    final String[] expected = { "--no-playlist", "--geo-bypass" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void skipsSeparatorsAtTheStartAndTheEnd() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{,format=best,no-playlist,}");
    final String[] expected = { "--format", "best", "--no-playlist" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void keepsAnEscapedCommaAtTheEndOfAValue() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{format=bv\\,}");
    final String[] expected = { "--format", "bv," };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void splitsOnlyAtTheFirstEqualsSign() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{add-header=Referer=https://example.com}");
    final String[] expected = { "--add-header", "Referer=https://example.com" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void keepsAnEmptyValue() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{format=}");
    final String[] expected = { "--format", "" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void findsTheOptionsBetweenOtherFlags() {
    final String[] arguments = this.parser.parseYTDLPFlags("--loop --yt-dlp{format=worst} --muted");
    final String[] expected = { "--format", "worst" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void keepsEscapedClosingBracesInsideValues() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{match-filter=title~='{a\\}',no-playlist} --muted");
    final String[] expected = { "--match-filter", "title~='{a}'", "--no-playlist" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void keepsOtherBackslashesAsTheyAre() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{match-filter=title~=C:\\videos\\%(title)s.mp4}");
    final String[] expected = { "--match-filter", "title~=C:\\videos\\%(title)s.mp4" };
    assertArrayEquals(expected, arguments);
  }

  /**
   * Parses one option, returning no arguments when the parser refuses it.
   */
  private String[] parseOrEmpty(final String option) {
    try {
      return this.parser.parseYTDLPFlags("--yt-dlp{" + option + "}");
    } catch (final IllegalArgumentException refused) {
      return new String[0];
    }
  }

  @Test
  void acceptsEverySupportedOption() {
    final List<String> supported = VideoFlagsParser.supportedOptions();
    for (final String option : supported) {
      final String[] withValue = this.parseOrEmpty(option + "=value");
      final String[] asSwitch = this.parseOrEmpty(option);
      final boolean takesValue = Arrays.equals(new String[] { "--" + option, "value" }, withValue);
      final boolean isSwitch = Arrays.equals(new String[] { "--" + option }, asSwitch);
      assertTrue(takesValue != isSwitch, option + " must be either a switch or an option with a value");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "exec", "output", "paths", "config-location", "cookies", "downloader", "load-info-json" })
  void refusesOptionsOutsideTheSupportedList(final String option) {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      this.parser.parseYTDLPFlags("--yt-dlp{" + option + "=value}")
    );
    final String message = refused.getMessage();
    final boolean namesTheOption = message.startsWith("Unsupported yt-dlp option " + option);
    assertTrue(namesTheOption, message);
    final IllegalArgumentException asSwitch = assertThrows(IllegalArgumentException.class, () ->
      this.parser.parseYTDLPFlags("--yt-dlp{" + option + "}")
    );
    final String switchMessage = asSwitch.getMessage();
    assertTrue(switchMessage.startsWith("Unsupported yt-dlp option " + option), switchMessage);
  }

  @Test
  void refusesAValueForASwitch() {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      this.parser.parseYTDLPFlags("--yt-dlp{no-playlist=https://example.com/other}")
    );
    final String message = refused.getMessage();
    assertEquals("The yt-dlp option no-playlist takes no value", message);
  }

  @Test
  void refusesASwitchThatNeedsAValue() {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      this.parser.parseYTDLPFlags("--yt-dlp{format}")
    );
    final String message = refused.getMessage();
    assertEquals("The yt-dlp option format needs a value, written as format=value", message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "-x", "--exec", "-" })
  void refusesValuesThatYtdlpWouldReadAsAnotherOption(final String value) {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      this.parser.parseYTDLPFlags("--yt-dlp{format=" + value + "}")
    );
    final String message = refused.getMessage();
    final String expected = "The value of the yt-dlp option format must not start with a dash: " + value;
    assertEquals(expected, message);
  }

  @Test
  void listsTheSupportedOptionsInAlphabeticalOrder() {
    final List<String> supported = VideoFlagsParser.supportedOptions();
    final List<String> sorted = new ArrayList<>(supported);
    Collections.sort(sorted);
    assertEquals(sorted, supported);
    assertTrue(supported.contains("format"), "format selection is the reason the flags exist");
    assertTrue(supported.contains("no-playlist"));
    assertFalse(supported.contains("exec"), "no option may run a program of the server");
    assertFalse(supported.contains("output"), "no option may write a file of the server");
  }

  @Test
  void endsTheOptionsAtTheFirstUnescapedClosingBrace() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{format=best}extra}");
    final String[] expected = { "--format", "best" };
    assertArrayEquals(expected, arguments);
  }

  /**
   * Found by {@code VideoFlagsParserFuzzTest}: the options used to be found with a regular expression whose repeated
   * alternative recursed once per character, so options of about 1600 characters overflowed the stack of a thread
   * with the default size of 1 MB and threw a {@link StackOverflowError} out of the parser. The fuzzer's input was
   * 2072 bytes of options; any long value shows it.
   */
  @Test
  void findsOptionsOfAnyLengthWithoutOverflowingTheStack() {
    final String value = "b".repeat(100_000);
    final String escaped = "b\\,".repeat(20_000);

    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{format=" + value + "}");
    final String[] escapedArguments = this.parser.parseYTDLPFlags("--yt-dlp{format=" + escaped + "}");

    final String[] expected = { "--format", value };
    final String[] expectedEscaped = { "--format", "b,".repeat(20_000) };
    assertArrayEquals(expected, arguments);
    assertArrayEquals(expectedEscaped, escapedArguments);
  }
}
