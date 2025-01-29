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
package me.brandonli.mcav.media.player.multimedia;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * An interface for media players that can be controlled to pause and resume playback.
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
   *
   * @return a {@code CompletableFuture} representing the asynchronous operation, which completes
   * with {@code true} if the media playback is successfully paused, or {@code false} otherwise.
   */
  default CompletableFuture<Boolean> pauseAsync() {
    return this.pauseAsync(ForkJoinPool.commonPool());
  }

  /**
   * Pauses media playback asynchronously using the provided {@link ExecutorService}..
   *
   * @param executor the {@link ExecutorService} to be used for executing the asynchronous operation
   * @return a {@code CompletableFuture} that completes with {@code true} if the media playback
   * is successfully paused, or {@code false} otherwise
   */
  default CompletableFuture<Boolean> pauseAsync(final ExecutorService executor) {
    return CompletableFuture.supplyAsync(this::pause, executor);
  }

  /**
   * Resumes the playback of the media from the current timestamp.
   *
   * @return {@code true} if the playback was successfully resumed, {@code false} otherwise.
   */
  boolean resume();

  /**
   * Resumes playback of the media asynchronously using a default thread pool.
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
   *
   * @param executor the {@link ExecutorService} to be used for executing the asynchronous operation
   * @return a {@link CompletableFuture} that completes with {@code true} if playback was successfully resumed,
   * or {@code false} if it failed
   */
  default CompletableFuture<Boolean> resumeAsync(final ExecutorService executor) {
    return CompletableFuture.supplyAsync(this::resume, executor);
  }
}
