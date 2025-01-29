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

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.player.vm.VMConfiguration;
import me.brandonli.mcav.media.player.vm.VMPlayer;

@SuppressWarnings("all")
public class VMInputExample {

  private static final String DEFAULT_ISO_PATH = "C:\\linuxmint-22.1-cinnamon-64bit.iso";
  private static final int VNC_PORT = 5900;
  private static final int WINDOW_WIDTH = 1024;
  private static final int WINDOW_HEIGHT = 768;

  private VMPlayer vmPlayer;
  private MCAVApi mcavApi;
  private final AtomicReference<BufferedImage> currentImage = new AtomicReference<>();

  private static class VMPanel extends JPanel {

    private BufferedImage image;

    @Override
    protected void paintComponent(final Graphics g) {
      super.paintComponent(g);
      if (this.image != null) {
        g.drawImage(this.image, 0, 0, this.getWidth(), this.getHeight(), null);
      }
    }

    public void setImage(final BufferedImage img) {
      this.image = img;
      this.repaint();
    }
  }

  public static void main(final String[] args) {
    final String isoPath = args.length > 0 ? args[0] : DEFAULT_ISO_PATH;
    final VMInputExample app = new VMInputExample();
    app.start(isoPath);
  }

  private void start(final String isoPath) {
    try {
      final JFrame frame = this.createUI();
      this.startVM(frame, isoPath);
      Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
    } catch (final Exception e) {
      e.printStackTrace();
      this.cleanup();
    }
  }

  private JFrame createUI() {
    final VMPanel vmPanel = new VMPanel();

    final JFrame frame = new JFrame("VM Viewer");
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
    frame.setLayout(new BorderLayout());
    frame.add(vmPanel, BorderLayout.CENTER);
    frame.setVisible(true);

    return frame;
  }

  private void startVM(final JFrame frame, final String isoPath) throws Exception {
    final VMPanel vmPanel = (VMPanel) ((BorderLayout) frame.getContentPane().getLayout()).getLayoutComponent(BorderLayout.CENTER);

    this.mcavApi = MCAV.api();
    this.mcavApi.install();

    final VideoPipelineStep pipeline = PipelineBuilder.video()
      .then((image, step) -> {
        final BufferedImage bufferedImage = image.toBufferedImage();
        vmPanel.setImage(bufferedImage);
        this.currentImage.set(bufferedImage);
      })
      .build();

    final VideoMetadata videoMetadata = VideoMetadata.of(WINDOW_WIDTH, WINDOW_HEIGHT);

    this.vmPlayer = VMPlayer.vm();

    final VMConfiguration config = VMConfiguration.builder().cdrom(isoPath).memory(2048);

    this.vmPlayer.startAsync(pipeline, VMPlayer.Architecture.X86_64, config, VideoMetadata.of(600, 800));
  }

  private void cleanup() {
    if (this.vmPlayer != null) {
      try {
        this.vmPlayer.release();
      } catch (final Exception e) {
        e.printStackTrace();
      }
    }

    if (this.mcavApi != null) {
      try {
        this.mcavApi.release();
      } catch (final Exception e) {
        e.printStackTrace();
      }
    }
  }
}
