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
package me.brandonli.mcav.media.player.vnc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.multimedia.ControllablePlayer;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.VNCSource;

/**
 * The {@code VNCPlayer} interface defines a media player for handling VNC (Virtual Network Computing) streams.
 * It extends {@link ControllablePlayer} and {@link ReleasablePlayer}, providing playback control and resource
 * management functionalities. Additionally, this interface supports interaction mechanisms such as mouse movement
 * and keyboard input simulation.
 * <p>
 * Core functionalities include:
 * - Starting a playback session with a specified video pipeline and VNC source.
 * - Simulating mouse and keyboard inputs within the VNC session.
 * - Managing playback with pause, resume, and release operations inherited from its superinterfaces.
 */
public interface VNCPlayer extends ControllablePlayer, ReleasablePlayer {
  /**
   * Starts the playback process with the specified video pipeline and VNC source.
   * This method initializes and begins the processing of video frames and metadata
   * from the provided VNC source, applying the specified video pipeline operations.
   *
   * @param videoPipeline the video pipeline to be used, consisting of processing steps
   *                      for handling video frames and metadata. Must not be null.
   * @param combined      the VNC source representing the input data, including host,
   *                      port, password, and video metadata. Must not be null.
   * @return true if the playback starts successfully, false otherwise.
   */
  boolean start(final VideoPipelineStep videoPipeline, final VNCSource combined);

  /**
   * Asynchronously starts the playback process with the specified video pipeline and VNC source.
   * This method initializes and begins the processing of video frames and metadata
   * from the provided VNC source, applying the specified video pipeline operations.
   * The execution is performed using the common ForkJoinPool.
   *
   * @param videoPipeline the video pipeline to be used, consisting of processing steps
   *                      for handling video frames and metadata. Must not be null.
   * @param combined      the VNC source representing the input data, including host,
   *                      port, password, and video metadata. Must not be null.
   * @return a {@link CompletableFuture} that completes with {@code true} if the playback
   * starts successfully, or {@code false} if it fails to start.
   */
  default CompletableFuture<Boolean> startAsync(final VideoPipelineStep videoPipeline, final VNCSource combined) {
    return this.startAsync(videoPipeline, combined, ForkJoinPool.commonPool());
  }

  /**
   * Initiates the asynchronous playback process with the specified video pipeline
   * and VNC source using the provided executor service.
   * This method begins the processing of video frames and metadata in an asynchronous
   * manner, delegating the execution to the specified executor service.
   *
   * @param videoPipeline the video pipeline to be used, consisting of processing steps
   *                      for handling video frames and metadata. Must not be null.
   * @param combined      the VNC source representing the input data, including host,
   *                      port, password, and video metadata. Must not be null.
   * @param service       the executor service responsible for executing the asynchronous
   *                      operation. Must not be null.
   * @return a CompletableFuture that completes with {@code true} if the playback starts
   * successfully, or {@code false} otherwise.
   */
  default CompletableFuture<Boolean> startAsync(
    final VideoPipelineStep videoPipeline,
    final VNCSource combined,
    final ExecutorService service
  ) {
    return CompletableFuture.supplyAsync(() -> this.start(videoPipeline, combined), service);
  }

  /**
   * Moves the mouse pointer to the specified coordinates within the VNC session.
   *
   * @param x the x-coordinate to move the mouse to, in pixels
   * @param y the y-coordinate to move the mouse to, in pixels
   */
  void moveMouse(final int x, final int y);

  /**
   * Simulates a mouse click at the specified coordinates within the VNC session.
   * This method moves the mouse to the given position and performs a press-and-release
   * action for the specified mouse button type.
   *
   * @param x    the x-coordinate to click at, in pixels
   * @param y    the y-coordinate to click at, in pixels
   * @param type the mouse button type to click, where 0 typically represents the left button,
   *             1 represents the middle button, and 2 represents the right button
   */
  default void click(final int x, final int y, final int type) {
    this.moveMouse(x, y);
    this.updateMouseButton(type, true);
    this.updateMouseButton(type, false);
  }

  /**
   * Simulates typing the provided text into the VNC session by sending corresponding keyboard input events.
   *
   * @param text the string of characters to be sent as keyboard events within the VNC session;
   *             cannot be null or empty. Each character in the string is processed individually.
   */
  void type(final String text);

  /**
   * Updates the state of a mouse button in the VNC player session.
   * This method simulates mouse button press or release actions
   * and sends the corresponding event to the VNC session.
   *
   * @param type    the mouse button type, where 0 typically represents the left button,
   *                1 represents the middle button, and 2 represents the right button.
   * @param pressed a boolean value representing the button state; true for pressed,
   *                and false for released.
   */
  void updateMouseButton(final int type, final boolean pressed);

  /**
   * Updates the state of a keyboard button in the VNC session.
   *
   * @param keyCode the code of the keyboard key to update
   * @param pressed {@code true} if the key is to be pressed, {@code false} if it is to be released
   */
  void updateKeyButton(final int keyCode, final boolean pressed);

  /**
   * Returns an instance of the {@link VNCPlayer} interface.
   * This method provides a thread-safe implementation of the VNCPlayer, enabling
   * functionality such as playback control, VNC session interaction, and resource
   * management for Virtual Network Computing streams.
   *
   * @return an instance of {@link VNCPlayer}, implemented by {@link VNCPlayerImpl}.
   */
  static VNCPlayer vm() {
    return new VNCPlayerImpl();
  }
}
