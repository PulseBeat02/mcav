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
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.player.combined.VideoPlayer;
import me.brandonli.mcav.media.player.combined.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.combined.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.combined.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.combined.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.combined.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.UriSource;

public final class InstallerTest {

  public static void main(final String[] args) throws Exception {
    final MCAVApi api = MCAV.api();
    api.install();
    System.out.println(api.hasCapability(Capability.FFMPEG));
    System.out.println(api.hasCapability(Capability.YTDLP));
    System.out.println(api.hasCapability(Capability.VLC));
    System.out.println(api.hasCapability(Capability.QEMU));
    final UriSource source = UriSource.uri(URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    final YTDLPParser parser = YTDLPParser.simple();
    final URLParseDump dump = parser.parse(source);
    final StrategySelector selector = StrategySelector.of(FormatStrategy.FIRST_AUDIO, FormatStrategy.FIRST_VIDEO);
    final UriSource videoFormat = selector.getVideoSource(dump).toUriSource();
    final UriSource audioFormat = selector.getAudioSource(dump).toUriSource();
    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.NO_OP;
    final VideoPipelineStep videoPipelineStep = PipelineBuilder.video()
      .then(VideoFilter.GRAYSCALE)
      .then((samples, metadata) -> System.out.println("T"))
      .build();
    //    final BrowserPlayer player = BrowserPlayer.defaultChrome();
    //    player.start(videoPipelineStep, BrowserSource.uri(URI.create("https://google.com"), VideoMetadata.of(1200, 1200)));
    final VideoPlayerMultiplexer multiplexer = VideoPlayer.vlc();
    multiplexer.start(audioPipelineStep, videoPipelineStep, videoFormat, audioFormat);
    Thread.sleep(10000);
    //    player.release();
    multiplexer.release();
    api.release();
  }
}
