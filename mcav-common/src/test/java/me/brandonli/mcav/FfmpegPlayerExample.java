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

import java.net.URI;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.builder.AudioPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.audio.DirectAudioOutput;
import me.brandonli.mcav.media.player.pipeline.filter.video.FramesPerSecondFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.uri.UriSource;

public final class FfmpegPlayerExample {

  private static final int WIDTH = 1280;
  private static final int HEIGHT = 720;

  private static final long PLAY_BEFORE_PAUSE_SECONDS = 5L;
  private static final long PAUSE_SECONDS = 3L;

  private FfmpegPlayerExample() {}

  static void main() throws InterruptedException {
    try (final ExampleResources resources = new ExampleResources()) {
      final MCAVApi api = MCAV.api();
      resources.add(api::release);
      api.install();

      final SwingVideoWindow window = SwingVideoWindow.open("FFmpeg Player", WIDTH, HEIGHT);
      resources.add(window::close);
      final DirectAudioOutput speakers = new DirectAudioOutput();
      resources.add(speakers::release);
      speakers.start();

      final AudioPipelineStepBuilder audioBuilder = PipelineBuilder.audio();
      audioBuilder.then(speakers);
      final AudioPipelineStep audioPipeline = audioBuilder.build();

      final VideoFilter display = window.asFilter();
      final VideoPipelineStepBuilder videoBuilder = PipelineBuilder.video();
      final FramesPerSecondFilter frameRate = new FramesPerSecondFilter();
      videoBuilder.then(frameRate);
      videoBuilder.then(display);
      final VideoPipelineStep videoPipeline = videoBuilder.build();

      final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
      resources.add(player::release);
      final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
      videoCallback.attach(videoPipeline);
      final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
      audioCallback.attach(audioPipeline);

      final URI uri = URI.create("https://github.com/mediaelement/mediaelement-files/raw/refs/heads/master/big_buck_bunny.mp4");
      final UriSource source = UriSource.uri(uri);
      final boolean started = player.start(source);
      if (!started) {
        final String message = "Playback could not start!";
        throw new IllegalStateException(message);
      }

      TimeUnit.SECONDS.sleep(PLAY_BEFORE_PAUSE_SECONDS);
      player.pause();
      TimeUnit.SECONDS.sleep(PAUSE_SECONDS);
      player.resume();

      window.awaitClosed();
    }
  }
}
