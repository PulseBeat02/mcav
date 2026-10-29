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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

final class ExecutorUtilsTest {

  private static final long TERMINATION_TIMEOUT_SECONDS = 5L;
  private static final long DEFAULT_WAIT_MILLISECONDS = 5_000L;

  private static void spinUntil(final AtomicBoolean release) {
    while (!release.get()) {
      Thread.onSpinWait();
    }
  }

  @Test
  void shouldReturnTrueWhenTheTasksFinishInTime() {
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
  void shouldInterruptTasksThatDoNotFinishInTime() throws InterruptedException {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final CountDownLatch started = new CountDownLatch(1);
    final AtomicBoolean release = new AtomicBoolean();
    executor.execute(() -> this.signalStartAndSpin(started, release));
    final AtomicBoolean queuedRan = new AtomicBoolean();
    executor.execute(() -> queuedRan.set(true));
    started.await();
    final Duration timeout = Duration.ofMillis(50);
    final boolean terminated = ExecutorUtils.shutdownExecutorGracefully(executor, timeout);
    release.set(true);
    final boolean finished = executor.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean ranQueued = queuedRan.get();
    assertFalse(terminated);
    assertTrue(finished);
    assertFalse(ranQueued, "tasks that had not started are cancelled");
  }

  private void signalStartAndSpin(final CountDownLatch started, final AtomicBoolean release) {
    started.countDown();
    spinUntil(release);
  }

  @Test
  void shouldKeepTheInterruptWhenInterruptedWhileWaiting() throws InterruptedException {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch blocker = new CountDownLatch(1);
    executor.execute(() -> this.signalStartAndAwaitBlocker(started, blocker));
    started.await();
    final Thread current = Thread.currentThread();
    current.interrupt();
    final boolean terminated = ExecutorUtils.shutdownExecutorGracefully(executor);
    final boolean interrupted = Thread.interrupted();
    final boolean finished = executor.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertFalse(terminated);
    assertTrue(interrupted, "the interrupt must be restored");
    assertTrue(finished, "shutdownNow must interrupt the running task");
  }

  private void signalStartAndAwaitBlocker(final CountDownLatch started, final CountDownLatch blocker) {
    started.countDown();
    try {
      blocker.await();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  @Test
  void shouldWaitOnceForFiveSecondsByDefault() {
    final RecordingExecutor executor = new RecordingExecutor();
    final boolean terminated = ExecutorUtils.shutdownExecutorGracefully(executor);
    final List<Long> waits = executor.getWaits();
    final int interruptions = executor.getInterruptions();
    assertFalse(terminated);
    assertEquals(List.of(DEFAULT_WAIT_MILLISECONDS), waits);
    assertEquals(1, interruptions);
  }

  @Test
  void shouldWaitForTheTimeoutInWholeMilliseconds() {
    final RecordingExecutor executor = new RecordingExecutor();
    ExecutorUtils.shutdownExecutorGracefully(executor, Duration.ofNanos(1_999_999));
    ExecutorUtils.shutdownExecutorGracefully(executor, Duration.ZERO);
    ExecutorUtils.shutdownExecutorGracefully(executor, Duration.ofMillis(-1));
    final List<Long> waits = executor.getWaits();
    assertEquals(List.of(1L, 0L, -1L), waits);
  }

  @Test
  void shouldLetAFailureOfTheExecutorPropagate() {
    final RecordingExecutor executor = new RecordingExecutor();
    final IllegalStateException failure = new IllegalStateException("Executor broke!");
    executor.failWith(failure);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      ExecutorUtils.shutdownExecutorGracefully(executor)
    );
    assertSame(failure, thrown);
  }

  @Test
  void shouldRejectATimeoutTooLongForMilliseconds() {
    final RecordingExecutor executor = new RecordingExecutor();
    final Duration timeout = Duration.ofSeconds(Long.MAX_VALUE);
    assertThrows(ArithmeticException.class, () -> ExecutorUtils.shutdownExecutorGracefully(executor, timeout));
  }

  @Test
  void shouldRejectMissingArguments() {
    final Duration timeout = Duration.ofSeconds(1);
    assertThrows(NullPointerException.class, () -> ExecutorUtils.shutdownExecutorGracefully(null));
    assertThrows(NullPointerException.class, () -> ExecutorUtils.shutdownExecutorGracefully(null, timeout));

    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      assertThrows(NullPointerException.class, () -> ExecutorUtils.shutdownExecutorGracefully(executor, null));
    }
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(ExecutorUtils.class);
  }

  private static final class RecordingExecutor extends AbstractExecutorService {

    private final List<Long> waits;

    private @Nullable RuntimeException failure;
    private int interruptions;
    private boolean shutdown;

    private RecordingExecutor() {
      this.waits = new ArrayList<>();
      this.failure = null;
      this.interruptions = 0;
      this.shutdown = false;
    }

    void failWith(final RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) {
      final long milliseconds = unit.toMillis(timeout);
      this.waits.add(milliseconds);

      final @Nullable RuntimeException failure = this.failure;
      if (failure != null) {
        throw failure;
      }

      return false;
    }

    @Override
    public List<Runnable> shutdownNow() {
      this.interruptions++;

      return List.of();
    }

    @Override
    public void shutdown() {
      this.shutdown = true;
    }

    @Override
    public boolean isShutdown() {
      return this.shutdown;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public void execute(final Runnable command) {
      throw new UnsupportedOperationException("The recording executor runs no tasks!");
    }

    List<Long> getWaits() {
      return this.waits;
    }

    int getInterruptions() {
      return this.interruptions;
    }
  }
}
