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
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.source.uri.UriSource;

/**
 * Prints the direct video and audio stream URLs yt-dlp finds for a YouTube page.
 */
public final class YTDLPExample {

  static void main() throws IOException {
    final MCAVApi api = MCAV.api();
    try {
      api.install();
      final CompletableFuture<Boolean> ready = api.whenCapabilityReady(Capability.YT_DLP);
      final boolean available = ready.join();
      if (!available) {
        throw new IllegalStateException("yt-dlp is unavailable");
      }

      final URI uri = URI.create("https://www.youtube.com/watch?v=47dtFZ8CFo8");
      final UriSource page = UriSource.uri(uri);
      final YTDLPParser parser = YTDLPParser.simple();
      final URLParseDump dump = parser.parse(page);
      final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
      final Format videoFormat = selector.getVideoSource(dump);
      final Format audioFormat = selector.getAudioSource(dump);
      final UriSource videoSource = videoFormat.toUriSource();
      final UriSource audioSource = audioFormat.toUriSource();
      System.out.println("Video: " + videoSource);
      System.out.println("Audio: " + audioSource);
    } finally {
      api.release();
    }
  }
}
