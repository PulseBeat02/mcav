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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.ListArbitrary;
import net.jqwik.api.arbitraries.StringArbitrary;

/**
 * Properties of {@link VideoFlagsParser}, whose input is typed by players. Pass 4 limited the yt-dlp options to the ones
 * that only pick a stream, because yt-dlp's full option set reads and writes files, runs programs and sends
 * credentials. These state what may reach the yt-dlp command line for every text a player can type: an argument that
 * starts with a dash is always an accepted option, a switch never takes the next argument, and an option with a value
 * takes exactly one argument that yt-dlp cannot read as an option.
 */
final class VideoFlagsParserPropertyTest {

  private static final String SEED = "20260925";
  private static final Set<String> SWITCHES = YtDlpArgumentAssertions.SWITCHES;

  private final VideoFlagsParser parser = new VideoFlagsParser();

  @Provide
  Arbitrary<String> typedFlags() {
    final List<String> supported = VideoFlagsParser.supportedOptions();
    final Arbitrary<String> names = Arbitraries.of(supported);
    final Arbitrary<String> dangerous = Arbitraries.of(
      "exec",
      "output",
      "config-location",
      "cookies",
      "batch-file",
      "",
      " ",
      "-o",
      "format "
    );
    // every arbitrary starts from a fresh base: jqwik 1.9 shares settings between an arbitrary and those made from it
    final StringArbitrary pieceStrings = Arbitraries.strings();
    final StringArbitrary tricky = pieceStrings.withChars("=,}{\\- abcf0\t");
    final StringArbitrary pieces = tricky.ofMaxLength(12);
    final Arbitrary<String> anyName = Arbitraries.oneOf(names, dangerous, pieces);
    final Arbitrary<String> separators = Arbitraries.of("", "=");
    final Combinators.Combinator3<String, String, String> parts = Combinators.combine(anyName, pieces, separators);
    final Arbitrary<String> option = parts.as((name, value, equals) -> name + equals + value);
    final ListArbitrary<String> options = option.list();
    final ListArbitrary<String> fewOptions = options.ofMaxSize(5);
    final Arbitrary<String> joined = fewOptions.map(list -> String.join(",", list));
    final Arbitrary<String> wrapped = joined.map(content -> "--yt-dlp{" + content + "}");
    // well-formed options of accepted names, so that what decides is the values, which a player controls most freely;
    // values often start with a dash, which yt-dlp would read as an option of its own
    final Arbitrary<String> dashed = pieces.map(piece -> "-" + piece);
    final Arbitrary<String> values = Arbitraries.oneOf(pieces, dashed);
    final Combinators.Combinator2<String, String> acceptedParts = Combinators.combine(names, values);
    final Arbitrary<String> acceptedOption = acceptedParts.as((name, value) -> SWITCHES.contains(name) ? name : name + "=" + value);
    final ListArbitrary<String> acceptedList = acceptedOption.list();
    final ListArbitrary<String> fewAccepted = acceptedList.ofMaxSize(4);
    final Arbitrary<String> acceptedJoined = fewAccepted.map(list -> String.join(",", list));
    final Arbitrary<String> acceptedWrapped = acceptedJoined.map(content -> "--yt-dlp{" + content + "}");
    final StringArbitrary textStrings = Arbitraries.strings();
    final StringArbitrary anyText = textStrings.ofMaxLength(60);
    return Arbitraries.oneOf(wrapped, acceptedWrapped, anyText);
  }

  @Property(seed = SEED)
  void onlyAcceptedOptionsAndTheirValuesReachYtDlp(@ForAll("typedFlags") final String typed) {
    final String[] arguments;
    try {
      arguments = this.parser.parseYTDLPFlags(typed);
    } catch (final IllegalArgumentException refused) {
      return;
    }
    YtDlpArgumentAssertions.assertSafe(typed, arguments);
  }

