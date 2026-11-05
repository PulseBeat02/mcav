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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.Workers;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

final class Mcv2WorkersTest {

  private static final String BODY_FAILURE_MESSAGE = "body failed!";

  @Test
  void shouldRunEveryIndexInOrderOnTheCallingThreadWithOneScratch() {
    final List<Integer> visited = new ArrayList<>();
    final AtomicInteger scratches = new AtomicInteger();
    final Thread caller = Thread.currentThread();
    Workers.SEQUENTIAL.forEach(5, scratches::incrementAndGet, (_, index) -> this.recordIndexOnCallingThread(index, caller, visited));

    final List<Integer> expected = List.of(0, 1, 2, 3, 4);
    assertEquals(expected, visited);

    final int scratchCount = scratches.get();
    assertEquals(1, scratchCount);

    final int threadCount = Workers.SEQUENTIAL.threads();
    assertEquals(1, threadCount);
  }

  private void recordIndexOnCallingThread(final int index, final Thread caller, final List<Integer> visited) {
    final Thread current = Thread.currentThread();
    assertEquals(caller, current);

    visited.add(index);
  }

  @Test
  void shouldRunEveryIndexExactlyOnceOnThePool() {
    final ForkJoinPool pool = new ForkJoinPool(4);
    try {
      final Workers workers = new Workers(pool, 4);
      final int threadCount = workers.threads();
      assertEquals(4, threadCount);

      final AtomicIntegerArray counts = new AtomicIntegerArray(1000);
      final Set<Thread> threads = ConcurrentHashMap.newKeySet();
      final AtomicInteger scratches = new AtomicInteger();
      workers.forEach(counts.length(), scratches::incrementAndGet, (_, index) -> this.recordIndexOnPool(index, counts, threads, pool));
      for (int position = 0; position < counts.length(); position++) {
        final int count = counts.get(position);
        assertEquals(1, count, "index %s".formatted(position));
      }

      final int scratchCount = scratches.get();
      assertEquals(4, scratchCount);

      final boolean ranOnCaller = threads.contains(Thread.currentThread());
      assertFalse(ranOnCaller);
    } finally {
      pool.shutdownNow();
    }
  }

  private void recordIndexOnPool(final int index, final AtomicIntegerArray counts, final Set<Thread> threads, final ForkJoinPool pool) {
    counts.incrementAndGet(index);
    threads.add(Thread.currentThread());
    final @Nullable ForkJoinPool currentPool = ForkJoinTask.getPool();
    assertSame(pool, currentPool, "every index must run on the supplied pool");
  }

