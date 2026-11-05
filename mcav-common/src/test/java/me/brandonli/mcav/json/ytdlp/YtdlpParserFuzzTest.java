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
package me.brandonli.mcav.json.ytdlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.runtime.CommandTask;
import org.junit.jupiter.api.Tag;

// Fuzzes what yt-dlp prints about a page, whose metadata comes from the site hosting the video, titles, formats and URLs included.
@Tag("fuzz")
final class YtdlpParserFuzzTest {

  private static final Path EXECUTABLE = Path.of("yt-dlp");
  private static final UriSource PAGE = UriSource.uri(URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
  private static final List<Strategy> STRATEGIES = List.of(
    new Strategy(FormatStrategy.LOWEST_QUALITY_AUDIO, FormatStrategy::hasAudio),
    new Strategy(FormatStrategy.LOWEST_QUALITY_VIDEO, FormatStrategy::hasVideo),
    new Strategy(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy::hasAudio),
    new Strategy(FormatStrategy.BEST_QUALITY_VIDEO, FormatStrategy::hasVideo),
    new Strategy(FormatStrategy.FIRST_AUDIO, FormatStrategy::hasAudio),
    new Strategy(FormatStrategy.FIRST_VIDEO, FormatStrategy::hasVideo),
    new Strategy(
      FormatStrategy.PREFER_WEBM_AUDIO,
      format -> FormatStrategy.isHttps(format) && format.getAudioExtension().equals(Optional.of("webm"))
    ),
    new Strategy(
      FormatStrategy.PREFER_MP4_VIDEO,
      format -> FormatStrategy.isHttps(format) && format.getVideoExtension().equals(Optional.of("mp4"))
    )
  );

  @FuzzTest(maxDuration = "30s")
  void shouldReadWhatYtdlpPrintsOrRefuseIt(final byte[] input) throws IOException {
    if (input.length == 0) {
      return;
    }

    final int exitCode = input[0];
    final String output = new String(input, 1, input.length - 1, StandardCharsets.UTF_8);
    // a new mock for every input, which keeps no record of its calls, so nothing piles up while the fuzzer runs
    final CommandTask task = mock(CommandTask.class, withSettings().stubOnly());
    when(task.run(any(Duration.class))).thenReturn(exitCode);
    when(task.getOutput()).thenReturn(output);
    when(task.getErrorOutput()).thenReturn("");
    final ExecutableYtdlpParser parser = new ExecutableYtdlpParser(() -> EXECUTABLE, arguments -> task);

    final UrlParseDump dump;
    try {
      dump = parser.parse(PAGE);
    } catch (final YtdlpParseException exception) {
      return;
    }

    assertEquals(0, exitCode, "only metadata of a successful run is read");

    for (final Strategy strategy : STRATEGIES) {
      final Optional<Format> picked = strategy.strategy().select(dump);
      if (picked.isEmpty()) {
        continue;
      }
      final Format format = picked.get();
      final List<Format> formats = dump.getFormats();
      final boolean listed = formats.contains(format);
      assertTrue(listed, "a strategy picks a stream of the metadata");
      final boolean accepted = strategy.accepts().test(format);
      assertTrue(accepted, "a strategy picks only a stream it accepts");
      try {
        format.toUriSource();
      } catch (final IllegalStateException | IllegalArgumentException exception) {
        // a stream without a URL, or with one that is no URI, as documented
      }
    }
  }

  private record Strategy(FormatStrategy strategy, Predicate<Format> accepts) {}
}
