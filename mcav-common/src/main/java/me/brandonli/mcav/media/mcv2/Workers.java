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
package me.brandonli.mcav.media.mcv2;

import com.google.common.base.Preconditions;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The workers a decode or an encoder stage may use: a pool and how many of its threads. A loop over independent items
 * runs with each worker pulling the next item from a shared counter and keeping its own scratch state, so what an item
 * computes never depends on which worker ran it or on the number of workers.
 */
public final class Workers {

  /** Runs every loop on the calling thread. */
  public static final Workers SEQUENTIAL = new Workers(null, 1);

  private final @Nullable ForkJoinPool pool;

  private final int threads;

  /**
   * Constructs new workers.
   *
   * @param pool    the pool, or null to run on the calling thread
   * @param threads how many workers a loop uses at most, at least 1
   * @throws IllegalArgumentException if the thread count is not positive
   */
  public Workers(final @Nullable ForkJoinPool pool, final int threads) {
    Preconditions.checkArgument(threads >= 1, "At least one thread is needed");
    this.pool = pool;
    this.threads = threads;
  }

  /**
   * Gets how many workers a loop uses at most.
   *
   * @return the thread count
   */
  public int threads() {
    return this.pool == null ? 1 : this.threads;
  }

  /**
   * Runs a body for every index from 0 to count, exclusive.
   *
   * @param count   the number of items
   * @param scratch makes one worker's scratch state
   * @param body    processes one item with the worker's scratch state
   * @param <T>     the scratch type
   */
  public <T> void forEach(final int count, final Supplier<T> scratch, final ObjIntConsumer<T> body) {
    final ForkJoinPool target = this.pool;
    final int workers = Math.min(this.threads(), count);
    if (target == null || workers <= 1) {
      final T state = scratch.get();
      for (int i = 0; i < count; i++) {
        body.accept(state, i);
      }
      return;
    }
    final AtomicInteger next = new AtomicInteger();
    target
      .submit(() ->
        IntStream.range(0, workers)
          .parallel()
          .forEach(_ -> {
            final T state = scratch.get();
            for (int i = next.getAndIncrement(); i < count; i = next.getAndIncrement()) {
              body.accept(state, i);
            }
          })
      )
      .join();
  }
}