  @Test
  void shouldUseNoMoreWorkersThanItems() {
    final ForkJoinPool pool = new ForkJoinPool(4);
    try {
      final AtomicInteger scratches = new AtomicInteger();
      final AtomicInteger calls = new AtomicInteger();
      new Workers(pool, 8).forEach(2, scratches::incrementAndGet, (_, _) -> calls.incrementAndGet());
      final int callCount = calls.get();
      assertEquals(2, callCount);

      final int scratchCount = scratches.get();
      assertEquals(2, scratchCount);

      final Thread caller = Thread.currentThread();
      new Workers(pool, 8).forEach(1, () -> caller, (thread, _) -> this.assertRunsOn(thread));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void shouldWaitForOtherCallbacksBeforePropagatingAFailure() throws InterruptedException {
    this.assertFailureWaitsForSibling(FailurePlace.BODY, new IllegalStateException(BODY_FAILURE_MESSAGE));
  }

  @Test
  void shouldWaitForOtherCallbacksBeforePropagatingAScratchFailure() throws InterruptedException {
    this.assertFailureWaitsForSibling(FailurePlace.SCRATCH, new IllegalStateException("scratch failed!"));
  }

  @Test
  void shouldWaitForOtherCallbacksBeforePropagatingAnError() throws InterruptedException {
    this.assertFailureWaitsForSibling(FailurePlace.BODY, new AssertionError(BODY_FAILURE_MESSAGE));
  }

  private void assertFailureWaitsForSibling(final FailurePlace place, final Throwable failure) throws InterruptedException {
    final SiblingFailure fixture = new SiblingFailure(place, failure);
    final Thread caller = new Thread(fixture::callForEach, "workers-failure-caller");
    try {
      caller.start();
      final boolean reachedFailure = fixture.throwing.await(5, TimeUnit.SECONDS);
      assertTrue(reachedFailure, "the root worker must reach its failure");

      final boolean returnedEarly = fixture.returned.await(200, TimeUnit.MILLISECONDS);
      assertFalse(returnedEarly, "forEach returned while a sibling callback still owned its state");
    } finally {
      fixture.releaseSibling.countDown();
      caller.join(5000);
      fixture.pool.shutdownNow();
      final boolean terminated = fixture.pool.awaitTermination(5, TimeUnit.SECONDS);
      assertTrue(terminated, "worker pool must terminate");

      final boolean callerAlive = caller.isAlive();
      assertFalse(callerAlive, "caller must terminate");
    }

    final boolean callbacksFinishedFirst = fixture.finishedBeforeReturn.get();
    assertTrue(callbacksFinishedFirst, "all callbacks must finish before the failure reaches the caller");

    Throwable observed = fixture.reported.get();
    while (observed != null && observed.getCause() != null) {
      observed = observed.getCause();
    }

    assertSame(failure, observed);
  }

  private static void await(final CountDownLatch latch) {
    try {
      final boolean released = latch.await(5, TimeUnit.SECONDS);
      assertTrue(released, "fixture latch must be released");
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private static ForkJoinWorkerThread newRecordedWorker(final ForkJoinPool owner, final AtomicReference<Thread> root) {
    final ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(owner);
    root.compareAndSet(null, worker);
    return worker;
  }

  @Test
  void shouldRunOnTheCallingThreadWithOneWorker() {
    final Workers workers = new Workers(ForkJoinPool.commonPool(), 1);
    final int threadCount = workers.threads();
    assertEquals(1, threadCount);

    final Thread caller = Thread.currentThread();
    workers.forEach(3, () -> caller, (thread, _) -> this.assertRunsOn(thread));
  }

  private void assertRunsOn(final Thread thread) {
    final Thread current = Thread.currentThread();
    assertEquals(thread, current);
  }

  @Test
  void shouldRefuseNoThreads() {
    assertThrows(IllegalArgumentException.class, () -> new Workers(ForkJoinPool.commonPool(), 0));
  }

  private static final class SiblingFailure {

    private final FailurePlace place;
    private final Throwable failure;
    private final AtomicReference<Thread> root;
    private final ForkJoinPool pool;
    private final CountDownLatch siblingEntered;
    private final CountDownLatch releaseSibling;
    private final CountDownLatch throwing;
    private final CountDownLatch returned;
    private final AtomicBoolean siblingFinished;
    private final AtomicBoolean finishedBeforeReturn;
    private final AtomicReference<Throwable> reported;

    SiblingFailure(final FailurePlace place, final Throwable failure) {
      final AtomicReference<Thread> root = new AtomicReference<>();
      this.place = place;
      this.failure = failure;
      this.root = root;
      this.pool = new ForkJoinPool(2, owner -> newRecordedWorker(owner, root), null, false);
      this.siblingEntered = new CountDownLatch(1);
      this.releaseSibling = new CountDownLatch(1);
      this.throwing = new CountDownLatch(1);
      this.returned = new CountDownLatch(1);
      this.siblingFinished = new AtomicBoolean();
      this.finishedBeforeReturn = new AtomicBoolean();
      this.reported = new AtomicReference<>();
    }

    void callForEach() {
      try {
        new Workers(this.pool, 2).forEach(2, this::createRoleScratch, (role, _) -> this.runBody(role));
      } catch (final RuntimeException | Error exception) {
        this.reported.set(exception);
      } finally {
        this.finishedBeforeReturn.set(this.siblingFinished.get());
        this.returned.countDown();
      }
    }

    private WorkerRole createRoleScratch() {
      final boolean rootWorker = Thread.currentThread().equals(this.root.get());
      final WorkerRole role = rootWorker ? WorkerRole.ROOT : WorkerRole.SIBLING;
      if (role == WorkerRole.ROOT && this.place == FailurePlace.SCRATCH) {
        this.fail();
      }

      return role;
    }

    private void runBody(final WorkerRole role) {
      if (role == WorkerRole.ROOT) {
        this.fail();
      }

      this.siblingEntered.countDown();
      await(this.releaseSibling);
      this.siblingFinished.set(true);
    }

    private void fail() {
      await(this.siblingEntered);
      this.throwing.countDown();

      if (this.failure instanceof final RuntimeException exception) {
        throw exception;
      }

      throw (Error) this.failure;
    }
  }

  private enum FailurePlace {
    BODY,
    SCRATCH,
  }

  private enum WorkerRole {
    ROOT,
    SIBLING,
  }
}
