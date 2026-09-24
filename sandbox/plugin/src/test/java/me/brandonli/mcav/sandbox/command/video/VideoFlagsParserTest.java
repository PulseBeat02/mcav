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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{a,, ,b}");
    final String[] expected = { "--a", "--b" };
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
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{output=}");
    final String[] expected = { "--output", "" };
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
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{output=C:\\videos\\%(title)s.mp4}");
    final String[] expected = { "--output", "C:\\videos\\%(title)s.mp4" };
    assertArrayEquals(expected, arguments);
  }

  @Test
  void endsTheOptionsAtTheFirstUnescapedClosingBrace() {
    final String[] arguments = this.parser.parseYTDLPFlags("--yt-dlp{format=best}extra}");
    final String[] expected = { "--format", "best" };
    assertArrayEquals(expected, arguments);
  }
}
