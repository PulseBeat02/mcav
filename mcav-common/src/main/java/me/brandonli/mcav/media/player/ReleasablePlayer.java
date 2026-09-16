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
package me.brandonli.mcav.media.player;

import com.google.common.base.Preconditions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * A player that owns threads, native handles, or network connections which must be released when the player is
 * no longer needed. Releasing stops playback; a released player cannot be started again.
 */
@FunctionalInterface
public interface ReleasablePlayer {
  /**
   * Stops playback and releases every resource of the player. Calling it again has no effect.
   *
   * @return true if the player was released, false if it was already released
   */
  boolean release();

  /**
   * Releases the player on the common pool.
   *
   * @return a future that completes with the result of {@link #release()}
   */
  default CompletableFuture<Boolean> releaseAsync() {
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    return this.releaseAsync(pool);
  }

  /**
   * Releases the player on an executor.
   *
   * @param executor the executor that runs the release
   * @return a future that completes with the result of {@link #release()}
   */
  default CompletableFuture<Boolean> releaseAsync(final ExecutorService executor) {
    Preconditions.checkNotNull(executor, "Executor must not be null");
    return CompletableFuture.supplyAsync(this::release, executor);
  }
}
