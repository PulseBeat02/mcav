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
package me.brandonli.mcav.media.player.driver;

import java.io.IOException;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.BrowserSource;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v136.page.Page;
import org.openqa.selenium.devtools.v136.page.model.ScreencastFrameMetadata;
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
  private final DevTools tools;

  private volatile byte[] frameBuffer;
  private volatile long frameWidth;
  private volatile long frameHeight;

  private volatile CompletableFuture<Void> captureTask;
  private volatile CompletableFuture<Void> processingTask;

  private volatile VideoPipelineStep videoPipeline;
  private volatile BrowserSource source;

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
    final int processors = Runtime.getRuntime().availableProcessors();
    this.lock = new Object();
    this.driver = new ChromeDriver(service, new ChromeOptions().addArguments(args));
    this.running = new AtomicBoolean(false);
    this.captureExecutor = Executors.newSingleThreadExecutor();
    this.processingExecutor = Executors.newFixedThreadPool(processors);
    this.tools = this.driver.getDevTools();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final VideoPipelineStep videoPipeline, final BrowserSource combined) {
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
    final Dimension size = new Dimension(width, height);
    final WebDriver.Options options = this.driver.manage();
    final WebDriver.Window window = options.window();
    window.setSize(size);
    window.maximize();

    final String resource = this.source.getResource();
    this.driver.get(resource);

    this.tools.createSession();

    final Base64.Decoder decoder = Base64.getDecoder();
    this.tools.addListener(Page.screencastFrame(), frame -> {
        if (this.running.get()) {
          final ScreencastFrameMetadata frameMetadata = frame.getMetadata();
          this.frameWidth = (long) frameMetadata.getDeviceWidth();
          this.frameHeight = (long) frameMetadata.getDeviceHeight();
          this.frameBuffer = decoder.decode(frame.getData());
          this.tools.send(Page.screencastFrameAck(frame.getSessionId()));
        }
      });

    final Optional<Page.StartScreencastFormat> format = Optional.of(Page.StartScreencastFormat.JPEG);
    final Optional<Integer> quality = Optional.of(80);
    final Optional<Integer> maxWidth = Optional.of(width);
    final Optional<Integer> maxHeight = Optional.of(height);
    final Optional<Integer> everyNthFrame = Optional.of(1);
    this.tools.send(Page.startScreencast(format, quality, maxWidth, maxHeight, everyNthFrame));
  }

  private void processFrames() {
    try {
      final VideoMetadata metadata = this.source.getMetadata();
      while (this.running.get()) {
        if (this.frameBuffer != null) {
          final StaticImage staticImage = StaticImage.bytes(this.frameBuffer);
          staticImage.resize(metadata.getVideoWidth(), metadata.getVideoHeight());
          VideoPipelineStep current = this.videoPipeline;
          while (current != null) {
            current.process(staticImage, metadata);
            current = current.next();
          }
          staticImage.release();
        }
      }
    } catch (final IOException e) {
      throw new me.brandonli.mcav.utils.UncheckedIOException(e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void sendMouseEvent(final int x, final int y, final MouseClick type) {
    if (!this.running.get()) {
      return;
    }
    final int[] translated = this.translateCoordinates(x, y);
    final int newX = translated[0];
    final int newY = translated[1];
    final Actions actions = new Actions(this.driver);
    final Actions move = actions.moveToLocation(newX, newY);
    final Actions modified = this.getAction(type, move);
    final Action action = modified.build();
    action.perform();
  }

  private int[] translateCoordinates(final int x, final int y) {
    final VideoMetadata videoMetadata = this.source.getMetadata();
    final int sourceWidth = videoMetadata.getVideoWidth();
    final int sourceHeight = videoMetadata.getVideoHeight();
    final int targetWidth = (int) this.frameWidth;
    final int targetHeight = (int) this.frameHeight;
    final double widthRatio = (double) targetWidth / sourceWidth;
    final double heightRatio = (double) targetHeight / sourceHeight;
    final int newX = (int) (x * widthRatio);
    final int newY = (int) (y * heightRatio);
    final int clampedX = Math.clamp(newX, 0, targetWidth - 1);
    final int clampedY = Math.clamp(newY, 0, targetHeight - 1);
    return new int[] { clampedX, clampedY };
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void sendKeyEvent(final String text) {
    if (!this.running.get()) {
      return;
    }
    final Actions actions = new Actions(this.driver);
    final Actions move = actions.sendKeys(text);
    final Action action = move.build();
    action.perform();
  }

  private Actions getAction(final MouseClick type, final Actions move) {
    return switch (type) {
      case LEFT -> move.click();
      case RIGHT -> move.contextClick();
      case DOUBLE -> move.doubleClick();
      case HOLD -> move.clickAndHold();
      case RELEASE -> move.release();
    };
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() {
    synchronized (this.lock) {
      if (!this.running.get()) {
        return false;
      }

      this.tools.close();
      this.running.set(false);

      if (this.captureTask != null) {
        this.captureTask.cancel(true);
      }
      if (this.processingTask != null) {
        this.processingTask.cancel(true);
      }

      ExecutorUtils.shutdownExecutorGracefully(this.captureExecutor);
      ExecutorUtils.shutdownExecutorGracefully(this.processingExecutor);

      return true;
    }
  }
}
