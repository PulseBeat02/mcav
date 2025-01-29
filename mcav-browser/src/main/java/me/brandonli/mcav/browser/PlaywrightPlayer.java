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

import static java.util.Objects.requireNonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.ScreenshotType;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.*;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A Playwright-based browser player implementation that takes screenshots.
 */
public final class PlaywrightPlayer implements BrowserPlayer {

  private static final Set<String> PLAYWRIGHT_SPECIAL_KEYS;

  private static final TriConsumer<Mouse, Integer, Integer>[] MOUSE_ACTION_CONSUMERS = CollectionUtils.array(
    Mouse::click,
    (mouse, x, y) -> mouse.click(x, y, new Mouse.ClickOptions().setButton(MouseButton.RIGHT).setClickCount(1)),
    Mouse::dblclick,
    (mouse, x, y) -> mouse.down(),
    (mouse, x, y) -> mouse.up()
  );

  static {
    final Gson gson = GsonProvider.getSimple();
    try (final Reader reader = IOUtils.getResourceAsStreamReader("keybinds.json")) {
      final TypeToken<Set<String>> token = new TypeToken<>() {};
      final Type type = token.getType();
      PLAYWRIGHT_SPECIAL_KEYS = requireNonNull(gson.fromJson(reader, type));
    } catch (final IOException e) {
      throw new PlayerException(e.getMessage(), e);
    }
  }

  private final VideoAttachableCallback videoAttachableCallback;
  private final BrowserType.LaunchOptions launchOptions;
  private final ExecutorService captureExecutor;
  private final ExecutorService actionExecutor;
  private final ExecutorService tabExecutor;
  private final AtomicBoolean running;
  private final Set<String> pageIds;
  private final Lock lock;

  @Nullable private volatile Browser browser;

  @Nullable private volatile Page page;

  @Nullable private volatile Long frameWidth;

  @Nullable private volatile Long frameHeight;

  @Nullable private volatile BrowserSource source;

  private BiConsumer<String, Throwable> exceptionHandler;

