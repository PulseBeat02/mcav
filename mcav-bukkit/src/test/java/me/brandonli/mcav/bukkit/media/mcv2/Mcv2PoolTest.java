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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.Workers;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

final class Mcv2PoolTest {

  private static final int WAIT_TIMEOUT_SECONDS = 60;

  private static final String ENCODER_THREAD_PREFIX = "mcav-mcv2-encoder-";

  @Test
  void shouldUseHalfTheProcessorsByDefault() {
    final int forOne = Pool.defaultThreads(1);
    assertEquals(1, forOne);

    final int forTwo = Pool.defaultThreads(2);
    assertEquals(1, forTwo);

    final int forThree = Pool.defaultThreads(3);
    assertEquals(1, forThree);

    final int forFour = Pool.defaultThreads(4);
    assertEquals(2, forFour);

    final int forTwelve = Pool.defaultThreads(12);
    assertEquals(6, forTwelve);

    final int forMany = Pool.defaultThreads(768);
    assertEquals(256, forMany);

    assertThrows(IllegalArgumentException.class, () -> new Pool(0));
    assertThrows(IllegalArgumentException.class, () -> new Pool(Pool.MAXIMUM_THREADS + 1));

    try (final Pool largest = new Pool(Pool.MAXIMUM_THREADS)) {
      final int threads = largest.getThreads();
      assertEquals(Pool.MAXIMUM_THREADS, threads);
    }
  }

  @Test
  void shouldRunOnLowPriorityDaemonThreadsOfItsOwn() {
    try (final Pool pool = new Pool(2)) {
      final int threads = pool.getThreads();
      assertEquals(2, threads);

      final Thread thread = pool.run(Thread::currentThread);
      final boolean named = thread.getName().startsWith(ENCODER_THREAD_PREFIX);
      assertTrue(named, thread.getName());

      final boolean daemon = thread.isDaemon();
      assertTrue(daemon);

      final int priority = thread.getPriority();
      assertEquals(Thread.MIN_PRIORITY, priority);

      final Thread testThread = Thread.currentThread();
      assertNotSame(testThread, thread);
    }
  }

