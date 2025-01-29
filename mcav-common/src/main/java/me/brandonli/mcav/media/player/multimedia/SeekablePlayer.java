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

/**
 * The {@code SeekablePlayer} interface provides functionality for seeking media playback
 * to a specific point in time. Implementations of this interface allow users to control
 * and navigate the media timeline by specifying a timestamp in milliseconds.
 * <p>
 * This interface is intended to be implemented by media players that support seeking
 * capabilities, enabling precise control over playback position.
 * <p>
 * The {@link #seek(long)} method plays a key role in enabling seek operations, returning
 * a boolean indicating the success of the operation, while also ensuring proper exception
 * handling for invalid seek requests or errors during the process.
 */
public interface SeekablePlayer {
  /**
   * Seeks the media to the specified timestamp in milliseconds. This method is used
   * to reposition the playback to a specific point in time within the media.
   *
   * @param time the position in milliseconds to seek to. This value must be a non-negative
   *             long and within the valid duration of the media.
   * @return true if the seek operation was successful; false otherwise.
   */
  boolean seek(final long time);

  /**
   * Asynchronously seeks the media to the specified timestamp in milliseconds.
   * This method allows repositioning the playback to a specific point in time within the media
   * on a background thread using the common fork-join thread pool.
   *
   * @param time the position in milliseconds to seek to. This value must be a non-negative
   *             long and within the valid duration of the media.
   * @return a {@code CompletableFuture} that completes with {@code true} if the seek operation
   * was successful, or {@code false} otherwise.
   */
  default CompletableFuture<Boolean> seekAsync(final long time) {
    return this.seekAsync(ForkJoinPool.commonPool(), time);
  }

  /**
   * Asynchronously seeks the media to the specified timestamp in milliseconds using the provided
   * {@link ExecutorService}. This method allows for non-blocking seek operations by executing
   * the {@link #seek(long)} method in a different thread managed by the specified executor.
   *
   * @param service the {@code ExecutorService} used to manage the thread executing the asynchronous seek operation
   * @param time    the position in milliseconds to seek to. Must be a non-negative value and within the valid duration of the media
   * @return a {@code CompletableFuture<Boolean>} representing the result of the asynchronous seek operation.
   * The returned future will complete with {@code true} if the seek operation was successful,
   * or {@code false} if it failed.
   */
  default CompletableFuture<Boolean> seekAsync(final ExecutorService service, final long time) {
    return CompletableFuture.supplyAsync(() -> this.seek(time), service);
  }
}
