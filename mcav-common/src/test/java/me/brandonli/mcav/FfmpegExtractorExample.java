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
package me.brandonli.mcav;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.json.ytdlp.YtdlpParser;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.ffmpeg.FfmpegCommand;
import me.brandonli.mcav.utils.ffmpeg.FfmpegTemplates;
import me.brandonli.mcav.utils.runtime.CommandTask;

public final class FfmpegExtractorExample {

  private static final String OUTPUT_FILE_NAME = "output.ogg";

  private FfmpegExtractorExample() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static void main() throws IOException {
    final MCAVApi api = MCAV.api();
    try {
      api.install();

      final CompletableFuture<Boolean> ready = api.whenCapabilityReady(Capability.YT_DLP);
      final boolean available = ready.join();
      if (!available) {
        final String message = "yt-dlp is unavailable!";
        throw new IllegalStateException(message);
      }

      final URI uri = URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
      final UriSource page = UriSource.uri(uri);
      final YtdlpParser parser = YtdlpParser.simple();
      final UrlParseDump dump = parser.parse(page);
      final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
      final Format audioFormat = selector.getAudioSource(dump);
      final UriSource audioSource = audioFormat.toUriSource();
      final String input = audioSource.getResource();

      final Path output = Path.of(OUTPUT_FILE_NAME);
      final Path absolute = output.toAbsolutePath();
      final String outputPath = absolute.toString();
      final FfmpegCommand command = FfmpegTemplates.extractOggVorbis(input, outputPath);
      System.out.println(command);

      final CommandTask task = command.createTask();
      task.runChecked();
      System.out.println("Wrote %s".formatted(absolute));
    } finally {
      api.release();
    }
  }
}
