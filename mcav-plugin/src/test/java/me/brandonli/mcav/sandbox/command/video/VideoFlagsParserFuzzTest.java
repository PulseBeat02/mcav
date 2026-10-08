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

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the flags of the video commands, which players type: whatever the text, the parser either refuses it with an
 * {@link IllegalArgumentException} the command reports, or hands yt-dlp nothing but accepted options and their values.
 * The seeds are the flag strings the tests of the parser use.
 */
@Tag("fuzz")
final class VideoFlagsParserFuzzTest {

  private final VideoFlagsParser parser = new VideoFlagsParser();

  @FuzzTest(maxDuration = "30s")
  void onlyAcceptedOptionsAndTheirValuesReachYtDlp(final byte[] input) {
    final String typed = new String(input, StandardCharsets.UTF_8);
    final String[] arguments;
    try {
      arguments = this.parser.parseYTDLPFlags(typed);
    } catch (final IllegalArgumentException refused) {
      return;
    }
    YtDlpArgumentAssertions.assertSafe(typed, arguments);
  }
}
