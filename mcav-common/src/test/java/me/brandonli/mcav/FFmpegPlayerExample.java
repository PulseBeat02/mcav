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
import me.brandonli.mcav.media.player.pipeline.filter.video.FPSFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.uri.UriSource;

/**
 * Plays a video from the web with the FFmpeg backend, shows it in a window, plays its audio through the speakers,
 * and pauses and resumes after a few seconds.
 */
public final class FFmpegPlayerExample {

  static void main() throws InterruptedException {
    final MCAVApi api = MCAV.api();
    api.install();

    final SwingVideoWindow window = new SwingVideoWindow("FFmpeg Player", 1280, 720);
    final DirectAudioOutput speakers = new DirectAudioOutput();
    speakers.start();

    final AudioPipelineStepBuilder audioBuilder = PipelineBuilder.audio();
    audioBuilder.then(speakers);
    final AudioPipelineStep audioPipeline = audioBuilder.build();

    final VideoFilter display = window.asFilter();
    final VideoPipelineStepBuilder videoBuilder = PipelineBuilder.video();
    videoBuilder.then(new FPSFilter());
    videoBuilder.then(display);
    final VideoPipelineStep videoPipeline = videoBuilder.build();

    final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    videoCallback.attach(videoPipeline);
    final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
    audioCallback.attach(audioPipeline);

    // registered before anything plays, so the player is released even when the example is stopped early
    final Runtime runtime = Runtime.getRuntime();
    runtime.addShutdownHook(
      new Thread(() -> {
        player.release();
        speakers.release();
        api.release();
      })
    );

    final URI uri = URI.create("https://github.com/mediaelement/mediaelement-files/raw/refs/heads/master/big_buck_bunny.mp4");
    final UriSource source = UriSource.uri(uri);
    player.start(source);

    TimeUnit.SECONDS.sleep(5);
    player.pause();
    TimeUnit.SECONDS.sleep(3);
    player.resume();
  }
}
