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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The workers a decode or an encoder stage may use: a pool and how many of its threads. A loop over independent items
 * runs with each worker pulling the next item from a shared counter and keeping its own scratch state, so what an item
 * computes never depends on which worker ran it or on the number of workers.
 *
 * <p>Workers on a {@link Lane} share it with the other workers of the same threads: a loop of {@link #first() first}
 * workers is published on the lane, and every other loop's workers take its items before their own next item, so a
 * short urgent loop - a frame's verification - runs at once instead of waiting until a long loop - the next frame's
 * search - has handed out its last item. The threads stay the pool's: the urgent loop's own thread pulls its items,
 * the others help between items, and no loop waits for another's to end.
 */
public final class Workers {

  /** Runs every loop on the calling thread. */
  public static final Workers SEQUENTIAL = new Workers(null, 1);

  private final @Nullable ForkJoinPool pool;

  private final int threads;

  private final @Nullable Lane lane;

  private final @Nullable Lane priority;

  /**
   * Constructs new workers.
   *
   * @param pool    the pool, or null to run on the calling thread
   * @param threads how many workers a loop uses at most, at least 1
   * @throws IllegalArgumentException if the thread count is not positive
   */
  public Workers(final @Nullable ForkJoinPool pool, final int threads) {
    this(pool, threads, null, null);
  }

  private Workers(final @Nullable ForkJoinPool pool, final int threads, final @Nullable Lane lane, final @Nullable Lane priority) {
    Preconditions.checkArgument(threads >= 1, "At least one thread is needed");
    this.pool = pool;
    this.threads = threads;
    this.lane = lane;
    this.priority = priority;
  }

  /**
   * Gets these workers on a lane: their loops take the lane's urgent items before each item of their own.
   *
   * @param lane the lane, which every workers of the same threads should share
   * @return the workers
   */
  public Workers on(final Lane lane) {
    return new Workers(this.pool, this.threads, Preconditions.checkNotNull(lane, "Lane must not be null"), null);
  }

  /**
   * Gets workers whose loops go first on the lane.
   *
   * @return the workers
   * @throws IllegalStateException if these workers are on no lane
   */
  public Workers first() {
    Preconditions.checkState(this.lane != null, "Workers on no lane cannot go first");
    return new Workers(this.pool, this.threads, this.lane, this.lane);
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
    final Lane shared = this.lane;
    final Lane priority = this.priority;
    final int workers = Math.min(this.threads(), count);
    if (priority != null && count > 0) {
      priority.run(new Job<>(count, scratch, body), target, workers);
      return;
    }
    if (target == null || workers <= 1) {
      final T state = scratch.get();
      for (int i = 0; i < count; i++) {
        help(shared);
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
              help(shared);
              body.accept(state, i);
            }
          })
      )
      .join();
  }

  private static void help(final @Nullable Lane lane) {
    if (lane != null) {
      lane.help();
    }
  }

  /**
   * The urgent loops of the workers of the same threads, which their other loops' workers take items of first.
   */
  public static final class Lane {

    private final ConcurrentLinkedQueue<Job<?>> jobs = new ConcurrentLinkedQueue<>();

    /** Takes the items of the urgent loops on the lane, if any, until none is left to take. */
    private void help() {
      for (Job<?> job = this.jobs.peek(); job != null; job = this.jobs.peek()) {
        job.work();
        // every item is taken: the loop's own thread waits for the ones still running
        this.jobs.remove(job);
      }
    }

    /**
     * Runs an urgent loop: published for the other loops' workers, given to idle threads, and worked on by the calling
     * thread, which then waits for the items other threads took.
     */
    private <T> void run(final Job<T> job, final @Nullable ForkJoinPool pool, final int workers) {
      this.jobs.add(job);
      if (pool != null) {
        for (int k = 1; k < workers; k++) {
          pool.execute(this::help);
        }
      }
      job.work();
      this.jobs.remove(job);
      job.awaitDone();
      job.rethrow();
    }

    /**
     * Runs a task as an urgent loop of one item on a pool's threads: the first of them to be free or between two items
     * of a loop runs it, and the calling thread, which is not one of them, waits for it.
     *
     * @param task the task
     * @param pool the pool
     * @param <V>  the result type
     * @return what the task returned
     * @throws InterruptedException if the calling thread is interrupted while it waits; a task not started by then is
     *                              not started
     */
    public <V> V call(final Callable<V> task, final ForkJoinPool pool) throws InterruptedException {
      final AtomicReference<@Nullable V> result = new AtomicReference<>();
      final Job<Callable<V>> job = new Job<>(1, () -> task, (callable, _) -> result.set(invoke(callable)));
      this.jobs.add(job);
      pool.execute(this::help);
      try {
        job.awaitInterruptibly();
      } catch (final InterruptedException exception) {
        job.cancel();
        this.jobs.remove(job);
        throw exception;
      }
      job.rethrow();
      return Preconditions.checkNotNull(result.get(), "The task returned nothing");
    }

    /** Calls a task, a checked exception it throws wrapped. */
    private static <V> V invoke(final Callable<V> task) {
      try {
        return task.call();
      } catch (final RuntimeException | Error exception) {
        throw exception;
      } catch (final Exception exception) {
        throw new IllegalStateException("A task failed", exception);
      }
    }
  }

  /** One urgent loop: its items, taken from a shared counter, and a count of the ones done. */
  private static final class Job<T> {

    private final int count;

    private final Supplier<T> scratch;

    private final ObjIntConsumer<T> body;

    private final AtomicInteger next = new AtomicInteger();

    private final CountDownLatch done;

    private final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();

    Job(final int count, final Supplier<T> scratch, final ObjIntConsumer<T> body) {
      this.count = count;
      this.scratch = scratch;
      this.body = body;
      this.done = new CountDownLatch(count);
    }

    /** Takes items until none is left; an item that throws is counted done and its failure kept. */
    void work() {
      int i = this.next.getAndIncrement();
      if (i >= this.count) {
        return;
      }
      final T state = this.scratch.get();
      for (; i < this.count; i = this.next.getAndIncrement()) {
        try {
          this.body.accept(state, i);
        } catch (final RuntimeException | Error exception) {
          this.failure.compareAndSet(null, exception);
        }
        this.done.countDown();
      }
    }

    /** Takes no more items: the ones not taken yet count as done. */
    void cancel() {
      for (int i = this.next.getAndIncrement(); i < this.count; i = this.next.getAndIncrement()) {
        this.done.countDown();
      }
    }

    /** Waits for the items other threads took, whatever interrupts come, which it keeps for the thread. */
    void awaitDone() {
      boolean interrupted = false;
      while (true) {
        try {
          this.done.await();
          break;
        } catch (final InterruptedException exception) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    void awaitInterruptibly() throws InterruptedException {
      this.done.await();
    }

    /** Throws what an item threw. */
    void rethrow() {
      final Throwable thrown = this.failure.get();
      if (thrown instanceof final RuntimeException runtime) {
        throw runtime;
      }
      if (thrown instanceof final Error error) {
        throw error;
      }
    }
  }
}
