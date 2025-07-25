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

import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import javax.swing.*;
import javax.swing.border.LineBorder;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.audio.DirectAudioOutput;
import me.brandonli.mcav.media.player.pipeline.filter.video.FPSFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.uri.UriSource;

@SuppressWarnings("all")
public final class MultiplexerInputExample {

  public static void main(final String[] args) throws Exception {
    final MCAVApi api = MCAV.api();
    api.install();

    final JFrame video = new JFrame("Video Player");
    video.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    video.setSize(1920, 1080);
    video.getContentPane().setBackground(Color.GREEN);

    final JLabel videoLabel = new JLabel();
    videoLabel.setPreferredSize(new Dimension(1800, 1000));
    videoLabel.setHorizontalAlignment(JLabel.CENTER);
    videoLabel.setVerticalAlignment(JLabel.CENTER);
    videoLabel.setBorder(new LineBorder(Color.BLACK, 3));

    video.setLayout(new BorderLayout());
    video.add(videoLabel, BorderLayout.CENTER);
    video.setVisible(true);

    final UriSource source = UriSource.uri(URI.create("https://www.youtube.com/watch?v=47dtFZ8CFo8"));
    final YTDLPParser parser = YTDLPParser.simple();
    final URLParseDump dump = parser.parse(source);
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final UriSource videoFormat = selector.getVideoSource(dump).toUriSource();
    final UriSource audioFormat = selector.getAudioSource(dump).toUriSource();

    BufferedImage bufferedImage;
    ImageIcon icon;
    final DirectAudioOutput output = new DirectAudioOutput();
    output.start();

    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(output);
    final VideoPipelineStep videoPipelineStep = PipelineBuilder.video()
      .then(new FPSFilter())
      .then((samples, metadata) -> videoLabel.setIcon(new ImageIcon(samples.toBufferedImage())))
      .build();

    final VideoPlayerMultiplexer multiplexer = VideoPlayer.ffmpeg();
    multiplexer.setExceptionHandler((context, throwable) -> {
      System.err.println("Error occurred while processing media: " + context);
      throwable.printStackTrace();
    });

    final VideoAttachableCallback videoCallback = multiplexer.getVideoAttachableCallback();
    videoCallback.attach(videoPipelineStep);

    final AudioAttachableCallback audioCallback = multiplexer.getAudioAttachableCallback();
    audioCallback.attach(audioPipelineStep);

    final DimensionAttachableCallback dimensionCallback = multiplexer.getDimensionAttachableCallback();
    final me.brandonli.mcav.utils.immutable.Dimension dimension = new me.brandonli.mcav.utils.immutable.Dimension(1800, 1000);
    dimensionCallback.attach(dimension);

    multiplexer.start(videoFormat, audioFormat);

    Runtime.getRuntime()
      .addShutdownHook(
        new Thread(() -> {
          output.release();
          multiplexer.release();
          api.release();
        })
      );
  }
}
