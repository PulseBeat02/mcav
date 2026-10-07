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
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.runtime.CommandTask;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the reading of what yt-dlp prints about a page: the metadata of a video comes from the site that hosts it,
 * titles, formats and their URLs included. Whatever yt-dlp prints, the parser returns the metadata of its first line
 * or refuses it with the {@link YTDLPParseException} it documents, and nothing else escapes; every strategy then picks
 * only a stream of the metadata that it accepts, and the stream it picked becomes a source or is refused as
 * {@link Format#toUriSource()} documents.
 *
 * <p>The first byte of an input is the exit code of yt-dlp, the rest what it printed. The seeds are a real dump of a
 * video, on one line as yt-dlp prints it, and dumps whose fields have other types, are missing or are followed by
 * more text.
 */
@Tag("fuzz")
final class YTDLPParserFuzzTest {

  private static final Path EXECUTABLE = Path.of("yt-dlp");
  private static final UriSource PAGE = UriSource.uri(URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
  private static final List<Strategy> STRATEGIES = List.of(
    new Strategy(FormatStrategy.LOWEST_QUALITY_AUDIO, FormatStrategy::hasAudio),
    new Strategy(FormatStrategy.LOWEST_QUALITY_VIDEO, FormatStrategy::hasVideo),
    new Strategy(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy::hasAudio),
    new Strategy(FormatStrategy.BEST_QUALITY_VIDEO, FormatStrategy::hasVideo),
    new Strategy(FormatStrategy.FIRST_AUDIO, FormatStrategy::hasAudio),
    new Strategy(FormatStrategy.FIRST_VIDEO, FormatStrategy::hasVideo),
    new Strategy(FormatStrategy.PREFER_WEBM_AUDIO, format -> FormatStrategy.isHttps(format) && "webm".equals(format.audio_ext)),
    new Strategy(FormatStrategy.PREFER_MP4_VIDEO, format -> FormatStrategy.isHttps(format) && "mp4".equals(format.video_ext))
  );

  @FuzzTest(maxDuration = "30s")
  void readsWhatYtdlpPrintsOrRefusesIt(final byte[] input) throws IOException {
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
    final YTDLPParserImpl parser = new YTDLPParserImpl(() -> EXECUTABLE, arguments -> task);

    final URLParseDump dump;
    try {
      dump = parser.parse(PAGE);
    } catch (final YTDLPParseException refused) {
      return;
    }
    assertEquals(0, exitCode, "only metadata of a successful run is read");
    for (final Strategy strategy : STRATEGIES) {
      final Optional<Format> picked = strategy.strategy().select(dump);
      if (picked.isEmpty()) {
        continue;
      }
      final Format format = picked.get();
      assertTrue(dump.formats != null && dump.formats.contains(format), "a strategy picks a stream of the metadata");
      assertTrue(strategy.accepts().test(format), "a strategy picks only a stream it accepts");
      try {
        format.toUriSource();
      } catch (final IllegalStateException | IllegalArgumentException refused) {
        // a stream without a URL, or with one that is no URI, as documented
      }
    }
  }

  /**
   * A strategy and the streams it accepts.
   */
  private record Strategy(FormatStrategy strategy, Predicate<Format> accepts) {}
}
