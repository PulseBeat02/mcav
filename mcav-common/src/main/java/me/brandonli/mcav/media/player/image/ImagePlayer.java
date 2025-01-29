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
package me.brandonli.mcav.media.player.image;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.source.FrameSource;

/**
 * An interface for image players that can play images from a source.
 */
public interface ImagePlayer extends ReleasablePlayer, ExceptionHandler {
  /**
   * Starts the image player with the given video pipeline step and frame source.
   *
   * @param source        The frame source to read images from.
   * @return true if the player started successfully, false otherwise.
   */
  boolean start(final FrameSource source);

  /**
   * Starts the image player asynchronously with the given video pipeline step, frame source and executor service.
   *
   * @param source        The frame source to read images from.
   * @param service       The executor service to run the task on.
   * @return A CompletableFuture that completes with true if the player started successfully, false otherwise.
   */
  default CompletableFuture<Boolean> startAsync(final FrameSource source, final ExecutorService service) {
    return CompletableFuture.supplyAsync(() -> this.start(source), service);
  }

  /**
   * Starts the image player asynchronously with the given video pipeline step and frame source.
   *
   * @param source        The frame source to read images from.
   * @return A CompletableFuture that completes with true if the player started successfully, false otherwise.
   */
  default CompletableFuture<Boolean> startAsync(final FrameSource source) {
    return this.startAsync(source, ForkJoinPool.commonPool());
  }

  /**
   * Creates a new instance of the ImagePlayer implementation.
   *
   * @return A new ImagePlayer instance.
   */
  static ImagePlayer player() {
    return new ImagePlayerImpl();
  }

  /**
   * Gets the video-attachable callback associated with this player.
   *
   * @return The video-attachable callback.
   */
  VideoAttachableCallback getVideoAttachableCallback();
}
