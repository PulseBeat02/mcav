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

import com.google.common.base.Equivalence;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.utils.ThrowableUtils;

/**
 * A window that shows frames, shared by the examples. The {@link #asFilter()} method turns it into a video filter
 * that can be attached at the end of a pipeline.
 */
public final class SwingVideoWindow implements AutoCloseable {

  private static final Equivalence<Object> IDENTITY = Equivalence.identity();

  private final JLabel label;
  private final JFrame frame;
  private final CountDownLatch closed = new CountDownLatch(1);

  /**
   * Creates an exit-on-close window. Call this constructor on the Swing event dispatch thread; the examples use
   * {@link #open(String, int, int)} to create a disposable window safely from their main thread.
   */
  public SwingVideoWindow(final String title, final int width, final int height) {
    this(title, width, height, WindowConstants.EXIT_ON_CLOSE);
  }

  private SwingVideoWindow(final String title, final int width, final int height, final int closeOperation) {
    this.frame = new JFrame(title);
    try {
      this.frame.setDefaultCloseOperation(closeOperation);
      this.frame.setSize(width, height);
      this.label = new JLabel();
      final Dimension size = new Dimension(width, height);
      this.label.setPreferredSize(size);
      this.label.setHorizontalAlignment(JLabel.CENTER);
      this.label.setVerticalAlignment(JLabel.CENTER);
      this.label.setBackground(Color.BLACK);
      this.label.setOpaque(true);
      final BorderLayout layout = new BorderLayout();
      this.frame.setLayout(layout);
      this.frame.add(this.label, BorderLayout.CENTER);
      final WindowAdapter listener = new WindowAdapter() {
        @Override
        public void windowClosed(final WindowEvent event) {
          closed.countDown();
        }
      };
      this.frame.addWindowListener(listener);
      this.frame.setVisible(true);
    } catch (final RuntimeException | Error failure) {
      ThrowableUtils.throwIfFatal(failure);
      try {
        this.frame.dispose();
      } catch (final RuntimeException | Error cleanupFailure) {
        ThrowableUtils.throwIfFatal(cleanupFailure);
        if (!IDENTITY.equivalent(failure, cleanupFailure)) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      throw failure;
    }
  }

  /** Creates a window on the Swing event dispatch thread, preserving interruption until the caller owns it. */
  public static SwingVideoWindow open(final String title, final int width, final int height) {
    final CompletableFuture<SwingVideoWindow> result = new CompletableFuture<>();
    final Runnable create = () -> {
      try {
        final SwingVideoWindow window = new SwingVideoWindow(title, width, height, WindowConstants.DISPOSE_ON_CLOSE);
        result.complete(window);
      } catch (final RuntimeException | Error failure) {
        // Publish the failure before propagating it on Swing's thread, so the caller cannot be stranded.
        result.completeExceptionally(failure);
        ThrowableUtils.throwIfFatal(failure);
      }
    };
    if (SwingUtilities.isEventDispatchThread()) {
      create.run();
    } else {
      SwingUtilities.invokeLater(create);
    }
    try {
      return result.join();
    } catch (final CompletionException failure) {
      final Throwable cause = result.exceptionNow();
      ThrowableUtils.throwIfFatal(cause);
      throw failure;
    }
  }

  /** Waits until the window is disposed; call this from the example's main thread, not from Swing callbacks. */
  public void awaitClosed() throws InterruptedException {
    this.closed.await();
  }

  /** Disposes the window on Swing's thread without joining that thread from a JVM shutdown hook. */
  @Override
  public void close() {
    if (SwingUtilities.isEventDispatchThread()) {
      this.frame.dispose();
    } else {
      SwingUtilities.invokeLater(this.frame::dispose);
    }
  }

  public VideoFilter asFilter() {
    return (samples, _) -> {
      this.show(samples);
      // the frame is only read, so the filter reports that it left the samples untouched
      return false;
    };
  }

  private void show(final ImageBuffer samples) {
    final BufferedImage image = samples.toBufferedImage();
    final ImageIcon icon = new ImageIcon(image);
    SwingUtilities.invokeLater(() -> this.label.setIcon(icon));
  }
}
