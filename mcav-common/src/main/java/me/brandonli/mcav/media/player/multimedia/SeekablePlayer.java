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
 * A player that can jump to a position in its media. Seeking only works for sources with a known length, such
 * as files; live streams ignore it.
 */
public interface SeekablePlayer {
  /**
   * Jumps to a position.
   *
   * @param time the position in milliseconds from the start of the media, not negative
   * @return true if the player seeked, false if nothing is playing or the source cannot be seeked
   */
  boolean seek(final long time);

  /**
   * Seeks on the common pool.
   *
   * @param time the position in milliseconds from the start of the media, not negative
   * @return a future that completes with the result of {@link #seek(long)}
   */
  default CompletableFuture<Boolean> seekAsync(final long time) {
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    return this.seekAsync(pool, time);
  }

  /**
   * Seeks on an executor.
   *
   * @param executor the executor that runs the call
   * @param time     the position in milliseconds from the start of the media, not negative
   * @return a future that completes with the result of {@link #seek(long)}
   */
  default CompletableFuture<Boolean> seekAsync(final ExecutorService executor, final long time) {
    Preconditions.checkNotNull(executor, "Executor must not be null");
    return CompletableFuture.supplyAsync(() -> this.seek(time), executor);
  }
}
