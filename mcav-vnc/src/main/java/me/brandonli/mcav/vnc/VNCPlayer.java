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
package me.brandonli.mcav.vnc;

import com.google.common.base.Preconditions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.ControllablePlayer;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.utils.interaction.MouseClick;

/**
 * Streams the screen of a VNC server and forwards mouse and keyboard input to it.
 *
 * <p>Frames arrive whenever the remote screen changes, scaled to the size of the {@link VNCSource}. Input
 * coordinates are in the coordinate system of the streamed frames. Pausing keeps the connection open but stops
 * accepting new frames for delivery; an in-flight filter call may finish. The default player runs the video
 * pipeline on a dedicated daemon renderer, keeps only the newest waiting update and closes each image buffer
 * after its pipeline call. Filters must copy data they retain beyond that call.
 *
 * <p>The caller owns the player and must {@link #release()} it. Release is terminal and closes the connection;
 * stopping {@link VNCModule} does not release players. Attached pipelines and their filters remain caller-owned.
 * Lifecycle calls are serialized. Exception callbacks run on the thread detecting the failure and must return
 * promptly without throwing.
 *
 * <pre><code>
 *   final VNCPlayer player = VNCPlayer.create();
 *   final VideoAttachableCallback video = player.getVideoAttachableCallback();
 *   video.attach(pipeline);
 *   player.start(source);
 *   player.sendKeyEvent("Return");
 * </code></pre>
 */
public interface VNCPlayer extends ControllablePlayer, ReleasablePlayer, ExceptionHandler {
  /**
   * Creates a player.
   *
   * @return the player
   */
  static VNCPlayer create() {
    return new VNCPlayerImpl();
  }

  /**
   * Connects to a server and starts streaming its screen. A player streams one server at a time.
   *
   * @param source the non-null server description; credentials and dimensions are read from it
   * @return true if startup succeeded, false if a session is already active (including paused) or the player
   *         has been released; a session that failed may be started again before release
   * @throws me.brandonli.mcav.media.player.PlayerException if the server cannot be reached or rejects the
   * connection
   * @throws NullPointerException if {@code source} is null
   */
  boolean start(final VNCSource source);

  /**
   * Connects on the common pool.
   *
   * @param source the server to connect to
   * @return a future that completes with the result of {@link #start(VNCSource)}, or exceptionally if
   *         startup fails; cancelling this future does not release the player
   * @throws NullPointerException if {@code source} is null
   */
  default CompletableFuture<Boolean> startAsync(final VNCSource source) {
    Preconditions.checkNotNull(source, "Source must not be null");
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    return this.startAsync(source, pool);
  }

  /**
   * Connects on an executor.
   *
   * @param source   the server to connect to
   * @param executor the non-null caller-owned executor that connects; the player never shuts it down
   * @return a future that completes with the result of {@link #start(VNCSource)}, or exceptionally if
   *         startup fails; cancelling this future does not release the player
   * @throws java.util.concurrent.RejectedExecutionException if the executor refuses the startup task
   * @throws NullPointerException if {@code source} or {@code executor} is null
   */
  default CompletableFuture<Boolean> startAsync(final VNCSource source, final ExecutorService executor) {
    Preconditions.checkNotNull(source, "Source must not be null");
    Preconditions.checkNotNull(executor, "Executor must not be null");
    return CompletableFuture.supplyAsync(() -> this.start(source), executor);
  }

  /**
   * Moves the mouse pointer while a session is active, including while paused. Input is ignored before
   * connection and after release or a connection failure. Coordinates are scaled to the remote screen and
   * clamped to its bounds; before its size is known only negative coordinates are clamped to zero.
   *
   * @param frameX the horizontal pixel coordinate from the left edge of the streamed frame; out-of-range
   *               values are clamped after scaling
   * @param frameY the vertical pixel coordinate from the top edge of the streamed frame; out-of-range
   *               values are clamped after scaling
   */
  void moveMouse(final int frameX, final int frameY);

  /**
   * Types text. A key name from the X11 keysym table, such as {@code Return}, {@code Escape}, or {@code Left},
   * presses and releases that key; any other text is typed character by character. Input is ignored without
   * an active session, but is accepted while paused. Forwarding failures are reported to the exception handler.
   *
   * @param text the non-null text or key name; an empty string types nothing
   * @throws NullPointerException if {@code text} is null, even without an active connection
   */
  void sendKeyEvent(final String text);

  /**
   * Moves the mouse pointer as {@link #moveMouse(int, int)} describes, then performs a click. Left, right,
   * double-left, left-button hold and left-button release are supported. Connection failures during forwarding
   * are reported to the exception handler.
   *
   * @param type   the kind of click
   * @param frameX the horizontal pixel coordinate from the left edge of the streamed frame; out-of-range
   *               values are clamped after scaling
   * @param frameY the vertical pixel coordinate from the top edge of the streamed frame; out-of-range
   *               values are clamped after scaling
   * @throws NullPointerException if {@code type} is null, even without an active connection
   */
  void sendMouseEvent(final MouseClick type, final int frameX, final int frameY);

  /**
   * Checks whether the player is connected and delivering frames.
   *
   * @return true while connected and not paused
   */
  boolean isPlaying();

  /**
   * Gets the slot that holds the video pipeline the frames are sent through.
   *
   * @return the video pipeline slot
   */
  VideoAttachableCallback getVideoAttachableCallback();
}
