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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import me.brandonli.mcav.media.mcv2.Workers;
import org.junit.jupiter.api.Test;

/**
 * The encoder budget: never more threads than its size, even when several encodes run at once and each waits for its
 * own parallel loops; its threads rank below the game's; and the server's shared budget.
 */
final class EncoderPoolTest {

  @Test
  void usesHalfTheProcessorsByDefault() {
    assertEquals(1, EncoderPool.defaultThreads(1));
    assertEquals(1, EncoderPool.defaultThreads(2));
    assertEquals(1, EncoderPool.defaultThreads(3));
    assertEquals(2, EncoderPool.defaultThreads(4));
    assertEquals(6, EncoderPool.defaultThreads(12));
    assertThrows(IllegalArgumentException.class, () -> new EncoderPool(0));
    assertThrows(IllegalArgumentException.class, () -> new EncoderPool(EncoderPool.MAX_THREADS + 1));
  }

  @Test
  void runsOnLowPriorityDaemonThreadsOfItsOwn() throws InterruptedException {
    try (EncoderPool pool = new EncoderPool(2)) {
      assertEquals(2, pool.getThreads());
      final Thread thread = pool.run(Thread::currentThread);
      assertTrue(thread.getName().startsWith("mcav-mcv2-encoder-"), thread.getName());
      assertTrue(thread.isDaemon());
      assertEquals(Thread.MIN_PRIORITY, thread.getPriority());
      assertNotSame(Thread.currentThread(), thread);
    }
  }

  @Test
  void neverUsesMoreThreadsThanItsSize() throws InterruptedException {
    try (EncoderPool pool = new EncoderPool(2)) {
      final Set<Thread> seen = ConcurrentHashMap.newKeySet();
      final AtomicLong total = new AtomicLong();
      // three encodes at once, each with parallel loops it waits for, the way an encoder's workers do
      final List<Thread> callers = new ArrayList<>();
      for (int caller = 0; caller < 3; caller++) {
        callers.add(
          Thread.ofPlatform()
            .start(() -> {
              try {
                pool.run(() -> {
                  final ForkJoinPool inner = ForkJoinTask.getPool();
                  final Workers workers = new Workers(inner, 4);
                  for (int loop = 0; loop < 5; loop++) {
                    workers.forEach(
                      16,
                      () -> seen,
                      (threads, item) -> {
                        threads.add(Thread.currentThread());
                        total.addAndGet(IntStream.range(0, 20_000).parallel().sum());
                      }
                    );
                  }
                  return null;
                });
              } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
            })
        );
      }
      for (final Thread caller : callers) {
        caller.join(TimeUnit.SECONDS.toMillis(60));
        assertTrue(!caller.isAlive());
      }
      assertEquals(3L * 5 * 16 * 199_990_000L, total.get());
      // every item ran on one of the budget's two threads, none on a caller or a spare thread
      assertTrue(seen.size() <= 2, "threads used: " + seen.size());
      for (final Thread thread : seen) {
        assertTrue(thread.getName().startsWith("mcav-mcv2-encoder-"), thread.getName());
      }
    }
  }

  @Test
  void blocksAThreadInsteadOfStartingASpare() throws InterruptedException {
    try (EncoderPool pool = new EncoderPool(1)) {
      final Set<Thread> seen = ConcurrentHashMap.newKeySet();
      final AtomicInteger blocks = new AtomicInteger();
      pool.run(() -> {
        seen.add(Thread.currentThread());
        // a pool asked to keep its threads running while one blocks would start a spare; the budget's does not
        ForkJoinPool.managedBlock(
          new ForkJoinPool.ManagedBlocker() {
            @Override
            public boolean block() {
              blocks.incrementAndGet();
              return true;
            }

            @Override
            public boolean isReleasable() {
              return false;
            }
          }
        );
        seen.add(Thread.currentThread());
        return null;
      });
      assertEquals(1, blocks.get());
      assertEquals(1, seen.size());
    }
  }

  @Test
  void runsANestedLoopOnASingleThread() throws InterruptedException {
    try (EncoderPool pool = new EncoderPool(1)) {
      final int sum = pool.run(() -> ForkJoinTask.getPool().submit(() -> IntStream.range(0, 1000).parallel().sum()).join());
      assertEquals(499_500, sum);
    }
  }

  @Test
  void encodesInsideTheBudget() throws InterruptedException {
    try (EncoderPool pool = new EncoderPool(2)) {
      final Mcv2Encoder encoder = pool.encoder(EncoderSettings.SHIP, true);
      final byte[] rgb = new byte[32 * 16 * 3];
      for (int i = 0; i < rgb.length; i++) {
        rgb[i] = (byte) (i * 7);
      }
      final byte[] inside = pool.run(() -> encoder.encode(rgb, 32, 16, 0));
      final Mcv2Encoder alone = new Mcv2Encoder(EncoderSettings.SHIP, ForkJoinPool.commonPool(), 1, true);
      assertArrayEquals(alone.encode(rgb, 32, 16, 0), inside);
    }
  }

