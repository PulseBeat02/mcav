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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The loop runner of the parallel decode and encode stages. */
final class WorkersTest {

  @Test
  void runsEveryIndexInOrderOnTheCallingThreadWithOneScratch() {
    final List<Integer> visited = new ArrayList<>();
    final AtomicInteger scratches = new AtomicInteger();
    final Thread caller = Thread.currentThread();
    Workers.SEQUENTIAL.forEach(5, scratches::incrementAndGet, (_, index) -> {
      assertEquals(caller, Thread.currentThread());
      visited.add(index);
    });
    assertEquals(List.of(0, 1, 2, 3, 4), visited);
    assertEquals(1, scratches.get());
    assertEquals(1, Workers.SEQUENTIAL.threads());
  }

  @Test
  void runsEveryIndexExactlyOnceOnThePool() {
    final ForkJoinPool pool = new ForkJoinPool(4);
    try {
      final Workers workers = new Workers(pool, 4);
      assertEquals(4, workers.threads());
      final AtomicIntegerArray counts = new AtomicIntegerArray(1000);
      final Set<Thread> threads = ConcurrentHashMap.newKeySet();
      final AtomicInteger scratches = new AtomicInteger();
      workers.forEach(counts.length(), scratches::incrementAndGet, (_, index) -> {
        counts.incrementAndGet(index);
        threads.add(Thread.currentThread());
      });
      for (int i = 0; i < counts.length(); i++) {
        assertEquals(1, counts.get(i), "index " + i);
      }
      // one scratch per worker, and the work ran on the pool, not on the caller
      assertEquals(4, scratches.get());
      assertFalse(threads.contains(Thread.currentThread()));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void usesNoMoreWorkersThanItems() {
    final ForkJoinPool pool = new ForkJoinPool(4);
    try {
      final AtomicInteger scratches = new AtomicInteger();
      final AtomicInteger calls = new AtomicInteger();
      new Workers(pool, 8).forEach(2, scratches::incrementAndGet, (_, _) -> calls.incrementAndGet());
      assertEquals(2, calls.get());
      assertEquals(2, scratches.get());
      // a single item runs on the calling thread
      final Thread caller = Thread.currentThread();
      new Workers(pool, 8).forEach(1, () -> caller, (thread, _) -> assertEquals(thread, Thread.currentThread()));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void runsOnTheCallingThreadWithoutAPool() {
    final Workers workers = new Workers(null, 6);
    assertEquals(1, workers.threads());
    final Thread caller = Thread.currentThread();
    workers.forEach(3, () -> caller, (thread, _) -> assertEquals(thread, Thread.currentThread()));
  }

  @Test
  void refusesNoThreads() {
    assertThrows(IllegalArgumentException.class, () -> new Workers(null, 0));
  }

  @Test
  void runsAnUrgentLoopBetweenTheItemsOfALongOne() throws InterruptedException {
    final ForkJoinPool pool = new ForkJoinPool(3);
    try {
      final Workers.Lane lane = new Workers.Lane();
      final Workers workers = new Workers(pool, 3).on(lane);
      final AtomicInteger searched = new AtomicInteger();
      final CountDownLatch started = new CountDownLatch(1);
      final Thread search = new Thread(() ->
        workers.forEach(
          400,
          () -> null,
          (_, _) -> {
            searched.incrementAndGet();
            started.countDown();
            sleep(2);
          }
        )
      );
      search.start();
      started.await();
      // the urgent loop's items: some taken by the long loop's workers between their own items
      final AtomicIntegerArray counts = new AtomicIntegerArray(40);
      final Set<Thread> threads = ConcurrentHashMap.newKeySet();
      final AtomicInteger scratches = new AtomicInteger();
      workers
        .first()
        .forEach(counts.length(), scratches::incrementAndGet, (_, index) -> {
          counts.incrementAndGet(index);
          threads.add(Thread.currentThread());
          sleep(3);
        });
      final int searchedWhenDone = searched.get();
      search.join();
      for (int i = 0; i < counts.length(); i++) {
        assertEquals(1, counts.get(i), "index " + i);
      }
      assertTrue(searchedWhenDone < 400, "the urgent loop ended while the long one still had items");
      assertTrue(threads.size() > 1, "the long loop's workers helped");
      assertEquals(threads.size(), scratches.get());
      assertEquals(400, searched.get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void passesOnWhatAnUrgentItemThrowsOnceEveryItemIsDone() {
    final ForkJoinPool pool = new ForkJoinPool(2);
    try {
      final Workers first = new Workers(pool, 2).on(new Workers.Lane()).first();
      final AtomicInteger done = new AtomicInteger();
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
        first.forEach(
          10,
          () -> null,
          (_, index) -> {
            done.incrementAndGet();
            if (index == 3) {
              throw new IllegalStateException("item 3");
            }
          }
        )
      );
      assertEquals("item 3", thrown.getMessage());
      assertEquals(10, done.get());
      assertThrows(AssertionError.class, () ->
        first.forEach(
          2,
          () -> null,
          (_, _) -> {
            throw new AssertionError("an error");
          }
        )
      );
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void runsAnUrgentLoopOnTheCallingThreadWithoutAPool() {
    final Workers first = new Workers(null, 4).on(new Workers.Lane()).first();
    final Thread caller = Thread.currentThread();
    final List<Integer> visited = new ArrayList<>();
    first.forEach(
      3,
      () -> caller,
      (thread, index) -> {
        assertEquals(thread, Thread.currentThread());
        visited.add(index);
      }
    );
    assertEquals(List.of(0, 1, 2), visited);
    // no items: nothing runs
    first.forEach(
      0,
      () -> caller,
      (_, _) -> {
        throw new AssertionError("no item");
      }
    );
    assertThrows(IllegalStateException.class, () -> new Workers(null, 1).first());
    assertThrows(NullPointerException.class, () -> new Workers(null, 1).on(null));
  }

  @Test
  void keepsAnInterruptThatCameWhileItWaitedForAnotherThreadsItems() throws InterruptedException {
    final ForkJoinPool pool = new ForkJoinPool(2);
    try {
      final Workers first = new Workers(pool, 2).on(new Workers.Lane()).first();
      final Thread caller = Thread.currentThread();
      final Thread interrupter = new Thread(() -> {
        sleep(200);
        caller.interrupt();
      });
      interrupter.start();
      // the caller's item is short, the pool's long: the caller waits for it, and is interrupted meanwhile
      first.forEach(2, () -> null, (_, _) -> sleep(Thread.currentThread().equals(caller) ? 100 : 400));
      assertTrue(Thread.interrupted());
      interrupter.join();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void runsATaskFirstOnThePoolAndPassesOnItsResultOrFailure() throws InterruptedException {
    final ForkJoinPool pool = new ForkJoinPool(2);
    try {
      final Workers.Lane lane = new Workers.Lane();
      final Thread caller = Thread.currentThread();
      assertEquals("done", lane.call(() -> Thread.currentThread().equals(caller) ? "caller" : "done", pool));
      final IllegalStateException checked = assertThrows(IllegalStateException.class, () ->
        lane.call(
          () -> {
            throw new IOException("checked");
          },
          pool
        )
      );
      assertInstanceOf(IOException.class, checked.getCause());
      assertThrows(UnsupportedOperationException.class, () ->
        lane.call(
          () -> {
            throw new UnsupportedOperationException("runtime");
          },
          pool
        )
      );
      assertThrows(NullPointerException.class, () -> lane.call(() -> null, pool));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void startsNoTaskForACallerInterruptedWhileItWaits() throws InterruptedException {
    final ForkJoinPool pool = new ForkJoinPool(1);
    final CountDownLatch release = new CountDownLatch(1);
    try {
      // the pool's one thread is busy, so the task waits on the lane
      pool.execute(() -> {
        try {
          release.await();
        } catch (final InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      });
      final Workers.Lane lane = new Workers.Lane();
      final AtomicBoolean ran = new AtomicBoolean();
      final AtomicReference<Throwable> outcome = new AtomicReference<>();
      final Thread waiter = new Thread(() -> {
        try {
          lane.call(() -> ran.getAndSet(true), pool);
        } catch (final InterruptedException exception) {
          outcome.set(exception);
        }
      });
      waiter.start();
      sleep(100);
      waiter.interrupt();
      waiter.join();
      release.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
      assertInstanceOf(InterruptedException.class, outcome.get());
      assertFalse(ran.get());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private static void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}
