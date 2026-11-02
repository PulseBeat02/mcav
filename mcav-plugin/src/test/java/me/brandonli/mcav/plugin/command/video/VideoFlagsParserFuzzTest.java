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

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class VideoFlagsParserFuzzTest {

  private final VideoFlagsParser parser = new VideoFlagsParser();

  @FuzzTest(maxDuration = "30s")
  void shouldPassOnlyAcceptedOptionsAndTheirValuesToYtdlp(final byte[] input) {
    final String typed = new String(input, StandardCharsets.UTF_8);
    final String[] arguments;
    try {
      arguments = this.parser.parseYtdlpFlags(typed);
    } catch (final IllegalArgumentException exception) {
      return;
    }
    YtdlpArgumentAssertions.assertSafe(typed, arguments);
  }
}
