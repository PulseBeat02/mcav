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
import java.awt.event.MouseWheelEvent;
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
import me.brandonli.mcav.media.player.pipeline.filter.video.FramesPerSecondFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;

// Streams a web page into a window and forwards clicks, the mouse wheel and typed characters to it.
public final class BrowserInputExample {

  private static final String PAGE_URL = "https://www.wikipedia.org";

  private static final int FRAME_INTERVAL = 1;

  private static final int NO_HORIZONTAL_SCROLL = 0;
  private static final int PIXELS_PER_NOTCH = 100;

  private static final char LINE_FEED = '\n';

  private static final String ENTER_KEY = "Enter";

  private static final int WIDTH = 1280;
  private static final int HEIGHT = 720;

  private BrowserInputExample() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static void main(final String[] args) {
    final MCAVApi api = MCAV.api();
    api.install(BrowserModule.class);
    final JLabel label = createWindow();
    final VideoPipelineStep pipeline = createPipeline(label);

    final BrowserPlayer browser = BrowserPlayer.create();
    final VideoAttachableCallback callback = browser.getVideoAttachableCallback();
    callback.attach(pipeline);
    final URI uri = URI.create(PAGE_URL);
    final BrowserSource source = BrowserSource.uri(uri, WIDTH, HEIGHT, FRAME_INTERVAL);
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
    final FramesPerSecondFilter framesPerSecondFilter = new FramesPerSecondFilter();
    builder.then(framesPerSecondFilter);
    builder.then((samples, _) -> showUnchanged(label, samples));

    return builder.build();
  }

  private static boolean showUnchanged(final JLabel label, final ImageBuffer samples) {
    show(label, samples);

    return false;
  }

  private static void forwardClicks(final JLabel label, final BrowserPlayer browser) {
    label.addMouseListener(
      new MouseAdapter() {
        @Override
        public void mouseClicked(final MouseEvent event) {
          label.requestFocusInWindow();
          final int pageX = event.getX();
          final int pageY = event.getY();
          browser.sendMouseEvent(MouseClick.LEFT, pageX, pageY);
        }
      }
    );
    label.addMouseWheelListener(event -> forwardWheel(browser, event));
  }

  private static void forwardWheel(final BrowserPlayer browser, final MouseWheelEvent event) {
    final int pageX = event.getX();
    final int pageY = event.getY();
    final int rotation = event.getWheelRotation();
    final int deltaY = rotation * PIXELS_PER_NOTCH;
    browser.scroll(pageX, pageY, NO_HORIZONTAL_SCROLL, deltaY);
  }

  private static void forwardKeys(final JLabel label, final BrowserPlayer browser) {
    label.addKeyListener(
      new KeyAdapter() {
        @Override
        public void keyTyped(final KeyEvent event) {
          final char character = event.getKeyChar();
          final String text = character == LINE_FEED ? ENTER_KEY : String.valueOf(character);
          browser.sendKeyEvent(text);
        }
      }
    );
  }

  private static void releaseOnShutdown(final BrowserPlayer browser, final MCAVApi api) {
    final Thread hook = new Thread(() -> releaseBrowserAndApi(browser, api));
    final Runtime runtime = Runtime.getRuntime();
    runtime.addShutdownHook(hook);
  }

  private static void releaseBrowserAndApi(final BrowserPlayer browser, final MCAVApi api) {
    browser.release();
    api.release();
  }

  private static void show(final JLabel label, final ImageBuffer samples) {
    final BufferedImage image = samples.toBufferedImage();
    final ImageIcon icon = new ImageIcon(image);
    SwingUtilities.invokeLater(() -> label.setIcon(icon));
  }
}
