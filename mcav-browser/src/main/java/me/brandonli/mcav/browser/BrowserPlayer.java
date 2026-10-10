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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.utils.interaction.MouseClick;

/**
 * Streams a web page as video and forwards mouse and keyboard input to it.
 *
 * <p>The page is rendered by Chromium through JCEF, the Java binding of the Chromium Embedded Framework, in a helper
 * process that mcav starts for every started player, so the server's JVM needs no options at all and a crash of the
 * browser never takes the server with it. Chromium hands every painted frame over as plain pixels, so frames arrive
 * only when the page changes and need no image decoding. The first start on a machine downloads the CEF build for it
 * (about 150 MB, from Maven Central, verified against a pinned hash) into mcav's cache folder. On Linux the browser
 * needs no X server and nothing installed but a full Java runtime: it draws on Chromium's headless platform, and the
 * first start also downloads the shared libraries CEF needs that the server lacks (about 13 MB of Debian 11 packages,
 * each verified against a pinned hash), which only the browser's own process uses.
 *
 * <p>The sound the page plays through Web Audio and its audio and video elements arrives at the audio pipeline (see
 * {@link #getAudioAttachableCallback()}); sound of frames of another site, which Chromium runs in another process, of
 * media from another site that does not allow it (CORS), and of protected media, stays silent.
 *
 * <pre>{@code
 *   final BrowserPlayer browser = BrowserPlayer.create();
 *   final VideoAttachableCallback video = browser.getVideoAttachableCallback();
 *   video.attach(pipeline);
 *   final URI page = URI.create("https://example.com");
 *   final BrowserSource source = BrowserSource.uri(page, 1280, 720, 1);
 *   browser.start(source);
 *   browser.sendMouseEvent(MouseClick.LEFT, 640, 360);
 * }</pre>
 *
 * <p>The page is untrusted content running without the Chromium sandbox, which JCEF cannot use, so the browser only
 * shows {@code http} and {@code https} pages, opens popups in place, refuses downloads, file choosers and external
 * programs, dismisses JavaScript dialogs, and by default reaches public addresses only and runs JavaScript without the
 * just-in-time compiler (see {@link BrowserOptions}).
 *
 * <p>Input coordinates are pixels of the page, which has the size of the frames, so a click at the position of a
 * pixel in a frame lands on the same spot of the page. Coordinates outside the page are clamped to its edges.
 * Input is queued without waiting for the helper; when its queue is full, input is dropped and reported to
 * the exception handler with rate limiting. Input sent before startup, after failure or after release is ignored.
 *
 * <p>Start and release are serialized by the default player. The caller must release each player when finished;
 * release is terminal, while a player whose helper failed can be started again. {@link BrowserModule#stop()}
 * also closes registered helpers and blocks new ones until module startup. Attached filters remain caller-owned.
 * Video and audio callbacks run on background threads; images are closed after the video callback, so copy
 * anything retained beyond the callback. Exception handlers must be fast and must not throw.
 */
public interface BrowserPlayer extends ReleasablePlayer, ExceptionHandler {
  /**
   * Checks whether the browser can run on the operating system and processor of this machine: there are CEF builds for
   * 64-bit Windows, Linux and macOS, on x86-64 and ARM processors. A start on such a machine can still fail with a
   * {@link BrowserUnavailableException}, for example when the download fails.
   *
   * @return true if there is a CEF build for this machine
   */
  static boolean isSupported() {
    return JcefNatives.isSupported();
  }

  /**
   * Creates a player with the {@link BrowserOptions#DEFAULT default options}.
   *
   * @return the player
   */
  static BrowserPlayer create() {
    return create(BrowserOptions.DEFAULT);
  }

  /**
   * Creates a player.
   *
   * @param options the non-null immutable policy and frame-rate options
   * @return a new idle player; native installation and helper startup are deferred to {@link #start(BrowserSource)}
   * @throws NullPointerException if {@code options} is null
   */
  static BrowserPlayer create(final BrowserOptions options) {
    Preconditions.checkNotNull(options, "Options must not be null");
    return new CefBrowserPlayer(options);
  }

