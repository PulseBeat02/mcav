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
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.combined.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.source.BrowserSource;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.Command;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.Event;
import org.openqa.selenium.devtools.v135.page.Page;
import org.openqa.selenium.devtools.v135.page.model.ScreencastFrame;
import org.openqa.selenium.interactions.Action;
import org.openqa.selenium.interactions.Actions;

/**
 * A browser-based player implementation using ChromeDriver to facilitate video streaming,
 * browser interaction, and screen capture processing. The {@code ChromeDriverPlayer} class
 * manages a ChromeDriver instance, DevTools API access, video pipeline step execution for video
 * processing, and sending mouse events within the browser's window.
 * <p>
 * This class provides functionalities such as session management, screen recording, video pipeline
 * integration, and executing tasks in a thread-safe manner with concurrency control.
 */
public final class ChromeDriverPlayer implements BrowserPlayer {

  private final ChromeDriver driver;
  private final DevTools tools;
  private final AtomicBoolean started;
  private final ExecutorService executor;
  private final List<CompletableFuture<?>> pendingTasks;
  private final Object lock;

  private volatile CompletableFuture<Void> videoTask;
  private volatile VideoPipelineStep videoPipeline;
  private volatile BrowserSource source;

  /**
   * Initializes an instance of {@code ChromeDriverPlayer} with the specified arguments.
   * This implementation sets up a ChromeDriver instance with given browser arguments,
   * schedules background tasks using a thread pool, and prepares developer tools interaction.
   *
   * @param args the arguments to configure the Chrome browser instance. These arguments
   *             can include flags for performance optimization, debugging, or specific
   *             browser behaviors.
   */
  public ChromeDriverPlayer(final String... args) {
    final ChromeDriverService service = ChromeDriverServiceProvider.getService();
    this.lock = new Object();
    this.driver = new ChromeDriver(service, new ChromeOptions().addArguments(args));
    this.tools = this.driver.getDevTools();
    this.started = new AtomicBoolean(false);
    this.executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    this.pendingTasks = new CopyOnWriteArrayList<>();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final VideoPipelineStep videoPipeline, final BrowserSource combined) throws Exception {
    synchronized (this.lock) {
      this.source = combined;
      this.videoPipeline = videoPipeline;
      this.videoTask = CompletableFuture.runAsync(this::startScreenCapture, this.executor);
      return true;
    }
  }

  private void startScreenCapture() {
    this.tools.createSession();
    final VideoMetadata metadata = this.source.getMetadata();
    final int width = metadata.getVideoWidth();
    final int height = metadata.getVideoHeight();
    final int bitrate = metadata.getVideoBitrate();
    final int quality = Math.min(100, (bitrate / 80));
    final Dimension size = new Dimension(width, height);
    this.driver.manage().window().setSize(size);
    final Optional<Page.StartScreencastFormat> format = Optional.of(Page.StartScreencastFormat.JPEG);
    final Optional<Integer> quality1 = Optional.of(quality);
    final Optional<Integer> width1 = Optional.of(width);
    final Optional<Integer> height1 = Optional.of(height);
    final Optional<Integer> nth = Optional.of(1);
    final Command<Void> command = Page.startScreencast(format, quality1, width1, height1, nth);
    this.tools.send(command);
    final Event<ScreencastFrame> event = Page.screencastFrame();
    final Base64.Decoder decoder = Base64.getDecoder();
    this.tools.addListener(event, frame -> {
        final String base64 = frame.getData();
        final byte[] data = decoder.decode(base64);
        final CompletableFuture<?> task = CompletableFuture.runAsync(
          () -> {
            final StaticImage image = StaticImage.bytes(data);
            VideoPipelineStep current = this.videoPipeline;
            while (current != null) {
              current.process(image, metadata);
              current = current.next();
            }
          },
          this.executor
        );
        this.pendingTasks.add(task);
        task.whenComplete((result, ex) -> this.pendingTasks.remove(task));
        final int id = frame.getSessionId();
        final Command<Void> next = Page.screencastFrameAck(id);
        this.tools.send(next);
      });

    final String resource = this.source.getResource();
    this.driver.get(resource);
    this.started.set(true);
  }

  private BufferedImage toBufferedImage(final byte[] bytes) {
    try (final ByteArrayInputStream stream = new ByteArrayInputStream(bytes)) {
      return ImageIO.read(stream);
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void sendMouseEvent(final int x, final int y, final MouseClick type) {
    final CompletableFuture<?> task = CompletableFuture.runAsync(
      () -> {
        final Actions actions = new Actions(this.driver);
        final Actions move = actions.moveToLocation(x, y);
        final Actions modified = this.getAction(type, move);
        final Action action = modified.build();
        action.perform();
      },
      this.executor
    );
    this.pendingTasks.add(task);
    task.whenComplete((result, ex) -> this.pendingTasks.remove(task));
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
      if (!this.started.get()) {
        return false;
      }
      if (this.videoTask != null) {
        this.videoTask.cancel(true);
      }
      final Command<Void> stop = Page.stopScreencast();
      this.tools.send(stop);
      this.tools.clearListeners();
      this.tools.close();
      this.started.set(false);
      this.driver.quit();
      try {
        CompletableFuture.allOf(this.pendingTasks.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
      } catch (final TimeoutException e) {
        throw new AssertionError(e);
      }
      ExecutorUtils.shutdownExecutorGracefully(this.executor);
      return true;
    }
  }
}
