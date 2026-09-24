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
package me.brandonli.mcav.vnc;

import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;

/**
 * Shows the screen of the VNC server at {@code localhost:5900} in a window and forwards clicks to it.
 */
public final class VNCInputExample {

  private static final int WIDTH = 1024;
  private static final int HEIGHT = 768;

  private VNCInputExample() {
    throw new UnsupportedOperationException("Example class cannot be instantiated");
  }

  /**
   * Opens the window, connects to the server, and releases everything when the JVM exits.
   */
  static void main() {
    final MCAVApi api = MCAV.api();
    api.install(VNCModule.class);

    final JLabel label = new JLabel();
    openWindow(label);

    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then((image, _) -> {
      show(label, image);
      // Displaying or recording the frame leaves its pixels unchanged.
      return false;
    });
    final VideoPipelineStep pipeline = builder.build();

    final VNCSource.Builder sourceBuilder = VNCSource.builder();
    sourceBuilder.host("localhost");
    sourceBuilder.port(5900);
    sourceBuilder.screenWidth(WIDTH);
    sourceBuilder.screenHeight(HEIGHT);
    sourceBuilder.targetFrameRate(30);
    final VNCSource source = sourceBuilder.build();

    final VNCPlayer player = VNCPlayer.create();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(pipeline);
    player.start(source);

    forwardClicks(label, player);
    releaseOnExit(api, player);
  }

  private static void openWindow(final JLabel label) {
    final JFrame frame = new JFrame("VNC");
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    frame.setSize(WIDTH, HEIGHT);
    final BorderLayout layout = new BorderLayout();
    frame.setLayout(layout);
    frame.add(label, BorderLayout.CENTER);
    frame.setVisible(true);
  }

  private static void forwardClicks(final JLabel label, final VNCPlayer player) {
    final MouseListener listener = new MouseAdapter() {
      @Override
      public void mouseClicked(final MouseEvent event) {
        final int x = event.getX();
        final int y = event.getY();
        player.sendMouseEvent(MouseClick.LEFT, x, y);
      }
    };
    label.addMouseListener(listener);
  }

  private static void releaseOnExit(final MCAVApi api, final VNCPlayer player) {
    final Thread hook = new Thread(() -> {
      player.release();
      api.release();
    });
    final Runtime runtime = Runtime.getRuntime();
    runtime.addShutdownHook(hook);
  }

  private static void show(final JLabel label, final ImageBuffer samples) {
    final BufferedImage image = samples.toBufferedImage();
    final ImageIcon icon = new ImageIcon(image);
    SwingUtilities.invokeLater(() -> label.setIcon(icon));
  }
}
