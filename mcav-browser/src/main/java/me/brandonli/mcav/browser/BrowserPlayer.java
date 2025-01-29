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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.utils.interaction.MouseClick;

/**
 * Interface representing a browser-based player that supports interaction with browser elements,
 * video pipeline processing, and mouse event handling.
 */
public interface BrowserPlayer extends ReleasablePlayer, ExceptionHandler {
  /**
   * Default arguments for Selenium ChromeDriver.
   */
  String[] DEFAULT_CHROME_ARGUMENTS = { "--headless", "--disable-gpu", "--disable-software-rasterizer" };

  /**
   * Starts the browser player with the specified video pipeline step and combined browser source.
   *
   * @param combined the combined browser source that provides the video content to be played.
   * @return true if the player started successfully, false otherwise.
   */
  boolean start(final BrowserSource combined);

  /**
   * Asynchronously starts the browser player with the specified video pipeline step and combined browser source.
   *
   * @param combined the combined browser source that provides the video content to be played.
   * @param service the executor service to run the asynchronous task on.
   * @return a CompletableFuture that completes with true if the player started successfully, false otherwise.
   */
  default CompletableFuture<Boolean> startAsync(final BrowserSource combined, final ExecutorService service) {
    return CompletableFuture.supplyAsync(() -> this.start(combined), service);
  }

  /**
   * Asynchronously starts the browser player with the specified video pipeline step and combined browser source
   * using the common ForkJoinPool.
   *
   * @param combined the combined browser source that provides the video content to be played.
   * @return a CompletableFuture that completes with true if the player started successfully, false otherwise.
   */
  default CompletableFuture<Boolean> startAsync(final BrowserSource combined) {
    return this.startAsync(combined, ForkJoinPool.commonPool());
  }

  /**
   * Moves the mouse cursor to the specified coordinates.
   *
   * @param x the x-coordinate to move the mouse to.
   * @param y the y-coordinate to move the mouse to.
   */
  void moveMouse(final int x, final int y);

  /**
   * Sends a mouse event of the specified type at the given coordinates.
   *
   * @param type the type of mouse click event to send (e.g., click, double-click).
   * @param x the x-coordinate where the mouse event should occur.
   * @param y the y-coordinate where the mouse event should occur.
   */
  void sendMouseEvent(final MouseClick type, final int x, final int y);

  /**
   * Sends a key event with the specified text.
   *
   * @param text the text to be sent as a key event. This can include special characters or sequences.
   */
  void sendKeyEvent(final String text);

  /**
   * Gets the video-attachable callback associated with this player.
   *
   * @return The video-attachable callback.
   */
  VideoAttachableCallback getVideoAttachableCallback();

  /**
   * Creates a new instance of a Selenium browser using the default Chrome arguments.
   *
   * @return a Selenium browser instance configured with default Chrome arguments.
   */
  static BrowserPlayer selenium() {
    return new SeleniumPlayer(DEFAULT_CHROME_ARGUMENTS);
  }

  /**
   * Creates a new instance of a Selenium browser with the specified arguments.
   *
   * @param args the command-line arguments to configure the Selenium browser.
   * @return a Selenium browser instance configured with the provided arguments.
   */
  static BrowserPlayer selenium(final String... args) {
    return new SeleniumPlayer(args);
  }

  /**
   * Creates a new instance of a Playwright browser using the default arguments.
   *
   * @param args the command-line arguments to configure the Playwright browser.
   * @return a Playwright browser instance configured with default arguments.
   */
  static BrowserPlayer playwright(final String... args) {
    return new PlaywrightPlayer(args);
  }
}
