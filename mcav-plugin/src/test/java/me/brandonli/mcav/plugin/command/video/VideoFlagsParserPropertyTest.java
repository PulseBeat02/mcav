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

final class VideoFlagsParserPropertyTest {

  private static final String SEED = "20260925";
  private static final Set<String> SWITCHES = YtdlpArgumentAssertions.SWITCHES;

  private static final int MAXIMUM_PIECE_LENGTH = 12;
  private static final int MAXIMUM_OPTIONS = 5;

  private static final String FLAG_OPENING = "--yt-dlp{";
  private static final String FLAG_CLOSING = "}";
  private static final String OPTION_SEPARATOR = ",";
  private static final String VALUE_SEPARATOR = "=";
  private static final String ESCAPE = "\\";
  private static final String ESCAPED_SEPARATOR = "\\,";
  private static final String ESCAPED_CLOSING = "\\}";

  // the parser's old regex, which overflowed the stack on long options; kept as the oracle for short inputs
  private static final Pattern ORIGINAL_OPTIONS = Pattern.compile("%s\\{((?:[^\\\\}]|\\\\.)*)}".formatted(Pattern.quote("--yt-dlp")));

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
    // every arbitrary starts from a fresh base: jqwik 1.10 shares settings between an arbitrary and those made from it
    final StringArbitrary pieceStrings = Arbitraries.strings();
    final StringArbitrary tricky = pieceStrings.withChars("=,}{\\- abcf0\t");
    final StringArbitrary pieces = tricky.ofMaxLength(MAXIMUM_PIECE_LENGTH);
    final Arbitrary<String> anyName = Arbitraries.oneOf(names, dangerous, pieces);
    final Arbitrary<String> separators = Arbitraries.of("", VALUE_SEPARATOR);
    final Combinators.Combinator3<String, String, String> parts = Combinators.combine(anyName, pieces, separators);
    final Arbitrary<String> option = parts.as((name, value, equals) -> name + equals + value);
    final ListArbitrary<String> options = option.list();
    final ListArbitrary<String> fewOptions = options.ofMaxSize(MAXIMUM_OPTIONS);
    final Arbitrary<String> joined = fewOptions.map(list -> String.join(OPTION_SEPARATOR, list));
    final Arbitrary<String> wrapped = joined.map(content -> FLAG_OPENING + content + FLAG_CLOSING);
    final Arbitrary<String> dashed = pieces.map(piece -> "-%s".formatted(piece));
    final Arbitrary<String> values = Arbitraries.oneOf(pieces, dashed);
    final Combinators.Combinator2<String, String> acceptedParts = Combinators.combine(names, values);
    final Arbitrary<String> acceptedOption = acceptedParts.as(this::toOption);
    final ListArbitrary<String> acceptedList = acceptedOption.list();
    final ListArbitrary<String> fewAccepted = acceptedList.ofMaxSize(4);
    final Arbitrary<String> acceptedJoined = fewAccepted.map(list -> String.join(OPTION_SEPARATOR, list));
    final Arbitrary<String> acceptedWrapped = acceptedJoined.map(content -> FLAG_OPENING + content + FLAG_CLOSING);
    final StringArbitrary textStrings = Arbitraries.strings();
    final StringArbitrary anyText = textStrings.ofMaxLength(60);
    return Arbitraries.oneOf(wrapped, acceptedWrapped, anyText);
  }

  private String toOption(final String name, final String value) {
    return SWITCHES.contains(name) ? name : name + VALUE_SEPARATOR + value;
  }

  @Property(seed = SEED)
  void shouldPassOnlyAcceptedOptionsAndTheirValuesToYtdlp(@ForAll("typedFlags") final String typed) {
    final String[] arguments;
    try {
      arguments = this.parser.parseYtdlpFlags(typed);
    } catch (final IllegalArgumentException exception) {
      return;
    }
    YtdlpArgumentAssertions.assertSafe(typed, arguments);
  }

  @Provide
  Arbitrary<String> flagTexts() {
    final StringArbitrary pieceStrings = Arbitraries.strings();
    final StringArbitrary tricky = pieceStrings.withChars("{}\\,=ab- \n\r\u0085\u2028\u2029");
    final StringArbitrary pieces = tricky.ofMaxLength(MAXIMUM_PIECE_LENGTH);
    final Arbitrary<String> openings = Arbitraries.of(FLAG_OPENING, "--yt-dlp", "--yt-dlp{}", ESCAPE);
    final Arbitrary<String> part = Arbitraries.oneOf(pieces, openings);
    final ListArbitrary<String> parts = part.list();
    final ListArbitrary<String> fewParts = parts.ofMaxSize(8);
    return fewParts.map(list -> String.join("", list));
  }

  @Property(seed = SEED)
  void shouldFindTheOptionsWhereTheRegularExpressionItReplacedFoundThem(@ForAll("flagTexts") final String flags) {
    final Matcher matcher = ORIGINAL_OPTIONS.matcher(flags);
    final boolean found = matcher.find();
    final String group = found ? matcher.group(1) : null;
    final String expected = group == null || group.isBlank() ? null : group;

    final String options = this.parser.extractOptions(flags);

    assertEquals(expected, options, () -> "options of '%s'".formatted(flags));
  }

  @Provide
  Arbitrary<List<String[]>> validOptions() {
    final List<String> supported = VideoFlagsParser.supportedOptions();
    final Arbitrary<String> names = Arbitraries.of(supported);
    final StringArbitrary strings = Arbitraries.strings();
    final StringArbitrary valueCharacters = strings.withChars("abc01=,}{\\/. ");
    final StringArbitrary values = valueCharacters.ofMinLength(1);
    final StringArbitrary shortValues = values.ofMaxLength(10);
    final Arbitrary<String> expressibleValues = shortValues.filter(this::isExpressible);
    final Combinators.Combinator2<String, String> parts = Combinators.combine(names, expressibleValues);
    final Arbitrary<String[]> option = parts.as(this::toArguments);
    final ListArbitrary<String[]> options = option.list();
    return options.ofMaxSize(MAXIMUM_OPTIONS);
  }

  private boolean isExpressible(final String value) {
    final String trimmed = value.strip();
    final boolean escapable = !value.contains(ESCAPED_SEPARATOR) && !value.contains(ESCAPED_CLOSING) && !value.endsWith(ESCAPE);
    return !trimmed.isEmpty() && trimmed.equals(value) && escapable;
  }

  private String[] toArguments(final String name, final String value) {
    final boolean isSwitch = SWITCHES.contains(name);
    return isSwitch ? new String[] { name } : new String[] { name, value };
  }

  @Property(seed = SEED)
  void shouldPassEscapedValuesToYtdlpUnchanged(@ForAll("validOptions") final List<String[]> options) {
    final List<String> typedOptions = new ArrayList<>();
    final List<String> expected = new ArrayList<>();
    for (final String[] option : options) {
      final String name = option[0];
      expected.add("--%s".formatted(name));

      if (option.length == 1) {
        typedOptions.add(name);
        continue;
      }
      final String value = option[1];
      final String commasEscaped = value.replace(OPTION_SEPARATOR, ESCAPED_SEPARATOR);
      final String escaped = commasEscaped.replace(FLAG_CLOSING, ESCAPED_CLOSING);
      typedOptions.add(name + VALUE_SEPARATOR + escaped);
      expected.add(value);
    }
    final String content = String.join(OPTION_SEPARATOR, typedOptions);
    final String typed = FLAG_OPENING + content + FLAG_CLOSING;
    final String[] expectedArguments = expected.toArray(new String[0]);

    final String[] arguments = this.parser.parseYtdlpFlags(typed);

    assertArrayEquals(expectedArguments, arguments, () -> "typed %s".formatted(typed));
  }
}
