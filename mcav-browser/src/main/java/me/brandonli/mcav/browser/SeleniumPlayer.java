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

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.CollectionUtils;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.LockUtils;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.Command;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.Event;
import org.openqa.selenium.devtools.v139.page.Page;
import org.openqa.selenium.devtools.v139.page.model.ScreencastFrame;
import org.openqa.selenium.devtools.v139.page.model.ScreencastFrameMetadata;
import org.openqa.selenium.interactions.Action;
import org.openqa.selenium.interactions.Actions;

/**
 * A Selenium-based browser player that captures screencasts.
 */
public final class SeleniumPlayer implements BrowserPlayer {

  private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();
  private static final Function<Actions, Actions>[] MOUSE_ACTION_CONSUMERS = CollectionUtils.array(
    Actions::click,
    Actions::contextClick,
    Actions::doubleClick,
    Actions::clickAndHold,
    Actions::release
  );

  private final VideoAttachableCallback videoAttachableCallback;
  private final ExecutorService captureExecutor;
  private final Set<String> handles;
  private final ExecutorService actionExecutor;
  private final ExecutorService tabExecutor;
  private final ChromeDriver driver;
  private final AtomicBoolean running;
  private final DevTools tools;
  private final Lock lock;

  @Nullable private volatile Long frameWidth;

  @Nullable private volatile Long frameHeight;

  @Nullable private volatile BrowserSource source;

  private BiConsumer<String, Throwable> exceptionHandler;