  @Test
  void shouldNeverUseMoreThreadsThanItsSize() throws InterruptedException {
    try (final Pool pool = new Pool(2)) {
      final Set<Thread> seen = ConcurrentHashMap.newKeySet();
      final AtomicLong total = new AtomicLong();
      final List<Thread> callers = new ArrayList<>();
      for (int caller = 0; caller < 3; caller++) {
        callers.add(Thread.ofPlatform().start(() -> this.runNestedLoops(pool, seen, total)));
      }

      for (final Thread caller : callers) {
        caller.join(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS));
        final boolean finished = !caller.isAlive();
        assertTrue(finished);
      }

      final long expectedTotal = 3L * 5 * 16 * 199_990_000L;
      final long actualTotal = total.get();
      assertEquals(expectedTotal, actualTotal);

      final boolean withinSize = seen.size() <= 2;
      assertTrue(withinSize, "threads used: %s".formatted(seen.size()));

      for (final Thread thread : seen) {
        final boolean named = thread.getName().startsWith(ENCODER_THREAD_PREFIX);
        assertTrue(named, thread.getName());
      }
    }
  }

  private void runNestedLoops(final Pool pool, final Set<Thread> seen, final AtomicLong total) {
    pool.run(() -> this.runWorkerLoops(seen, total));
  }

  private @Nullable Object runWorkerLoops(final Set<Thread> seen, final AtomicLong total) {
    final ForkJoinPool inner = ForkJoinTask.getPool();
    final Workers workers = new Workers(inner, 4);
    for (int loop = 0; loop < 5; loop++) {
      workers.forEach(16, () -> seen, (threads, _) -> this.sumOnThread(threads, total));
    }

    return null;
  }

  private void sumOnThread(final Set<Thread> threads, final AtomicLong total) {
    threads.add(Thread.currentThread());
    total.addAndGet(IntStream.range(0, 20_000).sum());
  }

  @Test
  void shouldBlockAThreadInsteadOfStartingASpare() {
    try (final Pool pool = new Pool(1)) {
      final Set<Thread> seen = ConcurrentHashMap.newKeySet();
      final AtomicInteger blocks = new AtomicInteger();
      final AtomicInteger threadsWhileBlocked = new AtomicInteger();
      final ForkJoinPool.ManagedBlocker blocker = new CountingBlocker(blocks, threadsWhileBlocked);
      pool.run(() -> this.blockOnce(seen, blocker));
      final int blockCount = blocks.get();
      assertEquals(1, blockCount);

      final int poolSizeWhileBlocked = threadsWhileBlocked.get();
      assertEquals(1, poolSizeWhileBlocked, "managed blocking must not create a spare worker");

      final int seenCount = seen.size();
      assertEquals(1, seenCount);
    }
  }

  private @Nullable Object blockOnce(final Set<Thread> seen, final ForkJoinPool.ManagedBlocker blocker) throws InterruptedException {
    seen.add(Thread.currentThread());
    // a pool asked to keep its threads running while one blocks would start a spare; the budget's does not
    ForkJoinPool.managedBlock(blocker);
    seen.add(Thread.currentThread());

    return null;
  }

  @Test
  void shouldRunANestedLoopOnASingleThread() {
    try (final Pool pool = new Pool(1)) {
      final int sum = pool.run(() ->
        ForkJoinTask.getPool()
          .submit(() -> IntStream.range(0, 1000).parallel().sum())
          .join()
      );
      assertEquals(499_500, sum);
    }
  }

  @Test
  void shouldEncodeInsideTheBudget() {
    try (final Pool pool = new Pool(2)) {
      final Mcv2 encoder = pool.encoder(Mcv2.Settings.DEFAULT, Mcv2.Verification.VERIFIED);
      final byte[] rgb = new byte[32 * 16 * 3];
      for (int index = 0; index < rgb.length; index++) {
        rgb[index] = (byte) (index * 7);
      }

      final byte[] inside = pool.run(() -> encoder.encode(rgb, 32, 16, 0));
      final Mcv2 alone = new Mcv2(Mcv2.Settings.DEFAULT, ForkJoinPool.commonPool(), 1, Mcv2.Verification.VERIFIED);
      final byte[] expected = alone.encode(rgb, 32, 16, 0);
      assertArrayEquals(expected, inside);
    }
  }

  private boolean isOrWraps(final Throwable thrown, final Throwable original) {
    return thrown.equals(original) || original.equals(thrown.getCause());
  }

  @Test
  void shouldPassOnWhatATaskThrows() {
    try (final Pool pool = new Pool(1)) {
      final IllegalArgumentException runtime = new IllegalArgumentException("runtime!");
      final IllegalArgumentException thrownRuntime = assertThrows(IllegalArgumentException.class, () ->
        pool.run(() -> this.raise(runtime))
      );
      final boolean passesOnRuntime = this.isOrWraps(thrownRuntime, runtime);
      assertTrue(passesOnRuntime);

      final AssertionError error = new AssertionError("error!");
      final AssertionError thrownError = assertThrows(AssertionError.class, () -> pool.run(() -> this.raise(error)));
      final boolean passesOnError = this.isOrWraps(thrownError, error);
      assertTrue(passesOnError);

      final IOException checked = new IOException("checked!");
      final Mcv2Exception wrapped = assertThrows(Mcv2Exception.class, () -> pool.run(() -> this.raise(checked)));
      final @Nullable Throwable cause = wrapped.getCause();
      assertInstanceOf(IOException.class, cause);

      final boolean passesOnChecked = this.isOrWraps(wrapped.getCause(), checked);
      assertTrue(passesOnChecked);

      final ForkJoinPool workers = (ForkJoinPool) Mcv2Internals.field(Pool.class, pool, "pool");
      workers.getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), new IllegalStateException("outside a task!"));
      assertThrows(NullPointerException.class, () -> pool.run(null));
    }
  }

  private @Nullable Object raise(final Exception exception) throws Exception {
    throw exception;
  }

  private @Nullable Object raise(final Error error) {
    throw error;
  }

  @Test
  void shouldCancelTheTaskOfAnInterruptedCaller() throws InterruptedException {
    try (final Pool pool = new Pool(1)) {
      final CountDownLatch started = new CountDownLatch(1);
      final CountDownLatch cancelled = new CountDownLatch(1);
      final AtomicReference<Throwable> thrown = new AtomicReference<>();
      final Thread caller = Thread.ofPlatform().start(() -> this.runUntilCancelled(pool, started, cancelled, thrown));
      final boolean hasStarted = started.await(30, TimeUnit.SECONDS);
      assertTrue(hasStarted);

      caller.interrupt();
      caller.join(TimeUnit.SECONDS.toMillis(30));
      final boolean finished = !caller.isAlive();
      assertTrue(finished);

      final @Nullable Throwable callerFailure = thrown.get();
      assertInstanceOf(InterruptedException.class, callerFailure);

      final boolean wasCancelled = cancelled.await(30, TimeUnit.SECONDS);
      assertTrue(wasCancelled);
    }
  }

  private void runUntilCancelled(
    final Pool pool,
    final CountDownLatch started,
    final CountDownLatch cancelled,
    final AtomicReference<Throwable> thrown
  ) {
    try {
      pool.run(() -> this.sleepUntilCancelled(started, cancelled));
    } catch (final Mcv2Exception exception) {
      thrown.set(exception.getCause());
    }
  }

  private @Nullable Object sleepUntilCancelled(final CountDownLatch started, final CountDownLatch cancelled) {
    started.countDown();
    try {
      Thread.sleep(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS));
    } catch (final InterruptedException exception) {
      cancelled.countDown();
    }

    return null;
  }

  @Test
  void shouldStartNothingForACallerInterruptedBefore() {
    try (final Pool pool = new Pool(1)) {
      final AtomicReference<Thread> ran = new AtomicReference<>();
      final Thread current = Thread.currentThread();
      current.interrupt();
      final Mcv2Exception thrown = assertThrows(Mcv2Exception.class, () -> pool.run(() -> this.recordThread(ran)));
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted);

      final @Nullable Throwable cause = thrown.getCause();
      assertInstanceOf(InterruptedException.class, cause);

      final @Nullable Thread ranOn = ran.get();
      assertNull(ranOn);
    }
  }

  private @Nullable Object recordThread(final AtomicReference<Thread> ran) {
    ran.set(Thread.currentThread());

    return null;
  }

  @Test
  void shouldRefuseWorkOnceClosed() {
    final Pool pool = new Pool(1);
    pool.close();
    assertThrows(RejectedExecutionException.class, () -> pool.run(() -> 1));
  }

  @Test
  void shouldShareOneBudgetPerServer() {
    try {
      Pool.setSharedThreads(Pool.MAXIMUM_THREADS);
      final Pool largest = Pool.shared();
      final int largestThreads = largest.getThreads();
      assertEquals(Pool.MAXIMUM_THREADS, largestThreads);

      largest.close();
      Pool.setSharedThreads(3);
      final Pool shared = Pool.shared();
      final Pool sharedAgain = Pool.shared();
      assertSame(shared, sharedAgain);

      final int sharedThreads = shared.getThreads();
      assertEquals(3, sharedThreads);

      Pool.setSharedThreads(3);
      final Pool sameSize = Pool.shared();
      assertSame(shared, sameSize);

      Pool.setSharedThreads(2);
      final Pool resized = Pool.shared();
      assertNotSame(shared, resized);

      final int resizedThreads = Pool.shared().getThreads();
      assertEquals(2, resizedThreads);

      Pool.setSharedThreads(0);
      final int expectedDefaultThreads = Pool.defaultThreads(Runtime.getRuntime().availableProcessors());
      final int defaultThreads = Pool.shared().getThreads();
      assertEquals(expectedDefaultThreads, defaultThreads);

      assertThrows(IllegalArgumentException.class, () -> Pool.setSharedThreads(-1));
      assertThrows(IllegalArgumentException.class, () -> Pool.setSharedThreads(Pool.MAXIMUM_THREADS + 1));
    } finally {
      Pool.setSharedThreads(0);
    }
  }

  private static final class CountingBlocker implements ForkJoinPool.ManagedBlocker {

    private final AtomicInteger blocks;
    private final AtomicInteger threadsWhileBlocked;

    CountingBlocker(final AtomicInteger blocks, final AtomicInteger threadsWhileBlocked) {
      this.blocks = blocks;
      this.threadsWhileBlocked = threadsWhileBlocked;
    }

    @Override
    public boolean block() {
      this.blocks.incrementAndGet();
      this.threadsWhileBlocked.set(ForkJoinTask.getPool().getPoolSize());

      return true;
    }

    @Override
    public boolean isReleasable() {
      return false;
    }
  }
}
