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
import me.brandonli.mcav.media.player.PlayerException;
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
import org.openqa.selenium.devtools.v136.page.model.ScreencastFrame;
import org.openqa.selenium.devtools.v136.page.model.ScreencastFrameMetadata;
import org.openqa.selenium.interactions.Action;
import org.openqa.selenium.interactions.Actions;

public final class ChromeDriverPlayer implements BrowserPlayer {

  private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

  private final ChromeDriver driver;
  private final AtomicBoolean running;
  private final ExecutorService captureExecutor;
  private final ExecutorService processingExecutor;
  private final Object lock;
  private final DevTools tools;

  private volatile long frameWidth;
  private volatile long frameHeight;
  private volatile CompletableFuture<Void> captureTask;
  private volatile VideoPipelineStep videoPipeline;
  private volatile BrowserSource source;

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
      return true;
    }
  }

  private void startScreenCapture() {
    final int width = this.source.getScreencastWidth();
    final int height = this.source.getScreencastHeight();
    final Dimension size = new Dimension(width, height);
    final WebDriver.Options options = this.driver.manage();
    final WebDriver.Window window = options.window();
    window.setSize(size);
    window.maximize();

    final String resource = this.source.getResource();
    this.driver.get(resource);
    this.tools.createSession();
    this.tools.addListener(Page.screencastFrame(), this::handleFrame);

    final int qualityRaw = this.source.getScreencastQuality();
    final int nthRaw = this.source.getScreencastNthFrame();
    final Optional<Page.StartScreencastFormat> format = Optional.of(Page.StartScreencastFormat.JPEG);
    final Optional<Integer> quality = Optional.of(qualityRaw);
    final Optional<Integer> maxWidth = Optional.of(width);
    final Optional<Integer> maxHeight = Optional.of(height);
    final Optional<Integer> everyNthFrame = Optional.of(nthRaw);
    this.tools.send(Page.startScreencast(format, quality, maxWidth, maxHeight, everyNthFrame));
  }

  private void handleFrame(final ScreencastFrame frame) {
    final int width = this.source.getScreencastWidth();
    final int height = this.source.getScreencastHeight();
    try {
      if (this.running.get()) {
        final ScreencastFrameMetadata frameMetadata = frame.getMetadata();
        this.frameWidth = (long) frameMetadata.getDeviceWidth();
        this.frameHeight = (long) frameMetadata.getDeviceHeight();
        final byte[] buffer = BASE64_DECODER.decode(frame.getData());
        final VideoMetadata metadata = VideoMetadata.of(width, height);
        if (buffer != null) {
          final StaticImage staticImage = StaticImage.bytes(buffer);
          staticImage.resize(width, height);
          VideoPipelineStep current = this.videoPipeline;
          while (current != null) {
            current.process(staticImage, metadata);
            current = current.next();
          }
          staticImage.release();
        }
      }
      this.tools.send(Page.screencastFrameAck(frame.getSessionId()));
    } catch (final IOException e) {
      throw new PlayerException(e.getMessage(), e);
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
    final int sourceWidth = this.source.getScreencastWidth();
    final int sourceHeight = this.source.getScreencastHeight();
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

      ExecutorUtils.shutdownExecutorGracefully(this.captureExecutor);
      ExecutorUtils.shutdownExecutorGracefully(this.processingExecutor);

      return true;
    }
  }
}
