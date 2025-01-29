/*
 * This file is part of mcav, a media playback library for Minecraft
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
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import me.brandonli.mcav.media.config.MapConfiguration;
import me.brandonli.mcav.media.player.combined.VideoPlayer;
import me.brandonli.mcav.media.player.combined.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.result.MapResult;
import me.brandonli.mcav.media.source.UriSource;

public final class MockableSandbox {

  public static void main(final String[] args) throws Exception {
    final MCAVApi api = MCAV.api();
    api.install();

    final Collection<UUID> viewer = Set.of(UUID.randomUUID());
    final MapConfiguration configuration = MapConfiguration.builder()
      .map(0)
      .mapWidthResolution(640)
      .mapHeightResolution(640)
      .mapBlockWidth(5)
      .mapBlockHeight(5)
      .viewers(viewer)
      .build();
    final MapResult result = new MapResult(configuration);
    final DitherAlgorithm algorithm = DitherAlgorithm.errorDiffusion().build();
    final VideoFilter filter = DitherFilter.dither(algorithm, result);
    final AudioPipelineStep step = AudioPipelineStep.NO_OP;
    final VideoPipelineStep videoStep = VideoPipelineStep.of(filter);
    final VideoPlayerMultiplexer player = VideoPlayer.vlc();
    final UriSource source = UriSource.uri(
      URI.create("https://github.com/mediaelement/mediaelement-files/raw/refs/heads/master/big_buck_bunny.mp4")
    );
    player.start(step, videoStep, source);

    Runtime.getRuntime()
      .addShutdownHook(
        new Thread(() -> {
          try {
            player.release();
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
          api.release();
        })
      );
  }
}
