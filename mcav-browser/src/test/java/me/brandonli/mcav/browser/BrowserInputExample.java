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
package me.brandonli.mcav.browser;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import javax.swing.*;
import javax.swing.border.LineBorder;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.FPSFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;

@SuppressWarnings("all")
public final class BrowserInputExample {

  public static void main(final String[] args) throws Exception {
    final MCAVApi api = MCAV.api();
    api.install(BrowserModule.class);

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

    final BrowserSource browserSource = BrowserSource.uri(URI.create("https://www.papermc.io"), 100, 1920, 1080, 1);

    BufferedImage bufferedImage;
    ImageIcon icon;
    final VideoPipelineStep videoPipelineStep = PipelineBuilder.video()
      .then(new FPSFilter())
      .then((samples, metadata) -> videoLabel.setIcon(new ImageIcon(samples.toBufferedImage())))
      .build();

    final BrowserPlayer browser = BrowserPlayer.playwright();
    browser.start(videoPipelineStep, browserSource);
    videoLabel.addMouseListener(
      new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          int x = e.getX();
          int y = e.getY();
          System.out.println("Mouse clicked at: " + x + ", " + y);
          browser.sendMouseEvent(MouseClick.LEFT, x, y);
        }
      }
    );

    Runtime.getRuntime()
      .addShutdownHook(
        new Thread(() -> {
          browser.release();
          api.release();
        })
      );
  }
}
