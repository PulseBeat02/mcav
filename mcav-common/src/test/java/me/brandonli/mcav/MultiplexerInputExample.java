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
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.audio.DirectAudioOutput;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a YouTube video with yt-dlp into its best separate video and audio streams and plays both in sync.
 */
public final class MultiplexerInputExample {

  private static final Logger LOGGER = LoggerFactory.getLogger(MultiplexerInputExample.class);

  static void main() throws IOException, InterruptedException {
    try (final ExampleResources resources = new ExampleResources()) {
      final MCAVApi api = MCAV.api();
      resources.add(api::release);
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

      final SwingVideoWindow window = SwingVideoWindow.open("YouTube", 1280, 720);
      resources.add(window::close);
      final DirectAudioOutput speakers = new DirectAudioOutput();
      resources.add(speakers::release);
      speakers.start();

      final AudioPipelineStep audioPipeline = AudioPipelineStep.of(speakers);
      final VideoFilter display = window.asFilter();
      final VideoPipelineStep videoPipeline = VideoPipelineStep.of(display);

      final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
      resources.add(player::release);
      player.setExceptionHandler((context, throwable) -> LOGGER.error("Playback failed in {}", context, throwable));
      final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
      videoCallback.attach(videoPipeline);
      final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
      audioCallback.attach(audioPipeline);
      final DimensionAttachableCallback dimensionCallback = player.getDimensionAttachableCallback();
      final Dimension dimension = new Dimension(1280, 720);
      dimensionCallback.attach(dimension);

      final boolean started = player.start(videoSource, audioSource);
      if (!started) {
        throw new IllegalStateException("Playback could not start");
      }

      window.awaitClosed();
    }
  }
}
