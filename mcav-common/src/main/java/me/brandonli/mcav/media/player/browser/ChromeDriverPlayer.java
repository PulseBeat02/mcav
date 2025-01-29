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
package me.brandonli.mcav.media.player.browser;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.combined.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.source.BrowserSource;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Action;
import org.openqa.selenium.interactions.Actions;

/**
 * A browser-based player implementation using ChromeDriver to facilitate video streaming,
 * browser interaction, and screen capture processing. The {@code ChromeDriverPlayer} class
 * manages a ChromeDriver instance, frame rate controlled screenshot capture, video pipeline
 * step execution for video processing, and sending mouse events within the browser's window.
 * <p>
 * This class provides functionalities such as session management, screen capture at specific frame rates,
 * video pipeline integration, and executing tasks in a thread-safe manner with concurrency control.
 */
public final class ChromeDriverPlayer implements BrowserPlayer {

  private final ChromeDriver driver;
  private final AtomicBoolean running;
  private final ExecutorService captureExecutor;
  private final ExecutorService processingExecutor;
  private final Object lock;

  private volatile CompletableFuture<Void> captureTask;
  private volatile CompletableFuture<Void> processingTask;

  private volatile VideoPipelineStep videoPipeline;
  private volatile BrowserSource source;
  private final BlockingQueue<byte[]> frameBuffer;

  private static final int BUFFER_CAPACITY = 128;

  /**
   * Initializes an instance of {@code ChromeDriverPlayer} with the specified arguments.
   * This implementation sets up a ChromeDriver instance with given browser arguments
   * and prepares three executors - one for capturing frames, one for processing them,
   * and one for handling miscellaneous tasks.
   *
   * @param args the arguments to configure the Chrome browser instance.
   */
  public ChromeDriverPlayer(final String... args) {
    final ChromeDriverService service = ChromeDriverServiceProvider.getService();
    this.lock = new Object();
    this.driver = new ChromeDriver(service, new ChromeOptions().addArguments(args));
    this.running = new AtomicBoolean(false);
    this.captureExecutor = Executors.newSingleThreadExecutor(r -> {
      final Thread t = new Thread(r, "chrome-capture-thread");
      t.setDaemon(true);
      return t;
    });
    this.processingExecutor = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), r -> {
      final Thread t = new Thread(r, "chrome-processing-thread");
      t.setDaemon(true);
      return t;
    });
    this.frameBuffer = new LinkedBlockingQueue<>(BUFFER_CAPACITY);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final VideoPipelineStep videoPipeline, final BrowserSource combined) throws Exception {
    synchronized (this.lock) {
      this.source = combined;
      this.videoPipeline = videoPipeline;
      this.running.set(true);
      this.captureTask = CompletableFuture.runAsync(this::startScreenCapture, this.captureExecutor);
      this.processingTask = CompletableFuture.runAsync(this::processFrames, this.processingExecutor);
      return true;
    }
  }

  private void startScreenCapture() {
    final VideoMetadata metadata = this.source.getMetadata();
    final int width = metadata.getVideoWidth();
    final int height = metadata.getVideoHeight();
    final double frameRate = metadata.getVideoFrameRate();
    final long frameIntervalMs = (long) (1000.0 / frameRate);
    final Dimension size = new Dimension(width, height);
    this.driver.manage().window().setSize(size);
    final String resource = this.source.getResource();
    this.driver.get(resource);
    long nextFrameTime = System.currentTimeMillis();
    try {
      while (this.running.get() && !Thread.currentThread().isInterrupted()) {
        final long currentTime = System.currentTimeMillis();
        if (currentTime >= nextFrameTime) {
          final byte[] frameBytes = this.driver.getScreenshotAs(OutputType.BYTES);
          final boolean added = this.frameBuffer.offer(frameBytes);
          if (!added) {
            this.frameBuffer.poll();
            this.frameBuffer.offer(frameBytes);
          }
          nextFrameTime += frameIntervalMs;
          if (nextFrameTime < currentTime) {
            nextFrameTime = currentTime;
          }
        } else {
          Thread.sleep(Math.max(1, nextFrameTime - currentTime));
        }
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private void processFrames() {
    try {
      final int bufferingThreshold = BUFFER_CAPACITY / 2;
      if (this.running.get()) {
        while (this.frameBuffer.size() < bufferingThreshold && this.running.get()) {
          Thread.sleep(50);
        }
      }
      final File bufferedImageFile = File.createTempFile("screenshot_", ".jpg");
      bufferedImageFile.deleteOnExit();
      while (this.running.get() && !Thread.currentThread().isInterrupted()) {
        try {
          final byte[] frameData = this.frameBuffer.poll(500, TimeUnit.MILLISECONDS);
          if (frameData != null) {
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(frameData));
            ImageIO.write(image, "jpg", bufferedImageFile);
            final VideoMetadata metadata = this.source.getMetadata();
            final StaticImage staticImage = StaticImage.path(FileSource.path(bufferedImageFile.toPath()));
            VideoPipelineStep current = this.videoPipeline;
            while (current != null) {
              current.process(staticImage, metadata);
              current = current.next();
            }
          }
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void sendMouseEvent(final int x, final int y, final MouseClick type) {
    final Actions actions = new Actions(this.driver);
    final Actions move = actions.moveToLocation(x, y);
    final Actions modified = this.getAction(type, move);
    final Action action = modified.build();
    action.perform();
  }

  private Actions getAction(final MouseClick type, final Actions move) {
    switch (type) {
      case LEFT:
        return move.click();
      case RIGHT:
        return move.contextClick();
      case DOUBLE:
        return move.doubleClick();
      case HOLD:
        return move.clickAndHold();
      case RELEASE:
        return move.release();
      default:
        throw new IllegalArgumentException();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() throws Exception {
    synchronized (this.lock) {
      if (!this.running.get()) {
        return false;
      }

      this.running.set(false);

      if (this.captureTask != null) {
        this.captureTask.cancel(true);
      }
      if (this.processingTask != null) {
        this.processingTask.cancel(true);
      }

      this.frameBuffer.clear();
      this.driver.quit();

      ExecutorUtils.shutdownExecutorGracefully(this.captureExecutor);
      ExecutorUtils.shutdownExecutorGracefully(this.processingExecutor);

      return true;
    }
  }
}
