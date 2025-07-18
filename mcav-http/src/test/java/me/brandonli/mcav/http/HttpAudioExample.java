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
package me.brandonli.mcav.http;

import java.io.IOException;
import java.net.URI;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.uri.UriSource;

public final class HttpAudioExample {

  public static void main(final String[] args) throws IOException {
    final MCAVApi api = MCAV.api();
    api.install();

    final HttpResult result = HttpResult.port(3000);
    final UriSource source = UriSource.uri(URI.create("https://youtu.be/bm0nLJuRNbw?si=lDVNuQ2wIXGMEdMI"));
    final YTDLPParser parser = YTDLPParser.simple();
    final URLParseDump dump = parser.parse(source);
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final UriSource videoFormat = selector.getVideoSource(dump).toUriSource();
    final UriSource audioFormat = selector.getAudioSource(dump).toUriSource();

    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(result);
    final VideoPipelineStep videoPipelineStep = VideoPipelineStep.NO_OP;
    result.start();
    result.setCurrentMedia(dump);

    final VideoPlayerMultiplexer multiplexer = VideoPlayer.vlc();
    multiplexer.setExceptionHandler((context, throwable) -> {
      System.err.println("Error occurred while processing media: " + context);
      throwable.printStackTrace();
    });

    Thread.getAllStackTraces()
      .keySet()
      .forEach(thread1 -> {
        thread1.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
          System.err.println("Uncaught exception in thread: " + thread.getName());
          throwable.printStackTrace();
        });
      });

    final VideoAttachableCallback video = multiplexer.getVideoAttachableCallback();
    video.attach(videoPipelineStep);

    final AudioAttachableCallback audio = multiplexer.getAudioAttachableCallback();
    audio.attach(audioPipelineStep);

    multiplexer.start(videoFormat, audioFormat);

    Runtime.getRuntime()
      .addShutdownHook(
        new Thread(() -> {
          multiplexer.release();
          api.release();
          result.stop();
        })
      );
  }
}
