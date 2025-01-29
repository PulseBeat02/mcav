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
package me.brandonli.mcav.vnc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.multimedia.ControllablePlayer;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;

/**
 * An interface representing a VNC (Virtual Network Computing) player, useful for handling virtual desktops, etc.
 */
public interface VNCPlayer extends ControllablePlayer, ReleasablePlayer {
  /**
   * Starts the playback process with the specified video pipeline and VNC source.
   *
   * @param videoPipeline the video pipeline to be used, consisting of processing steps
   * @param combined the VNC source representing the input data, including host
   * @return {@code true} if the playback starts successfully, or {@code false} if it fails to start.
   */
  boolean start(final VideoPipelineStep videoPipeline, final VNCSource combined);

  /**
   * Asynchronously initiates the playback process with the specified video pipeline with a ForkJoinPool.
   *
   * @param videoPipeline the video pipeline to be used, consisting of processing steps
   * @param combined the VNC source representing the input data
   * @return a CompletableFuture that completes with {@code true} if the playback starts
   */
  default CompletableFuture<Boolean> startAsync(final VideoPipelineStep videoPipeline, final VNCSource combined) {
    return this.startAsync(videoPipeline, combined, ForkJoinPool.commonPool());
  }

  /**
   * Asynchronously initiates the playback process with the specified video pipeline
   *
   * @param videoPipeline the video pipeline to be used, consisting of processing steps
   * @param combined the VNC source representing the input data
   * @param service the ExecutorService to run the task on, allowing for custom thread management
   * @return a CompletableFuture that completes with {@code true} if the playback starts
   */
  default CompletableFuture<Boolean> startAsync(
    final VideoPipelineStep videoPipeline,
    final VNCSource combined,
    final ExecutorService service
  ) {
    return CompletableFuture.supplyAsync(() -> this.start(videoPipeline, combined), service);
  }

  /**
   * Moves the mouse to the specified coordinates.
   *
   * @param x the x-coordinate to move the mouse to
   * @param y the y-coordinate to move the mouse to
   */
  void moveMouse(final int x, final int y);

  /**
   * Sends a key event with the specified text.
   *
   * @param text the text to send as a key event
   */
  void sendKeyEvent(final String text);

  /**
   * Sends a mouse event of the specified type at the given coordinates.
   *
   * @param type the type of mouse click (e.g., left click, right click)
   * @param x the x-coordinate where the mouse event occurs
   * @param y the y-coordinate where the mouse event occurs
   */
  void sendMouseEvent(final MouseClick type, final int x, final int y);

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
