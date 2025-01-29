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
package me.brandonli.mcav.media.player.multimedia;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;

/**
 * The {@code ControllablePlayer} interface defines control mechanisms for media players
 * that support seeking, pausing, and resuming playback operations. It extends the
 * {@link ReleasablePlayer} interface to integrate release functionality.
 * <p>
 * Implementations of this interface allow greater flexibility in playback control,
 * enabling users to interact with the media at a precise level.
 * <p>
 * The key control methods provided include:
 * - {@code seek(long time)}: Allows seeking to a specific point in the media timeline.
 * - {@code pause()}: Temporarily halts playback while maintaining the current position.
 * - {@code resume()}: Resumes playback from the paused position.
 * Each method may throw an exception if the operation fails or is unsupported.
 */
public interface ControllablePlayer {
  /**
   * Pauses media playback if it is currently playing.
   *
   * @return true if the media playback is successfully paused; false otherwise.
   */
  boolean pause();

  /**
   * Asynchronously pauses media playback using the default {@link ForkJoinPool#commonPool()} executor.
   * This method is a non-blocking, asynchronous version of the {@code pause()} method, allowing the
   * pause operation to be executed in a separate thread.
   *
   * @return a {@code CompletableFuture} representing the asynchronous operation, which completes
   * with {@code true} if the media playback is successfully paused, or {@code false} otherwise.
   */
  default CompletableFuture<Boolean> pauseAsync() {
    return this.pauseAsync(ForkJoinPool.commonPool());
  }

  /**
   * Pauses media playback asynchronously using the provided {@link ExecutorService}.
   * This method is a non-blocking, asynchronous version of the {@code pause()} method,
   * allowing media playback to be paused in the background without blocking the main thread.
   *
   * @param executor the {@link ExecutorService} to be used for executing the asynchronous operation
   * @return a {@code CompletableFuture} that completes with {@code true} if the media playback
   * is successfully paused, or {@code false} otherwise
   */
  default CompletableFuture<Boolean> pauseAsync(final ExecutorService executor) {
    return CompletableFuture.supplyAsync(this::pause, executor);
  }

  /**
   * Resumes the playback of the media from the current timestamp. If the player has been
   * paused or stopped, it will reinitialize necessary components and start processing
   * frames for audio and/or video output.
   * <p>
   * The method ensures thread safety by synchronizing access to shared resources and
   * handles the starting of frame processing in either single or multiplexer mode
   * depending on the presence of audio data.
   *
   * @return {@code true} if the playback was successfully resumed, {@code false} otherwise.
   */
  boolean resume();

  /**
   * Resumes playback of the media asynchronously using a default thread pool.
   * <p>
   * This method is a non-blocking, asynchronous version of the {@code resume()} method
   * that utilizes the {@code ForkJoinPool.commonPool()} for execution. It allows
   * the playback to resume in the background without blocking the main thread.
   *
   * @return a {@code CompletableFuture} representing the asynchronous operation,
   * which completes with {@code true} if the playback resumes successfully,
   * or {@code false} otherwise.
   */
  default CompletableFuture<Boolean> resumeAsync() {
    return this.resumeAsync(ForkJoinPool.commonPool());
  }

  /**
   * Asynchronously resumes playback of media using a provided {@link ExecutorService}.
   * The resume operation is executed in a separate thread specified by the given executor.
   *
   * @param executor the {@link ExecutorService} to be used for executing the asynchronous operation
   * @return a {@link CompletableFuture} that completes with {@code true} if playback was successfully resumed,
   * or {@code false} if it failed
   */
  default CompletableFuture<Boolean> resumeAsync(final ExecutorService executor) {
    return CompletableFuture.supplyAsync(this::resume, executor);
  }
}
