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
import me.brandonli.mcav.media.source.UriSource;

@SuppressWarnings("all") // checker
public final class SingleCombinedInputExample {

  public static void main(final String[] args) throws Exception {
    final MCAVApi api = MCAV.api();
    api.install();

    final JFrame video = new JFrame("Video Player");
    video.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    video.setSize(1920, 1080);
    video.getContentPane().setBackground(Color.GREEN);

    final JLabel videoLabel = new JLabel();
    videoLabel.setPreferredSize(new Dimension(1920, 1080));
    videoLabel.setHorizontalAlignment(JLabel.CENTER);
    videoLabel.setVerticalAlignment(JLabel.CENTER);
    videoLabel.setBorder(new LineBorder(Color.BLACK, 3));

    video.setLayout(new BorderLayout());
    video.add(videoLabel, BorderLayout.CENTER);
    video.setVisible(true);

    BufferedImage bufferedImage;
    ImageIcon icon;
    final UriSource source = UriSource.uri(
      URI.create("http://distribution.bbb3d.renderfarming.net/video/mp4/bbb_sunflower_native_60fps_normal.mp4")
    );

    final AudioPipelineStep audioPipelineStep = PipelineBuilder.audio().then(new DirectAudioOutput()).build();
    final VideoPipelineStep videoPipelineStep = PipelineBuilder.video()
      .then(new FPSFilter())
      .then((samples, metadata) -> videoLabel.setIcon(new ImageIcon(samples.toBufferedImage())))
      .build();

    final VideoPlayerMultiplexer multiplexer = VideoPlayer.vlc();
    multiplexer.setExceptionHandler((context, throwable) -> {
      System.err.println("Error occurred while processing media: " + context);
      throwable.printStackTrace();
    });

    final AudioAttachableCallback audioCallback = multiplexer.getAudioAttachableCallback();
    audioCallback.attach(audioPipelineStep);

    final VideoAttachableCallback videoCallback = multiplexer.getVideoAttachableCallback();
    videoCallback.attach(videoPipelineStep);

    final DimensionAttachableCallback dimensionCallback = multiplexer.getDimensionAttachableCallback();
    final me.brandonli.mcav.utils.immutable.Dimension dimension = new me.brandonli.mcav.utils.immutable.Dimension(1920, 1080);
    dimensionCallback.attach(dimension);

    Thread.getAllStackTraces()
      .keySet()
      .forEach(thread1 -> {
        thread1.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
          System.err.println("Uncaught exception in thread: " + thread.getName());
          throwable.printStackTrace();
        });
      });

    multiplexer.start(source);

    Runtime.getRuntime()
      .addShutdownHook(
        new Thread(() -> {
          multiplexer.release();
          api.release();
        })
      );
  }
}
