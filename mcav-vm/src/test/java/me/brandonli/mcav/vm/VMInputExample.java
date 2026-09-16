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
package me.brandonli.mcav.vm;

import java.awt.BorderLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
import me.brandonli.mcav.vnc.VNCModule;

/**
 * Boots an ISO image in QEMU, shows the screen in a window, and forwards clicks and typed keys to the machine.
 * Pass the path of the ISO as the first argument.
 */
public final class VMInputExample {

  private static final int WIDTH = 1024;
  private static final int HEIGHT = 768;

  private VMInputExample() {
    throw new UnsupportedOperationException("Example class cannot be instantiated");
  }

  /**
   * Runs the example.
   *
   * @param args the path of the ISO image to boot
   */
  static void main(final String[] args) {
    if (args.length == 0) {
      System.err.println("Usage: VMInputExample <iso>");
      return;
    }

    final String iso = args[0];
    final MCAVApi api = MCAV.api();
    api.install(VNCModule.class, VMModule.class);

    final JLabel label = createWindow();
    final VMPlayer player = VMPlayer.create();
    showFrames(player, label);
    startMachine(player, iso);
    forwardInput(player, label);

    final Runtime runtime = Runtime.getRuntime();
    final Thread cleanup = new Thread(() -> {
      player.release();
      api.release();
    });
    runtime.addShutdownHook(cleanup);
  }

  private static JLabel createWindow() {
    final JFrame frame = new JFrame("Virtual Machine");
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    frame.setSize(WIDTH, HEIGHT);

    final JLabel label = new JLabel();
    label.setFocusable(true);
    final BorderLayout layout = new BorderLayout();
    frame.setLayout(layout);
    frame.add(label, BorderLayout.CENTER);
    frame.setVisible(true);
    return label;
  }

  private static void showFrames(final VMPlayer player, final JLabel label) {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then((image, _) -> {
      show(label, image);
      return true;
    });
    final VideoPipelineStep pipeline = builder.build();

    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(pipeline);
  }

  private static void startMachine(final VMPlayer player, final String iso) {
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.cdrom(iso);
    configuration.memory(2048);
    configuration.cores(2);
    final VMSettings settings = VMSettings.of(WIDTH, HEIGHT, 30);
    player.start(settings, VMPlayer.Architecture.X86_64, configuration);
  }

  private static void forwardInput(final VMPlayer player, final JLabel label) {
    final MouseAdapter clicks = new MouseAdapter() {
      @Override
      public void mouseClicked(final MouseEvent event) {
        label.requestFocusInWindow();
        final int x = event.getX();
        final int y = event.getY();
        player.sendMouseEvent(MouseClick.LEFT, x, y);
      }
    };
    label.addMouseListener(clicks);

    final KeyAdapter keys = new KeyAdapter() {
      @Override
      public void keyTyped(final KeyEvent event) {
        final char character = event.getKeyChar();
        final String text = character == '\n' ? "Return" : String.valueOf(character);
        player.sendKeyEvent(text);
      }
    };
    label.addKeyListener(keys);
  }

  private static void show(final JLabel label, final ImageBuffer samples) {
    final BufferedImage image = samples.toBufferedImage();
    final ImageIcon icon = new ImageIcon(image);
    SwingUtilities.invokeLater(() -> label.setIcon(icon));
  }
}
