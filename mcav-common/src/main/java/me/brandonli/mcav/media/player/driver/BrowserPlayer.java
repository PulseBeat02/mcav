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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.BrowserSource;

/**
 * Interface representing a browser-based player that supports interaction with browser elements,
 * video pipeline processing, and mouse event handling.
 * Extends the functionality of {@link ReleasablePlayer}.
 */
public interface BrowserPlayer extends ReleasablePlayer {
  String[] DEFAULT_CHROME_ARGUMENTS = {
    "--no-sandbox",
    "--headless",
    "--disable-gpu",
    "--disable-software-rasterizer",
    "--disable-dev-shm-usage",
    "--disable-extensions",
    "--disable-background-networking",
    "--disable-background-timer-throttling",
    "--disable-backgrounding-occluded-windows",
    "--disable-breakpad",
    "--disable-client-side-phishing-detection",
    "--disable-default-apps",
    "--disable-hang-monitor",
    "--disable-popup-blocking",
    "--disable-prompt-on-repost",
    "--disable-renderer-backgrounding",
    "--disable-sync",
    "--disable-translate",
    "--metrics-recording-only",
    "--no-first-run",
    "--safebrowsing-disable-auto-update",
    "--enable-automation",
    "--password-store=basic",
    "--use-mock-keychain",
    "--start-maximized",
    "--start-fullscreen",
  };

  /**
   * Starts the browser player with the provided video pipeline step and browser source.
   *
   * @param videoPipeline the video processing pipeline to be applied to the browser's video output.
   * @param combined      the browser source that contains the video metadata and URI information.
   * @return true if the browser player starts successfully, false otherwise.
   */
  boolean start(final VideoPipelineStep videoPipeline, final BrowserSource combined);

  /**
   * Starts the browser player asynchronously with the provided video pipeline step,
   * browser source, and an executor service for handling the asynchronous task.
   *
   * @param videoPipeline the video processing pipeline to be applied to the browser's video output
   * @param combined      the browser source that contains the video metadata and URI information
   * @param service       the executor service to be used for running the asynchronous task
   * @return a CompletableFuture that resolves to true if the browser player starts successfully, false otherwise
   */
  default CompletableFuture<Boolean> startAsync(
    final VideoPipelineStep videoPipeline,
    final BrowserSource combined,
    final ExecutorService service
  ) {
    return CompletableFuture.supplyAsync(() -> this.start(videoPipeline, combined), service);
  }

  /**
   * Starts the browser player asynchronously using the provided video pipeline step
   * and browser source. The method runs with a default asynchronous execution
   * using the common fork/join pool.
   *
   * @param videoPipeline the video processing pipeline to be applied to the browser's video output
   * @param combined      the browser source containing the video metadata and URI information
   * @return a CompletableFuture that resolves to true if the browser player starts successfully,
   * or false otherwise
   */
  default CompletableFuture<Boolean> startAsync(final VideoPipelineStep videoPipeline, final BrowserSource combined) {
    return this.startAsync(videoPipeline, combined, ForkJoinPool.commonPool());
  }

  /**
   * Sends a mouse event at the specified coordinates with the specified mouse click type.
   *
   * @param x    the x-coordinate where the mouse event should occur
   * @param y    the y-coordinate where the mouse event should occur
   * @param type the type of mouse click to be performed, represented by the {@link MouseClick} enum
   */
  void sendMouseEvent(final int x, final int y, final MouseClick type);

  /**
   * Creates a default instance of {@code ChromeDriverPlayer} with pre-defined arguments to
   * configure the Chrome browser in a headless environment.
   * The default arguments include configurations for performance, security, and resource optimization.
   *
   * @return a {@code BrowserPlayer} instance configured with default Chrome arguments.
   */
  static BrowserPlayer defaultChrome() {
    return new ChromeDriverPlayer(DEFAULT_CHROME_ARGUMENTS);
  }

  /**
   * Creates a new instance of a {@link BrowserPlayer} using the given arguments for the Chrome browser.
   *
   * @param args the arguments to be passed to the Chrome browser instance. These arguments
   *             can be used to customize browser behavior, such as enabling or disabling features.
   *             If no arguments are provided, default Chrome arguments can be used via {@link BrowserPlayer#defaultChrome()}.
   * @return a {@link BrowserPlayer} instance configured with the specified Chrome arguments.
   */
  static BrowserPlayer chrome(final String... args) {
    return new ChromeDriverPlayer(args);
  }
}
