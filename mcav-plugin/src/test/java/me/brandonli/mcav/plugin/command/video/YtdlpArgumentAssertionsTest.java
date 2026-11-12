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

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class YtdlpArgumentAssertionsTest {

  private static final String TYPED_FLAGS = "--yt-dlp{no-playlist,format=best}";

  private static final String FORMAT_OPTION = "--format";
  private static final String BEST_FORMAT = "best";

  @Test
  void shouldAcceptSwitchesAndOptionsWithTheirValues() {
    final String[] arguments = { "--no-playlist", FORMAT_OPTION, BEST_FORMAT };
    YtdlpArgumentAssertions.assertSafe(TYPED_FLAGS, arguments);
  }

  @ParameterizedTest
  @MethodSource("unsafeArguments")
  void shouldFailForArgumentsThatYtdlpMustNotReceive(final String[] arguments) {
    assertThrows(AssertionError.class, () -> YtdlpArgumentAssertions.assertSafe(TYPED_FLAGS, arguments));
  }

  static Stream<Arguments> unsafeArguments() {
    return Stream.of(
      Arguments.argumentSet("a value where an option belongs", (Object) new String[] { BEST_FORMAT }),
      Arguments.argumentSet("an option that is not accepted", (Object) new String[] { "--exec", "program" }),
      Arguments.argumentSet("an option that lost its value", (Object) new String[] { FORMAT_OPTION }),
      Arguments.argumentSet("a value that yt-dlp reads as an option", (Object) new String[] { FORMAT_OPTION, "-x" })
    );
  }
}
