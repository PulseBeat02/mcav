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
package me.brandonli.mcav.browser;

import java.awt.BorderLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
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
import me.brandonli.mcav.media.player.pipeline.filter.video.FPSFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;

/**
 * Streams a web page into a window and forwards clicks and typed characters to it. Pass {@code playwright} as
 * the first argument to use the Playwright backend instead of Selenium.
 */
public final class BrowserInputExample {

  private static final int WIDTH = 1280;
  private static final int HEIGHT = 720;

  static void main(final String[] args) {
    final MCAVApi api = MCAV.api();
    api.install(BrowserModule.class);
    final JLabel label = createWindow();
    final VideoPipelineStep pipeline = createPipeline(label);

    final BrowserPlayer browser = createPlayer(args);
    final VideoAttachableCallback callback = browser.getVideoAttachableCallback();
    callback.attach(pipeline);
    final URI uri = URI.create("https://www.wikipedia.org");
    final BrowserSource source = BrowserSource.uri(uri, 80, WIDTH, HEIGHT, 1);
    browser.start(source);

    forwardClicks(label, browser);
    forwardKeys(label, browser);
    releaseOnShutdown(browser, api);
  }

  private static JLabel createWindow() {
    final JFrame frame = new JFrame("Browser");
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

  private static VideoPipelineStep createPipeline(final JLabel label) {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    final FPSFilter fpsFilter = new FPSFilter();
    builder.then(fpsFilter);
    builder.then((samples, _) -> {
      show(label, samples);
      // Displaying or recording the frame leaves its pixels unchanged.
      return false;
    });
    return builder.build();
  }

  private static BrowserPlayer createPlayer(final String[] args) {
    final boolean playwright = args.length > 0 && "playwright".equalsIgnoreCase(args[0]);
    return playwright ? BrowserPlayer.playwright() : BrowserPlayer.selenium();
  }

  private static void forwardClicks(final JLabel label, final BrowserPlayer browser) {
    label.addMouseListener(
      new MouseAdapter() {
        @Override
        public void mouseClicked(final MouseEvent event) {
          label.requestFocusInWindow();
          final int x = event.getX();
          final int y = event.getY();
          browser.sendMouseEvent(MouseClick.LEFT, x, y);
        }
      }
    );
  }

  private static void forwardKeys(final JLabel label, final BrowserPlayer browser) {
    label.addKeyListener(
      new KeyAdapter() {
        @Override
        public void keyTyped(final KeyEvent event) {
          final char character = event.getKeyChar();
          final String text = character == '\n' ? "Enter" : String.valueOf(character);
          browser.sendKeyEvent(text);
        }
      }
    );
  }

  private static void releaseOnShutdown(final BrowserPlayer browser, final MCAVApi api) {
    final Thread hook = new Thread(() -> {
      browser.release();
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
