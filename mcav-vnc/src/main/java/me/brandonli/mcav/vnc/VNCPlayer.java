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
 * delivering frames.
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
   * @param source the server to connect to
   * @return true if the connection was made, false if the player is already playing or released
   * @throws me.brandonli.mcav.media.player.PlayerException if the server cannot be reached or rejects the
   * connection
   */
  boolean start(final VNCSource source);

  /**
   * Connects on the common pool.
   *
   * @param source the server to connect to
   * @return a future that completes with the result of {@link #start(VNCSource)}
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
   * @param executor the executor that connects
   * @return a future that completes with the result of {@link #start(VNCSource)}
   */
  default CompletableFuture<Boolean> startAsync(final VNCSource source, final ExecutorService executor) {
    Preconditions.checkNotNull(source, "Source must not be null");
    Preconditions.checkNotNull(executor, "Executor must not be null");
    return CompletableFuture.supplyAsync(() -> this.start(source), executor);
  }

  /**
   * Moves the mouse pointer.
   *
   * @param x the x coordinate in the streamed frame
   * @param y the y coordinate in the streamed frame
   */
  void moveMouse(final int x, final int y);

  /**
   * Types text. A key name from the X11 keysym table, such as {@code Return}, {@code Escape}, or {@code Left},
   * presses that key; any other text is typed character by character.
   *
   * @param text the text or key name
   */
  void sendKeyEvent(final String text);

  /**
   * Moves the mouse pointer and performs a click.
   *
   * @param type the kind of click
   * @param x    the x coordinate in the streamed frame
   * @param y    the y coordinate in the streamed frame
   */
  void sendMouseEvent(final MouseClick type, final int x, final int y);

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
