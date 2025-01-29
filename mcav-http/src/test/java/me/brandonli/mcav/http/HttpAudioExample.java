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
package me.brandonli.mcav.http;

import java.nio.file.Path;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.media.player.combined.VideoPlayer;
import me.brandonli.mcav.media.player.combined.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.media.source.Source;

public final class HttpAudioExample {

  public static void main(final String[] args) {
    final MCAVApi api = MCAV.api();
    api.install();

    final HttpResult result = HttpResult.port(3000);
    final Source source = FileSource.path(Path.of("C:\\rickroll.mp4"));
    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(result);
    final VideoPipelineStep videoPipelineStep = VideoPipelineStep.NO_OP;
    result.start();

    final VideoPlayerMultiplexer multiplexer = VideoPlayer.vlc();
    multiplexer.start(audioPipelineStep, videoPipelineStep, source);

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