  /**
   * Opens a page and starts streaming it, and returns once the page has loaded and its first frame arrived. A player
   * streams one page at a time; pages the page opens in new windows are opened in its place. The first start on a
   * machine also downloads the CEF build for it.
   *
   * @param source the non-null page address, output dimensions and painted-frame interval
   * @return true if streaming started; false if already playing or released, or if the helper ended
   *         while startup was being handed over to the player (also reported to the exception handler)
   * @throws me.brandonli.mcav.media.player.PlayerException if the browser cannot be installed or started, or the page
   *                                                        cannot be loaded, or the browser module is stopped
   * @throws NullPointerException if {@code source} is null
   */
  boolean start(final BrowserSource source);

  /**
   * Starts streaming on an executor.
   *
   * @param source   the page and its size
   * @param executor the non-null caller-owned executor that starts the browser; the player never shuts it down
   * @return a future that completes with the startup result, or exceptionally if startup throws; cancelling
   *         the future does not release the player or stop its helper
   * @throws java.util.concurrent.RejectedExecutionException if the executor refuses the startup task
   * @throws NullPointerException if {@code source} or {@code executor} is null
   */
  default CompletableFuture<Boolean> startAsync(final BrowserSource source, final ExecutorService executor) {
    Preconditions.checkNotNull(source, "Source must not be null");
    Preconditions.checkNotNull(executor, "Executor must not be null");
    return CompletableFuture.supplyAsync(() -> this.start(source), executor);
  }

  /**
   * Starts streaming on the common pool.
   *
   * @param source the page and its size
   * @return a future that completes with the startup result, or exceptionally if startup throws; cancelling
   *         the future does not release the player or stop its helper
   * @throws NullPointerException if {@code source} is null
   */
  default CompletableFuture<Boolean> startAsync(final BrowserSource source) {
    Preconditions.checkNotNull(source, "Source must not be null");
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    return this.startAsync(source, pool);
  }

  /**
   * Moves the mouse pointer.
   *
   * @param pageX the horizontal pixel coordinate from the left edge, clamped to the page
   * @param pageY the vertical pixel coordinate from the top edge, clamped to the page
   */
  void moveMouse(final int pageX, final int pageY);

  /**
   * Moves the mouse pointer and performs a click.
   *
   * @param type  the kind of click
   * @param pageX the horizontal pixel coordinate from the left edge, clamped to the page
   * @param pageY the vertical pixel coordinate from the top edge, clamped to the page
   * @throws NullPointerException if {@code type} is null, even while not playing
   */
  void sendMouseEvent(final MouseClick type, final int pageX, final int pageY);

  /**
   * Turns the mouse wheel over a position, which scrolls what is under the pointer.
   *
   * @param pageX  the horizontal pixel coordinate from the left edge, clamped to the page
   * @param pageY  the vertical pixel coordinate from the top edge, clamped to the page
   * @param deltaX how far to scroll right in pixels, negative to scroll left; clamped to -32768 through 32767
   * @param deltaY how far to scroll down in pixels, negative to scroll up; clamped to -32768 through 32767
   */
  void scroll(final int pageX, final int pageY, final int deltaX, final int deltaY);

  /**
   * Types text into the focused element. Special keys are pressed by their W3C name, such as {@code Enter},
   * {@code PageDown} or {@code ArrowLeft}; any other text is typed character by character.
   *
   * @param text the non-null text or key name; long text is split into protocol messages in order
   * @throws NullPointerException if {@code text} is null, even while not playing
   */
  void sendKeyEvent(final String text);

  /**
   * Checks whether a page is being streamed.
   *
   * @return true between a successful {@link #start(BrowserSource)} and {@link #release()} or the loss of the browser
   */
  boolean isPlaying();

  /**
   * Gets the slot that holds the video pipeline the frames are sent through.
   *
   * @return the video pipeline slot
   */
  VideoAttachableCallback getVideoAttachableCallback();

  /**
   * Gets the slot that holds the audio pipeline the sound of the page is sent through, as 16-bit little-endian stereo
   * samples at 48 kHz like the sound of every mcav player. As in a desktop browser, a page may play sound only once
   * someone clicked or typed into it, such as a player who clicks the screen, unless {@link BrowserOptions#isAutoplay()};
   * mcav holds the sound of every page back until a press on that page as well, so a page written to get around the
   * browser's rule plays nothing earlier. Nothing plays on the server's speakers.
   *
   * @return the audio pipeline slot
   */
  AudioAttachableCallback getAudioAttachableCallback();
}
