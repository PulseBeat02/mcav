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
package me.brandonli.mcav.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ExecutorUtils}.
 */
final class ExecutorUtilsTest {

  private static void spinUntil(final AtomicBoolean release) {
    while (!release.get()) {
      Thread.onSpinWait();
    }
  }

  @Test
  void returnsTrueWhenTheTasksFinishInTime() {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final CountDownLatch ran = new CountDownLatch(1);
    executor.execute(ran::countDown);
    final boolean terminated = ExecutorUtils.shutdownExecutorGracefully(executor);
    final boolean isTerminated = executor.isTerminated();
    final long remaining = ran.getCount();
    assertTrue(terminated);
    assertTrue(isTerminated);
    assertEquals(0L, remaining, "the task ran before the executor terminated");
  }

  @Test
  void interruptsTasksThatDoNotFinishInTime() throws InterruptedException {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final CountDownLatch started = new CountDownLatch(1);
    final AtomicBoolean release = new AtomicBoolean();
    executor.execute(() -> {
      started.countDown();
      spinUntil(release);
    });
    final AtomicBoolean queuedRan = new AtomicBoolean();
    executor.execute(() -> queuedRan.set(true));
    started.await();
    final Duration timeout = Duration.ofMillis(50);
    final boolean terminated = ExecutorUtils.shutdownExecutorGracefully(executor, timeout);
    release.set(true);
    final boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);
    final boolean ranQueued = queuedRan.get();
    assertFalse(terminated);
    assertTrue(finished);
    assertFalse(ranQueued, "tasks that had not started are cancelled");
  }

  @Test
  void keepsTheInterruptWhenInterruptedWhileWaiting() throws InterruptedException {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch blocker = new CountDownLatch(1);
    executor.execute(() -> {
      started.countDown();
      try {
        blocker.await();
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();
      }
    });
    started.await();
    final Thread current = Thread.currentThread();
    current.interrupt();
    final boolean terminated = ExecutorUtils.shutdownExecutorGracefully(executor);
    final boolean interrupted = Thread.interrupted();
    final boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);
    assertFalse(terminated);
    assertTrue(interrupted, "the interrupt must be restored");
    assertTrue(finished, "shutdownNow must interrupt the running task");
  }

  @Test
  void rejectsMissingArguments() {
    final Duration timeout = Duration.ofSeconds(1);
    assertThrows(NullPointerException.class, () -> ExecutorUtils.shutdownExecutorGracefully(null));
    assertThrows(NullPointerException.class, () -> ExecutorUtils.shutdownExecutorGracefully(null, timeout));
    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      assertThrows(NullPointerException.class, () -> ExecutorUtils.shutdownExecutorGracefully(executor, null));
    }
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(ExecutorUtils.class);
  }
}