  PlaywrightPlayer(final String... args) {
    final List<String> launchArgs = Arrays.asList(args);
    this.exceptionHandler = ExceptionHandler.createDefault().getExceptionHandler();
    this.videoAttachableCallback = VideoAttachableCallback.create();
    this.pageIds = Collections.synchronizedSet(new HashSet<>());
    this.running = new AtomicBoolean(false);
    this.captureExecutor = Executors.newSingleThreadExecutor();
    this.tabExecutor = Executors.newSingleThreadExecutor();
    this.actionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.lock = new ReentrantLock();
    this.launchOptions = new BrowserType.LaunchOptions().setHeadless(true).setArgs(launchArgs);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BiConsumer<String, Throwable> getExceptionHandler() {
    return this.exceptionHandler;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    this.exceptionHandler = exceptionHandler;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final BrowserSource combined) {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.createBrowser(combined);
      this.addPage(combined);
      this.startCapture(combined);
      return true;
    });
  }

  private void startTabMonitoring() {
    while (this.running.get() && this.browser != null) {
      final Browser browser = requireNonNull(this.browser);
      final List<Page> pages = browser.contexts().stream().flatMap(context -> context.pages().stream()).toList();
      if (pages.isEmpty()) {
        continue;
      }
      final Set<String> currentUrls = new HashSet<>();
      for (final Page page : pages) {
        final String url = page.url();
        currentUrls.add(url);
        this.pageIds.add(url);
      }
      this.page = pages.getLast();
      this.pageIds.removeIf(url -> !currentUrls.contains(url));
      this.waitForNextTab();
    }
  }

  private void waitForNextTab() {
    try {
      Thread.sleep(50);
    } catch (final InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      final String msg = e.getMessage();
      requireNonNull(msg);
      this.exceptionHandler.accept(msg, e);
    }
  }

  private void addPage(final BrowserSource combined) {
    final Page page = requireNonNull(this.page);
    final String rawUrl = page.url();
    this.pageIds.add(rawUrl);
    this.frameWidth = (long) combined.getScreencastWidth();
    this.frameHeight = (long) combined.getScreencastHeight();
  }

  private void createBrowser(final BrowserSource combined) {
    final int width = combined.getScreencastWidth();
    final int height = combined.getScreencastHeight();
    final Browser.NewContextOptions contextOptions = new Browser.NewContextOptions().setViewportSize(width, height);
    final Playwright playwright = PlaywrightServiceProvider.getService();
    final Browser browser = playwright.chromium().launch(this.launchOptions);
    final BrowserContext context = browser.newContext(contextOptions);
    final String url = combined.getResource();
    final Page page = context.newPage();
    page.navigate(url);
    this.page = page;
    this.browser = browser;
    this.source = combined;
  }

  private void startCapture(final BrowserSource source) {
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    final int quality = source.getScreencastQuality();
    final int frameInterval = (1000 / 30) * source.getScreencastNthFrame();
    final VideoPipelineStep videoPipeline = this.videoAttachableCallback.retrieve();
    final Page.ScreenshotOptions screenshotOptions = new Page.ScreenshotOptions()
      .setType(ScreenshotType.JPEG)
      .setQuality(quality)
      .setFullPage(false);
    this.running.set(true);
    this.captureExecutor.submit(() -> this.startScreencast(videoPipeline, screenshotOptions, width, height, frameInterval));
    this.tabExecutor.submit(this::startTabMonitoring);
  }

  private void startScreencast(
    final VideoPipelineStep videoPipeline,
    final Page.ScreenshotOptions screenshotOptions,
    final int width,
    final int height,
    final int frameInterval
  ) {
    final ResizeFilter resizeFilter = new ResizeFilter(width, height);
    try {
      while (this.running.get() && this.page != null) {
        final Page page = requireNonNull(this.page);
        final byte[] buffer = page.screenshot(screenshotOptions);
        final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(width, height);
        final ImageBuffer staticImage = ImageBuffer.bytes(buffer);
        resizeFilter.applyFilter(staticImage, metadata);
        VideoPipelineStep current = videoPipeline;
        while (current != null) {
          current.process(staticImage, metadata);
          current = current.next();
        }
        staticImage.release();
        this.waitForNextFrame(frameInterval);
      }
    } catch (final Throwable e) {
      final String msg = e.getMessage();
      requireNonNull(msg);
      this.exceptionHandler.accept(msg, e);
    }
  }

  private void waitForNextFrame(final long sleep) {
    try {
      Thread.sleep(sleep);
    } catch (final InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      final String msg = e.getMessage();
      requireNonNull(msg);
      this.exceptionHandler.accept(msg, e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void moveMouse(final int x, final int y) {
    LockUtils.executeWithLock(this.lock, () -> {
      if (!this.running.get()) {
        return;
      }
      final int[] translated = this.translateCoordinates(x, y);
      final int newX = translated[0];
      final int newY = translated[1];
      final Page page = requireNonNull(this.page);
      final Mouse mouse = page.mouse();
      this.actionExecutor.submit(() -> mouse.move(newX, newY));
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void sendMouseEvent(final MouseClick type, final int x, final int y) {
    LockUtils.executeWithLock(this.lock, () -> {
      if (!this.running.get()) {
        return;
      }
      final int id = type.getId();
      final TriConsumer<Mouse, Integer, Integer> actionConsumer = MOUSE_ACTION_CONSUMERS[id];
      final int[] translated = this.translateCoordinates(x, y);
      final Page page = requireNonNull(this.page);
      final Mouse mouse = page.mouse();
      final int newX = translated[0];
      final int newY = translated[1];
      this.actionExecutor.submit(() -> {
          mouse.move(newX, newY);
          actionConsumer.accept(mouse, newX, newY);
        });
    });
  }

  private int[] translateCoordinates(final int x, final int y) {
    final BrowserSource source = requireNonNull(this.source);
    final long width = requireNonNull(this.frameWidth);
    final long height = requireNonNull(this.frameHeight);
    final int sourceWidth = source.getScreencastWidth();
    final int sourceHeight = source.getScreencastHeight();
    final int targetWidth = (int) width;
    final int targetHeight = (int) height;
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
    LockUtils.executeWithLock(this.lock, () -> {
      if (!this.running.get()) {
        return;
      }
      final Page page = requireNonNull(this.page);
      final Keyboard keyboard = page.keyboard();
      this.actionExecutor.submit(() -> {
          if (PLAYWRIGHT_SPECIAL_KEYS.contains(text)) {
            keyboard.press(text);
            return;
          }
          keyboard.type(text);
        });
    });
  }

  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.videoAttachableCallback;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.running.set(false);
      this.pageIds.clear();
      if (this.browser != null) {
        final Browser browser = requireNonNull(this.browser);
        browser.close();
        this.browser = null;
      }
      ExecutorUtils.shutdownExecutorGracefully(this.captureExecutor);
      ExecutorUtils.shutdownExecutorGracefully(this.actionExecutor);
      ExecutorUtils.shutdownExecutorGracefully(this.tabExecutor);
      return true;
    });
  }
}
