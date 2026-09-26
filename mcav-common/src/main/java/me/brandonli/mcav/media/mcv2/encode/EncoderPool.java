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
package me.brandonli.mcav.media.mcv2.encode;

import com.google.common.base.Preconditions;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The threads MCV2 encoding may use: one budget that every encoder given it shares, so screens that encode at the
 * same time split the budget instead of each taking the machine, and a game server keeps the rest of its processors.
 *
 * <p>A whole encode runs inside the budget ({@link #run(Callable)}): the encoder's sequential steps and all its
 * parallel loops run on the budget's threads, and no thread is ever added beyond them, not even while one waits for a
 * loop to finish. The frames of several encoders are encoded by whichever threads are free, in the order they were
 * handed over. The threads are daemon threads in a thread group whose priority is capped at the lowest, which the
 * operating systems that honour Java thread priorities, like Windows, run after the game's threads; HotSpot on Linux
 * ignores thread priorities unless the JVM is started with {@code -XX:ThreadPriorityPolicy=1} as root, so there the
 * size of the budget is what keeps processors free for the game.
 *
 * <p>A server has one shared budget, {@link #shared()}, of {@link #defaultThreads(int) half its processors} unless
 * {@link #setSharedThreads(int)} sets another size. {@link Runtime#availableProcessors()} counts the processors the
 * JVM may use, which on JDK 25 follows the CPU limit and CPU set of a container.
 */
public final class EncoderPool implements AutoCloseable {

  /** The most threads a budget may have. */
  public static final int MAX_THREADS = 256;

  /** How long an idle thread of a budget lives before it ends; the budget starts another when work comes. */
  private static final long KEEP_ALIVE_SECONDS = 30;

  private static final Logger LOGGER = LoggerFactory.getLogger(EncoderPool.class);

  private static final String THREAD_FAILED = "MCV2 encoder thread {} failed";

  /** The group of every budget's threads, whose priority it caps below the game's. */
  private static final ThreadGroup GROUP = lowPriorityGroup();

  private static @Nullable EncoderPool shared;

  private static int sharedThreads;

  private final ForkJoinPool pool;

  private final int threads;

  /**
   * Creates a budget.
   *
   * @param threads how many threads may encode at once, 1 to {@link #MAX_THREADS}
   * @throws IllegalArgumentException if the thread count is out of range
   */
  public EncoderPool(final int threads) {
    Preconditions.checkArgument(threads >= 1 && threads <= MAX_THREADS, "Threads must be 1 to %s", MAX_THREADS);
    this.threads = threads;
    final AtomicInteger created = new AtomicInteger();
    final ForkJoinPool.ForkJoinWorkerThreadFactory factory = forkJoinPool -> {
      final ForkJoinWorkerThread thread = new EncoderThread(forkJoinPool);
      thread.setName("mcav-mcv2-encoder-" + created.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
    // at most `threads` threads ever exist: a thread that waits for a loop helps with it or waits, and the pool
    // carries on with the others instead of starting a spare
    this.pool = new ForkJoinPool(
      threads,
      factory,
      EncoderPool::uncaught,
      false,
      0,
      threads,
      1,
      _ -> true,
      KEEP_ALIVE_SECONDS,
      TimeUnit.SECONDS
    );
  }

  /** A thread of a budget: in the low-priority group, so it gets the group's capped priority. */
  private static final class EncoderThread extends ForkJoinWorkerThread {

    private EncoderThread(final ForkJoinPool pool) {
      super(GROUP, pool, false);
    }
  }

  private static ThreadGroup lowPriorityGroup() {
    final ThreadGroup group = new ThreadGroup("mcav-mcv2-encoders");
    group.setMaxPriority(Thread.MIN_PRIORITY);
    return group;
  }

  /**
   * Gets the default size of a budget: half the processors, at least one, so a server keeps the other half. A machine
   * with a single processor has to share it.
   *
   * @param processors the processors the JVM may use
   * @return the thread count
   */
  public static int defaultThreads(final int processors) {
    return Math.max(1, processors / 2);
  }

  /**
   * Gets the budget every screen and file encode of the server shares, creating it on first use.
   *
   * @return the shared budget
   */
  public static synchronized EncoderPool shared() {
    final EncoderPool current = shared;
    if (current != null) {
      return current;
    }
    final int size = sharedThreads > 0 ? sharedThreads : defaultThreads(Runtime.getRuntime().availableProcessors());
    final EncoderPool created = new EncoderPool(size);
    shared = created;
    return created;
  }

  /**
   * Sets the size of the shared budget. Encoders created before keep the budget they were given until they stop; the
   * threads of a replaced budget end once they are idle.
   *
   * @param threads the thread count, 1 to {@link #MAX_THREADS}, or 0 for {@link #defaultThreads(int) the default}
   * @throws IllegalArgumentException if the thread count is out of range
   */
  public static synchronized void setSharedThreads(final int threads) {
    Preconditions.checkArgument(threads >= 0 && threads <= MAX_THREADS, "Threads must be 0 to %s", MAX_THREADS);
    if (threads != sharedThreads) {
      sharedThreads = threads;
      shared = null;
    }
  }

  /**
   * Gets how many threads may encode at once.
   *
   * @return the thread count
   */
  public int getThreads() {
    return this.threads;
  }

  /**
   * Creates an encoder whose parallel loops run on the budget's threads. Call {@link Mcv2Encoder#encode} through
   * {@link #run(Callable)} so the rest of the encode does as well.
   *
   * @param settings the encoder settings
   * @param verify   whether every frame is decoded and compared, as the encoder's verify argument
   * @return the encoder
   */
  public Mcv2Encoder encoder(final EncoderSettings settings, final boolean verify) {
    return new Mcv2Encoder(settings, this.pool, this.threads, verify);
  }

  /**
   * Runs a task on the budget's threads and waits for it.
   *
   * @param task the task
   * @param <T>  the result type
   * @return what the task returned
   * @throws InterruptedException if the calling thread was interrupted before, when the task is not started, or while
   *                              it waits, when the task is cancelled
   */
  public <T> T run(final Callable<T> task) throws InterruptedException {
    Preconditions.checkNotNull(task, "Task must not be null");
    // a finished task's result is returned without looking at the interrupt, so one that came between two tasks is
    // noticed here
    if (Thread.interrupted()) {
      throw new InterruptedException("Interrupted before the task started");
    }
    final ForkJoinTask<T> job = this.pool.submit(task);
    try {
      return job.get();
    } catch (final InterruptedException exception) {
      job.cancel(true);
      throw exception;
    } catch (final ExecutionException exception) {
      // what the task threw, as it is when it may be thrown from here
      final Throwable cause = exception.getCause();
      if (cause instanceof final RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof final Error error) {
        throw error;
      }
      throw new IllegalStateException("An encode failed", cause);
    }
  }

  /**
   * Logs an exception that ended one of a budget's threads outside any task; the budget starts another thread when
   * work comes.
   *
   * @param thread    the thread
   * @param exception what ended it
   */
  static void uncaught(final Thread thread, final Throwable exception) {
    LOGGER.error(THREAD_FAILED, thread.getName(), exception);
  }

  /**
   * Stops the budget's threads; tasks that are still running are interrupted.
   */
  @Override
  public void close() {
    this.pool.shutdownNow();
  }
}
