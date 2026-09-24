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
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.builder.AudioPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.audio.DirectAudioOutput;
import me.brandonli.mcav.media.player.pipeline.filter.video.FPSFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FlipFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.InvertFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays a video with the VLC backend, scaled down by the player, with an inverted and mirrored picture.
 */
public final class VLCPlayerExample {

  private static final Logger LOGGER = LoggerFactory.getLogger(VLCPlayerExample.class);

  static void main() throws InterruptedException {
    try (final ExampleResources resources = new ExampleResources()) {
      final MCAVApi api = MCAV.api();
      resources.add(api::release);
      api.install();
      final CompletableFuture<Boolean> ready = api.whenCapabilityReady(Capability.VLC);
      final boolean available = ready.join();
      if (!available) {
        throw new IllegalStateException("VLC is unavailable");
      }

      final SwingVideoWindow window = SwingVideoWindow.open("VLC Player", 960, 540);
      resources.add(window::close);
      final DirectAudioOutput speakers = new DirectAudioOutput();
      resources.add(speakers::release);
      speakers.start();

      final AudioPipelineStepBuilder audioBuilder = PipelineBuilder.audio();
      audioBuilder.then(speakers);
      final AudioPipelineStep audioPipeline = audioBuilder.build();

      final VideoFilter display = window.asFilter();
      final VideoPipelineStepBuilder videoBuilder = PipelineBuilder.video();
      final FPSFilter frameRate = new FPSFilter();
      videoBuilder.then(frameRate);
      final InvertFilter invert = new InvertFilter();
      videoBuilder.then(invert);
      final FlipFilter flip = new FlipFilter(FlipFilter.FlipDirection.HORIZONTAL);
      videoBuilder.then(flip);
      videoBuilder.then(display);
      final VideoPipelineStep videoPipeline = videoBuilder.build();

      final VideoPlayerMultiplexer player = VideoPlayer.vlc();
      resources.add(player::release);
      player.setExceptionHandler((context, throwable) -> LOGGER.error("Playback failed in {}", context, throwable));
      final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
      audioCallback.attach(audioPipeline);
      final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
      videoCallback.attach(videoPipeline);
      final DimensionAttachableCallback dimensionCallback = player.getDimensionAttachableCallback();
      final Dimension dimension = new Dimension(960, 540);
      dimensionCallback.attach(dimension);

      final URI uri = URI.create("https://download.blender.org/peach/bigbuckbunny_movies/big_buck_bunny_1080p_h264.mov");
      final UriSource source = UriSource.uri(uri);
      final boolean started = player.start(source);
      if (!started) {
        throw new IllegalStateException("Playback could not start");
      }

      window.awaitClosed();
    }
  }
}
