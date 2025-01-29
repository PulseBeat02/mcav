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
package me.brandonli.mcav.vnc;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.VNCSource;

public class VNCInputExample {

  private static class VNCPanel extends JPanel {

    private BufferedImage image;

    @Override
    protected void paintComponent(final Graphics g) {
      super.paintComponent(g);
      if (this.image != null) {
        g.drawImage(this.image, 0, 0, this.getWidth(), this.getHeight(), (img, infoflags, x, y, width, height) -> false);
      }
    }

    public void setImage(final BufferedImage img) {
      this.image = img;
      this.repaint();
    }
  }

  public static void main(final String[] args) {
    final MCAVApi api = MCAV.api();
    api.install();

    final VNCPanel vncPanel = new VNCPanel();
    vncPanel.setVisible(true);

    final JFrame frame = new JFrame("VNC Viewer Test");
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setSize(800, 600);
    frame.add(vncPanel, BorderLayout.CENTER);
    frame.setSize(1000, 800);
    frame.setVisible(true);

    final VideoPipelineStep pipeline = PipelineBuilder.video().then((image, step) -> vncPanel.setImage(image.toBufferedImage())).build();
    final VNCSource source = VNCSource.vnc().host("localhost").port(5900).screenWidth(800).screenHeight(600).targetFrameRate(30).build();
    final VNCPlayer player = VNCPlayer.vm();
    final Runtime runtime = Runtime.getRuntime();
    runtime.addShutdownHook(
      new Thread(() -> {
        player.release();
        api.release();
      })
    );

    player.start(pipeline, source);
  }
}
