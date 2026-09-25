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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
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
}
