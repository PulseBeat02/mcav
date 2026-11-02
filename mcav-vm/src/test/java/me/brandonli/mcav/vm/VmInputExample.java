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
import me.brandonli.mcav.vnc.VncModule;

// Boots the ISO image named by the first argument in QEMU, shows its screen in a window and forwards clicks and keys.
public final class VmInputExample {

  private static final int WIDTH = 1024;
  private static final int HEIGHT = 768;

  private static final int FRAME_RATE = 30;

  private static final int MEMORY_MEGABYTES = 2048;
  private static final int CORES = 2;

  private static final char ENTER_CHARACTER = '\n';
  private static final String RETURN_KEY = "Return";

  private VmInputExample() {
    final String message = "Example class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static void main(final String[] args) {
    if (args.length == 0) {
      System.err.println("Usage: VmInputExample <iso>");
      return;
    }

    final String iso = args[0];
    final MCAVApi api = MCAV.api();
    api.install(VncModule.class, VmModule.class);

    final JLabel label = createWindow();
    final VmPlayer player = VmPlayer.create();
    showFrames(player, label);
    startMachine(player, iso);
    forwardInput(player, label);

    final Runtime runtime = Runtime.getRuntime();
    final Thread cleanup = new Thread(() -> release(player, api));
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

  private static void showFrames(final VmPlayer player, final JLabel label) {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then((image, _) -> show(label, image));
    final VideoPipelineStep pipeline = builder.build();

    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(pipeline);
  }

  private static void startMachine(final VmPlayer player, final String iso) {
    final VmConfiguration configuration = VmConfiguration.builder().cdrom(iso).memory(MEMORY_MEGABYTES).cores(CORES).build();
    final VmSettings settings = VmSettings.of(WIDTH, HEIGHT, FRAME_RATE);
    player.start(settings, VmPlayer.Architecture.X86_64, configuration);
  }

  private static void forwardInput(final VmPlayer player, final JLabel label) {
    final MouseAdapter clicks = new MouseAdapter() {
      @Override
      public void mouseClicked(final MouseEvent event) {
        label.requestFocusInWindow();
        final int frameX = event.getX();
        final int frameY = event.getY();
        player.sendMouseEvent(MouseClick.LEFT, frameX, frameY);
      }
    };
    label.addMouseListener(clicks);

    final KeyAdapter keys = new KeyAdapter() {
      @Override
      public void keyTyped(final KeyEvent event) {
        final char character = event.getKeyChar();
        final String text = character == ENTER_CHARACTER ? RETURN_KEY : String.valueOf(character);
        player.sendKeyEvent(text);
      }
    };
    label.addKeyListener(keys);
  }

  private static void release(final VmPlayer player, final MCAVApi api) {
    player.release();
    api.release();
  }

  private static boolean show(final JLabel label, final ImageBuffer samples) {
    final BufferedImage image = samples.toBufferedImage();
    final ImageIcon icon = new ImageIcon(image);
    SwingUtilities.invokeLater(() -> label.setIcon(icon));

    return false;
  }
}
