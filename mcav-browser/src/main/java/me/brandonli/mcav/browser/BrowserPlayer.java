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

import com.google.common.base.Preconditions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.utils.interaction.MouseClick;

/**
 * Streams a web page as video and forwards mouse and keyboard input to it.
 *
 * <p>Two backends exist. {@link #selenium(String...)} drives the Chrome installed on the machine through
 * ChromeDriver, which is downloaded automatically. {@link #playwright(String...)} downloads its own headless
 * Chromium through Playwright the first time it starts, which takes a while but needs no Chrome installation; on
 * Linux the system libraries Chromium depends on must be present. Both stream frames with the Chrome DevTools
 * screencast, so frames arrive only when the page changes.
 *
 * <pre>{@code
 *   final BrowserPlayer browser = BrowserPlayer.selenium();
 *   final VideoAttachableCallback video = browser.getVideoAttachableCallback();
 *   video.attach(pipeline);
 *   final URI page = URI.create("https://example.com");
 *   final BrowserSource source = BrowserSource.uri(page, 80, 1280, 720, 1);
 *   browser.start(source);
 *   browser.sendMouseEvent(MouseClick.LEFT, 640, 360);
 * }</pre>
 *
 * <p>Input coordinates are in the coordinate system of the streamed frames, so a click at the position of a
 * pixel in a frame lands on the same spot of the page.
 */
public interface BrowserPlayer extends ReleasablePlayer, ExceptionHandler {
  /**
   * The Chrome arguments used when none are specified: headless, without GPU acceleration so Chrome renders in
   * software, muted, and without scrollbars. The list cannot be modified; to pass these arguments together with
   * others, copy them into a new list or array.
   */
  List<String> DEFAULT_CHROME_ARGUMENTS = List.of("--headless=new", "--disable-gpu", "--mute-audio", "--hide-scrollbars");

  /**
   * An argument for {@link #selenium(String...)} that opens a Chrome window instead of running headless. It is not
   * passed on to Chrome. A window needs a display, which servers usually lack.
   */
  String SHOW_WINDOW = "--mcav-show-window";

  /**
   * Creates a player that drives the installed Chrome through Selenium with {@link #DEFAULT_CHROME_ARGUMENTS}.
   *
   * @return the player
   * @see #selenium(String...)
   */
  static BrowserPlayer selenium() {
    final String[] defaultArguments = DEFAULT_CHROME_ARGUMENTS.toArray(String[]::new);
    return new SeleniumPlayer(defaultArguments);
  }

  /**
   * Creates a player that drives the installed Chrome through Selenium.
   *
   * <p>The arguments replace {@link #DEFAULT_CHROME_ARGUMENTS}, with two exceptions that keep Chrome working on
   * headless servers. First, {@code --headless=new} is kept unless an argument chooses a headless mode itself, such as
   * {@code --headless=old}, or is {@link #SHOW_WINDOW}. Second, on Linux {@code --disable-dev-shm-usage} is added, and
   * {@code --no-sandbox} too when the process runs as root or in a Docker or Podman container, where Chrome cannot
   * start with its sandbox.
   *
   * @param args the Chrome command-line arguments
   * @return the player
   */
  static BrowserPlayer selenium(final String... args) {
    Preconditions.checkNotNull(args, "Arguments must not be null");
    return new SeleniumPlayer(args);
  }

  /**
   * Creates a player that drives a Chromium downloaded by Playwright. Chromium always runs headless.
   *
   * @param args extra Chromium command-line arguments
   * @return the player
   */
  static BrowserPlayer playwright(final String... args) {
    Preconditions.checkNotNull(args, "Arguments must not be null");
    return new PlaywrightPlayer(args);
  }

  /**
   * Opens a page and starts streaming it. A player streams one page at a time; new tabs opened by the page are
   * followed automatically.
   *
   * @param source the page and the screencast settings
   * @return true if streaming started, false if the player is already playing or released
   * @throws me.brandonli.mcav.media.player.PlayerException if the browser cannot be started
   */
  boolean start(final BrowserSource source);

  /**
   * Starts streaming on an executor.
   *
   * @param source   the page and the screencast settings
   * @param executor the executor that starts the browser
   * @return a future that completes with the result of {@link #start(BrowserSource)}
   */
  default CompletableFuture<Boolean> startAsync(final BrowserSource source, final ExecutorService executor) {
    Preconditions.checkNotNull(source, "Source must not be null");
    Preconditions.checkNotNull(executor, "Executor must not be null");
    return CompletableFuture.supplyAsync(() -> this.start(source), executor);
  }

  /**
   * Starts streaming on the common pool.
   *
   * @param source the page and the screencast settings
   * @return a future that completes with the result of {@link #start(BrowserSource)}
   */
  default CompletableFuture<Boolean> startAsync(final BrowserSource source) {
    Preconditions.checkNotNull(source, "Source must not be null");
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    return this.startAsync(source, pool);
  }

  /**
   * Moves the mouse pointer.
   *
   * @param x the x coordinate in the streamed frame
   * @param y the y coordinate in the streamed frame
   */
  void moveMouse(final int x, final int y);

  /**
   * Moves the mouse pointer and performs a click.
   *
   * @param type the kind of click
   * @param x    the x coordinate in the streamed frame
   * @param y    the y coordinate in the streamed frame
   */
  void sendMouseEvent(final MouseClick type, final int x, final int y);

  /**
   * Types text into the focused element. Special keys are typed by their name, such as {@code Enter} or
   * {@code ArrowLeft}; any other text is typed character by character.
   *
   * @param text the text or key name
   */
  void sendKeyEvent(final String text);

  /**
   * Checks whether a page is being streamed.
   *
   * @return true between a successful {@link #start(BrowserSource)} and {@link #release()}
   */
  boolean isPlaying();

  /**
   * Gets the slot that holds the video pipeline the frames are sent through.
   *
   * @return the video pipeline slot
   */
  VideoAttachableCallback getVideoAttachableCallback();
}
