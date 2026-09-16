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
package me.brandonli.mcav.media.player.multimedia.vlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link RenderThread}.
 */
final class RenderThreadTest {

  private static final long TIMEOUT_SECONDS = 5L;
  private static final String THREAD_NAME = "test-render";

  /**
   * Creates a render thread whose steps must never fail.
   */
  private static RenderThread strictThread() {
    return new RenderThread(THREAD_NAME, failure -> {
      throw new AssertionError("no step of this test fails", failure);
    });
  }

  /**
   * Creates a step that fails the first time it runs and counts down the latch the third time.
   */
  private static RenderThread.Step failFirstThenCount(
    final AtomicInteger steps,
    final RuntimeException failure,
    final CountDownLatch third
  ) {
    return () -> {
      final int step = steps.incrementAndGet();
      if (step == 1) {
        throw failure;
      }
      if (step == 3) {
        third.countDown();
      }
      Thread.sleep(5);
    };
  }

  @Test
  void reportsStepsThatFailAndGoesOnWithTheNextStep() throws Exception {
    final List<RuntimeException> failures = Collections.synchronizedList(new ArrayList<>());
    final RenderThread thread = new RenderThread(THREAD_NAME, failures::add);
    final AtomicInteger steps = new AtomicInteger();
    final CountDownLatch third = new CountDownLatch(1);
    final IllegalStateException failure = new IllegalStateException("broken frame");
    final RenderThread.Step step = failFirstThenCount(steps, failure, third);
    thread.start(step, () -> {});
    try {
      final boolean continued = third.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      final boolean running = thread.isRunning();
      final List<RuntimeException> expected = List.of(failure);
      assertTrue(continued, "the loop goes on after a failing step");
      assertTrue(running);
      assertEquals(expected, failures);
    } finally {
      thread.stop();
    }
  }

  /**
   * Creates a step that records the thread it runs on and, from the third step on, counts down the latch and blocks
   * until it is interrupted.
   */
  private static RenderThread.Step blockingFromTheThirdStep(
    final AtomicReference<Thread> worker,
    final AtomicInteger steps,
    final CountDownLatch blocked
  ) {
    final CountDownLatch never = new CountDownLatch(1);
    return () -> {
      final Thread current = Thread.currentThread();
      worker.set(current);
      final int step = steps.incrementAndGet();
      if (step >= 3) {
        blocked.countDown();
        never.await();
      }
    };
  }

  /**
   * Checks that the worker thread of a stopped render thread exited, was a daemon, and had the thread name.
   */
  private static void assertWorkerExited(final Thread workerThread) {
    final boolean alive = workerThread.isAlive();
    final boolean daemon = workerThread.isDaemon();
    final String name = workerThread.getName();
    assertFalse(alive);
    assertTrue(daemon);
    assertEquals(THREAD_NAME, name);
  }

  @Test
  void runsTheStepUntilStoppedAndWaitsForTheThreadToExit() throws Exception {
    final RenderThread thread = strictThread();
    final AtomicInteger steps = new AtomicInteger();
    final CountDownLatch blocked = new CountDownLatch(1);
    final AtomicBoolean cleanedUp = new AtomicBoolean();
    final AtomicReference<Thread> worker = new AtomicReference<>();
    final RenderThread.Step step = blockingFromTheThirdStep(worker, steps, blocked);
    thread.start(step, () -> cleanedUp.set(true));

    final boolean runningBeforeStop = thread.isRunning();
    final boolean reachedThirdStep = blocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    thread.stop();

    final boolean runningAfterStop = thread.isRunning();
    final boolean cleaned = cleanedUp.get();
    final Thread workerThread = worker.get();
    final int stepCount = steps.get();
    assertTrue(runningBeforeStop);
    assertTrue(reachedThirdStep);
    assertFalse(runningAfterStop);
    assertTrue(cleaned, "stop waits until the thread has cleaned up");
    assertWorkerExited(workerThread);
    assertEquals(3, stepCount);
  }

  @Test
  void endsTheLoopAfterTheCurrentStepWhenStoppedFromItself() throws Exception {
    final RenderThread thread = strictThread();
    final AtomicInteger steps = new AtomicInteger();
    final CountDownLatch cleanedUp = new CountDownLatch(1);
    final AtomicBoolean interruptedInCleanup = new AtomicBoolean();
    thread.start(
      () -> {
        steps.incrementAndGet();
        thread.stop();
      },
      () -> {
        final Thread current = Thread.currentThread();
        final boolean interrupted = current.isInterrupted();
        interruptedInCleanup.set(interrupted);
        cleanedUp.countDown();
      }
    );

    final boolean cleaned = cleanedUp.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final int stepCount = steps.get();
    final boolean interrupted = interruptedInCleanup.get();
    final boolean running = thread.isRunning();
    assertTrue(cleaned, "a thread that stops itself does not wait for itself");
    assertEquals(1, stepCount);
    assertTrue(interrupted, "the interrupt of the stop is kept");
    assertFalse(running);
  }

  /**
   * Creates a stubborn step that ignores interrupts until it is released, and then ends the loop.
   */
  private static RenderThread.Step stubbornStep(final CountDownLatch started, final CountDownLatch release) {
    return () -> {
      started.countDown();
      while (release.getCount() > 0) {
        try {
          release.await();
        } catch (final InterruptedException exception) {
          // keep waiting
        }
      }
      throw new InterruptedException("released");
    };
  }

  @Test
  void stopsWaitingWhenTheCallerIsInterrupted() throws Exception {
    final RenderThread thread = strictThread();
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch cleanedUp = new CountDownLatch(1);
    final RenderThread.Step step = stubbornStep(started, release);
    thread.start(step, cleanedUp::countDown);
    final boolean stepStarted = started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    final Thread caller = Thread.currentThread();
    caller.interrupt();
    final long start = System.nanoTime();
    thread.stop();
    final long end = System.nanoTime();
    final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(end - start);
    final boolean callerInterrupted = Thread.interrupted();

    release.countDown();
    final boolean cleaned = cleanedUp.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(stepStarted);
    assertTrue(callerInterrupted, "the interrupt of the caller is kept");
    assertTrue(elapsedMillis < 1_000L, "an interrupted caller does not wait, took " + elapsedMillis);
    assertTrue(cleaned);
  }

  @Test
  void stoppingAThreadThatNeverStartedDoesNothing() {
    final RenderThread thread = strictThread();
    final boolean runningBeforeStop = thread.isRunning();
    thread.stop();
    thread.stop();
    final boolean runningAfterStop = thread.isRunning();
    assertFalse(runningBeforeStop);
    assertFalse(runningAfterStop);
  }

  @Test
  void cannotBeStartedTwice() {
    final RenderThread thread = strictThread();
    final RenderThread.Step idle = () -> Thread.sleep(10);
    thread.start(idle, () -> {});
    try {
      assertThrows(IllegalStateException.class, () -> thread.start(idle, () -> {}));
      thread.stop();
      assertThrows(IllegalStateException.class, () -> thread.start(idle, () -> {}), "a stopped thread cannot be restarted");
    } finally {
      thread.stop();
    }
  }
}