  /**
   * The regular expression the parser found the options with until the fuzzer showed it overflowing the stack on long
   * options. It is kept as the oracle for inputs short enough for it.
   */
  private static final Pattern ORIGINAL_OPTIONS = Pattern.compile(Pattern.quote("--yt-dlp") + "\\{((?:[^\\\\}]|\\\\.)*)}");

  @Provide
  Arbitrary<String> flagTexts() {
    final StringArbitrary pieceStrings = Arbitraries.strings();
    final StringArbitrary tricky = pieceStrings.withChars("{}\\,=ab- \n\r\u0085\u2028\u2029");
    final StringArbitrary pieces = tricky.ofMaxLength(12);
    final Arbitrary<String> openings = Arbitraries.of("--yt-dlp{", "--yt-dlp", "--yt-dlp{}", "\\");
    final Arbitrary<String> part = Arbitraries.oneOf(pieces, openings);
    final ListArbitrary<String> parts = part.list();
    final ListArbitrary<String> fewParts = parts.ofMaxSize(8);
    return fewParts.map(list -> String.join("", list));
  }

  @Property(seed = SEED)
  void findsTheOptionsWhereTheRegularExpressionItReplacedFoundThem(@ForAll("flagTexts") final String flags) {
    final Matcher matcher = ORIGINAL_OPTIONS.matcher(flags);
    final boolean found = matcher.find();
    final String group = found ? matcher.group(1) : null;
    final String expected = group == null || group.isBlank() ? null : group;

    final String options = VideoFlagsParser.extractOptions(flags);

    assertEquals(expected, options, () -> "options of '" + flags + "'");
  }

  @Provide
  Arbitrary<List<String[]>> validOptions() {
    final List<String> supported = VideoFlagsParser.supportedOptions();
    final Arbitrary<String> names = Arbitraries.of(supported);
    final StringArbitrary strings = Arbitraries.strings();
    final StringArbitrary valueCharacters = strings.withChars("abc01=,}{\\/. ");
    final StringArbitrary values = valueCharacters.ofMinLength(1);
    final StringArbitrary shortValues = values.ofMaxLength(10);
    // the escapes cover commas and closing braces; a backslash right before either, or at the end, has no spelling
    final Arbitrary<String> expressibleValues = shortValues.filter(value -> {
      final String trimmed = value.strip();
      final boolean escapable = !value.contains("\\,") && !value.contains("\\}") && !value.endsWith("\\");
      return !trimmed.isEmpty() && trimmed.equals(value) && escapable;
    });
    final Combinators.Combinator2<String, String> parts = Combinators.combine(names, expressibleValues);
    final Arbitrary<String[]> option = parts.as((name, value) -> {
      final boolean isSwitch = SWITCHES.contains(name);
      return isSwitch ? new String[] { name } : new String[] { name, value };
    });
    final ListArbitrary<String[]> options = option.list();
    return options.ofMaxSize(5);
  }

  /**
   * A player who escapes commas and closing braces inside values, as the command help says, gets exactly the options
   * and values they meant.
   */
  @Property(seed = SEED)
  void escapedValuesReachYtDlpUnchanged(@ForAll("validOptions") final List<String[]> options) {
    final List<String> typedOptions = new ArrayList<>();
    final List<String> expected = new ArrayList<>();
    for (final String[] option : options) {
      final String name = option[0];
      expected.add("--" + name);
      if (option.length == 1) {
        typedOptions.add(name);
        continue;
      }
      final String value = option[1];
      final String commasEscaped = value.replace(",", "\\,");
      final String escaped = commasEscaped.replace("}", "\\}");
      typedOptions.add(name + "=" + escaped);
      expected.add(value);
    }
    final String content = String.join(",", typedOptions);
    final String typed = "--yt-dlp{" + content + "}";
    final String[] expectedArguments = expected.toArray(new String[0]);

    final String[] arguments = this.parser.parseYTDLPFlags(typed);

    assertArrayEquals(expectedArguments, arguments, () -> "typed " + typed);
  }
}
