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
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
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
    videoLabel.setPreferredSize(new Dimension(1800, 1000));
    videoLabel.setHorizontalAlignment(JLabel.CENTER);
    videoLabel.setVerticalAlignment(JLabel.CENTER);
    videoLabel.setBorder(new LineBorder(Color.BLACK, 3));

    video.setLayout(new BorderLayout());
    video.add(videoLabel, BorderLayout.CENTER);
    video.setVisible(true);

    BufferedImage bufferedImage;
    ImageIcon icon;
    final UriSource source = UriSource.uri(
      URI.create("https://github.com/mediaelement/mediaelement-files/raw/refs/heads/master/big_buck_bunny.mp4")
    );

    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.NO_OP;
    final VideoPipelineStep videoPipelineStep = PipelineBuilder.video()
      .then(new FPSFilter())
      .then((samples, metadata) -> videoLabel.setIcon(new ImageIcon(samples.toBufferedImage())))
      .build();

    final VideoPlayerMultiplexer multiplexer = VideoPlayer.vlc();
    multiplexer.start(audioPipelineStep, videoPipelineStep, source);

    Runtime.getRuntime()
      .addShutdownHook(
        new Thread(() -> {
          multiplexer.release();
          api.release();
        })
      );
  }
}
