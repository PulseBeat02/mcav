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

import com.google.common.base.Preconditions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * A player whose playback can be paused and resumed.
 */
public interface ControllablePlayer {
  /**
   * Pauses playback, keeping the current position.
   *
   * @return true if playback was paused, false if nothing is playing or it was already paused
   */
  boolean pause();

  /**
   * Pauses playback on the common pool.
   *
   * @return a future that completes with the result of {@link #pause()}
   */
  default CompletableFuture<Boolean> pauseAsync() {
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    return this.pauseAsync(pool);
  }

  /**
   * Pauses playback on an executor.
   *
   * @param executor the executor that runs the call
   * @return a future that completes with the result of {@link #pause()}
   */
  default CompletableFuture<Boolean> pauseAsync(final ExecutorService executor) {
    Preconditions.checkNotNull(executor, "Executor must not be null");
    return CompletableFuture.supplyAsync(this::pause, executor);
  }

  /**
   * Resumes playback after {@link #pause()}.
   *
   * @return true if playback was resumed, false if nothing is playing or it was not paused
   */
  boolean resume();

  /**
   * Resumes playback on the common pool.
   *
   * @return a future that completes with the result of {@link #resume()}
   */
  default CompletableFuture<Boolean> resumeAsync() {
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    return this.resumeAsync(pool);
  }

  /**
   * Resumes playback on an executor.
   *
   * @param executor the executor that runs the call
   * @return a future that completes with the result of {@link #resume()}
   */
  default CompletableFuture<Boolean> resumeAsync(final ExecutorService executor) {
    Preconditions.checkNotNull(executor, "Executor must not be null");
    return CompletableFuture.supplyAsync(this::resume, executor);
  }
}