  SeleniumPlayer(final String... args) {
    final ChromeDriverService service = ChromeDriverServiceProvider.getService();
    final ChromeOptions options = new ChromeOptions().addArguments(args);
    this.exceptionHandler = ExceptionHandler.createDefault().getExceptionHandler();
    this.videoAttachableCallback = VideoAttachableCallback.create();
    this.driver = new ChromeDriver(service, options);
    this.handles = Collections.synchronizedSet(new HashSet<>());
    this.running = new AtomicBoolean(false);
    this.captureExecutor = Executors.newSingleThreadExecutor();
    this.tabExecutor = Executors.newSingleThreadExecutor();
    this.actionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.tools = this.driver.getDevTools();
    this.lock = new ReentrantLock();
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
      this.source = combined;
      this.running.set(true);
      this.maximizeWindow(combined);
      this.addScreencastListener(combined);
      this.startScreencast(combined);
      return true;
    });
  }

  private void startScreencast(final BrowserSource source) {
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    final int qualityRaw = source.getScreencastQuality();
    final int nthRaw = source.getScreencastNthFrame();
    final Optional<Page.StartScreencastFormat> format = Optional.of(Page.StartScreencastFormat.JPEG);
    final Optional<Integer> quality = Optional.of(qualityRaw);
    final Optional<Integer> maxWidth = Optional.of(width);
    final Optional<Integer> maxHeight = Optional.of(height);
    final Optional<Integer> everyNthFrame = Optional.of(nthRaw);
    this.tools.send(Page.startScreencast(format, quality, maxWidth, maxHeight, everyNthFrame));

    final String handle = this.driver.getWindowHandle();
    this.handles.add(handle);
    this.tabExecutor.submit(this::startTabMonitoring);
  }

  private void addScreencastListener(final BrowserSource source) {
    final String resource = source.getResource();
    final Event<ScreencastFrame> screencastFrameEvent = Page.screencastFrame();
    this.driver.get(resource);
    this.tools.createSession();
    this.tools.addListener(screencastFrameEvent, this::handleFrame);
  }

  private void maximizeWindow(final BrowserSource source) {
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    final Dimension size = new Dimension(width, height);
    final WebDriver.Options options = this.driver.manage();
    final WebDriver.Window window = options.window();
    window.setSize(size);
    window.maximize();
  }

  private void startTabMonitoring() {
    while (this.running.get()) {
      final Set<String> currentHandles = this.driver.getWindowHandles();
      for (final String handle : currentHandles) {
        if (this.handles.contains(handle)) {
          continue;
        }
        this.handles.add(handle);
        this.resetDevToolsSession(handle);
        break;
      }
      this.waitForTabReset();
    }
  }

  private void waitForTabReset() {
    try {
      Thread.sleep(50);
    } catch (final InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  private void resetDevToolsSession(final String newHandle) {
    if (!this.running.get() || this.source == null) {
      return;
    }

    final WebDriver.TargetLocator targetLocator = this.driver.switchTo();
    targetLocator.window(newHandle);

    final BrowserSource source = requireNonNull(this.source);
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    final int qualityRaw = source.getScreencastQuality();
    final int nthRaw = source.getScreencastNthFrame();
    final Optional<Page.StartScreencastFormat> format = Optional.of(Page.StartScreencastFormat.JPEG);
    final Optional<Integer> quality = Optional.of(qualityRaw);
    final Optional<Integer> maxWidth = Optional.of(width);
    final Optional<Integer> maxHeight = Optional.of(height);
    final Optional<Integer> everyNthFrame = Optional.of(nthRaw);
    final Event<ScreencastFrame> screencastFrameEvent = Page.screencastFrame();
    final Command<Void> startScreencast = Page.startScreencast(format, quality, maxWidth, maxHeight, everyNthFrame);
    this.tools.clearListeners();
    this.tools.close();
    this.tools.createSession();
    this.tools.addListener(screencastFrameEvent, this::handleFrameSend);
    this.tools.send(startScreencast);
  }

  private void handleFrameSend(final ScreencastFrame frame) {
    this.captureExecutor.submit(() -> this.handleFrame(frame));
  }

  private void handleFrame(final ScreencastFrame frame) {
    if (!this.running.get() || !this.lock.tryLock() || this.source == null) {
      return;
    }

    final BrowserSource source = requireNonNull(this.source);
    final ScreencastFrameMetadata frameMetadata = frame.getMetadata();
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    this.frameWidth = (long) frameMetadata.getDeviceWidth();
    this.frameHeight = (long) frameMetadata.getDeviceHeight();

    final String data = frame.getData();
    final byte[] buffer = BASE64_DECODER.decode(data);
    if (buffer == null) {
      return;
    }

    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(width, height);
    final ImageBuffer staticImage = ImageBuffer.bytes(buffer);
    final ResizeFilter resizeFilter = new ResizeFilter(width, height);
    resizeFilter.applyFilter(staticImage, metadata);

    VideoPipelineStep current = this.videoAttachableCallback.retrieve();
    while (current != null) {
      current.process(staticImage, metadata);
      current = current.next();
    }

    staticImage.release();

    final int id = frame.getSessionId();
    final Command<Void> event = Page.screencastFrameAck(id);
    this.tools.send(event);
    this.lock.unlock();
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
      final Actions actions = new Actions(this.driver);
      final Actions move = actions.moveToLocation(newX, newY);
      final Action action = move.build();
      this.actionExecutor.submit(action::perform);
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
      final int[] translated = this.translateCoordinates(x, y);
      final int newX = translated[0];
      final int newY = translated[1];
      final Actions actions = new Actions(this.driver);
      final Actions move = actions.moveToLocation(newX, newY);
      final int index = type.getId();
      final Actions modified = MOUSE_ACTION_CONSUMERS[index].apply(move);
      final Action action = modified.build();
      this.actionExecutor.submit(action::perform);
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
      final Actions actions = new Actions(this.driver);
      final Actions move = actions.sendKeys(text);
      final Action action = move.build();
      this.actionExecutor.submit(action::perform);
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
      this.tools.close();
      this.handles.clear();
      this.running.set(false);
      ExecutorUtils.shutdownExecutorGracefully(this.captureExecutor);
      ExecutorUtils.shutdownExecutorGracefully(this.actionExecutor);
      ExecutorUtils.shutdownExecutorGracefully(this.tabExecutor);
      return true;
    });
  }
}