  /** Whether a thrown exception is the original, or the copy of it the pool makes for the waiting thread. */
  private static boolean isOrWraps(final Throwable thrown, final Throwable original) {
    return thrown.equals(original) || original.equals(thrown.getCause());
  }

  @Test
  void passesOnWhatATaskThrows() {
    try (EncoderPool pool = new EncoderPool(1)) {
      final IllegalArgumentException runtime = new IllegalArgumentException("runtime");
      final IllegalArgumentException thrownRuntime = assertThrows(IllegalArgumentException.class, () ->
        pool.run(() -> {
          throw runtime;
        })
      );
      assertTrue(isOrWraps(thrownRuntime, runtime));
      final AssertionError error = new AssertionError("error");
      final AssertionError thrownError = assertThrows(AssertionError.class, () ->
        pool.run(() -> {
          throw error;
        })
      );
      assertTrue(isOrWraps(thrownError, error));
      final IOException checked = new IOException("checked");
      final IllegalStateException wrapped = assertThrows(IllegalStateException.class, () ->
        pool.run(() -> {
          throw checked;
        })
      );
      assertInstanceOf(IOException.class, wrapped.getCause());
      assertTrue(isOrWraps(wrapped.getCause(), checked));
      // a thread that dies outside any task is logged; the pool starts another
      EncoderPool.uncaught(Thread.currentThread(), new IllegalStateException("outside a task"));
      assertThrows(NullPointerException.class, () -> pool.run(null));
    }
  }

  @Test
  void cancelsTheTaskOfAnInterruptedCaller() throws InterruptedException {
    try (EncoderPool pool = new EncoderPool(1)) {
      final CountDownLatch started = new CountDownLatch(1);
      final CountDownLatch cancelled = new CountDownLatch(1);
      final AtomicReference<Throwable> thrown = new AtomicReference<>();
      final Thread caller = Thread.ofPlatform()
        .start(() -> {
          try {
            pool.run(() -> {
              started.countDown();
              try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(60));
              } catch (final InterruptedException exception) {
                cancelled.countDown();
              }
              return null;
            });
          } catch (final InterruptedException exception) {
            thrown.set(exception);
          }
        });
      assertTrue(started.await(30, TimeUnit.SECONDS));
      caller.interrupt();
      caller.join(TimeUnit.SECONDS.toMillis(30));
      assertTrue(!caller.isAlive());
      assertInstanceOf(InterruptedException.class, thrown.get());
      // the task is cancelled, which interrupts the budget's thread that runs it
      assertTrue(cancelled.await(30, TimeUnit.SECONDS));
    }
  }

  @Test
  void startsNothingForACallerInterruptedBefore() {
    try (EncoderPool pool = new EncoderPool(1)) {
      final AtomicReference<Thread> ran = new AtomicReference<>();
      Thread.currentThread().interrupt();
      assertThrows(InterruptedException.class, () ->
        pool.run(() -> {
          ran.set(Thread.currentThread());
          return null;
        })
      );
      assertFalse(Thread.currentThread().isInterrupted());
      assertNull(ran.get());
    }
  }

  @Test
  void refusesWorkOnceClosed() {
    final EncoderPool pool = new EncoderPool(1);
    pool.close();
    assertThrows(RejectedExecutionException.class, () -> pool.run(() -> 1));
  }

  @Test
  void sharesOneBudgetPerServer() {
    try {
      EncoderPool.setSharedThreads(3);
      final EncoderPool shared = EncoderPool.shared();
      assertSame(shared, EncoderPool.shared());
      assertEquals(3, shared.getThreads());
      // the same size keeps the budget; another size replaces it for the encoders created next
      EncoderPool.setSharedThreads(3);
      assertSame(shared, EncoderPool.shared());
      EncoderPool.setSharedThreads(2);
      assertNotSame(shared, EncoderPool.shared());
      assertEquals(2, EncoderPool.shared().getThreads());
      EncoderPool.setSharedThreads(0);
      assertEquals(EncoderPool.defaultThreads(Runtime.getRuntime().availableProcessors()), EncoderPool.shared().getThreads());
      assertThrows(IllegalArgumentException.class, () -> EncoderPool.setSharedThreads(-1));
      assertThrows(IllegalArgumentException.class, () -> EncoderPool.setSharedThreads(EncoderPool.MAX_THREADS + 1));
    } finally {
      EncoderPool.setSharedThreads(0);
    }
  }
}
