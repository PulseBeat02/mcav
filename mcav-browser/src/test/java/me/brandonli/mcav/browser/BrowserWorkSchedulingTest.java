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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tests backpressure and fairness without launching a native browser. */
final class BrowserWorkSchedulingTest {

  @Test
  void retainsOnlyTheNewestCaptureNotificationBehindABlockedDecoder() throws InterruptedException {
    final ThreadPoolExecutor capture = SeleniumPlayer.createCaptureExecutor();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch finish = new CountDownLatch(1);
    final List<Integer> decoded = new ArrayList<>();
    try {
      capture.execute(() -> block(entered, finish));
      final boolean decoding = entered.await(5, TimeUnit.SECONDS);
      assertTrue(decoding);
      for (int frame = 0; frame < 10_000; frame++) {
        final int number = frame;
        capture.execute(() -> decoded.add(number));
      }
      finish.countDown();
      capture.shutdown();
      final boolean stopped = capture.awaitTermination(5, TimeUnit.SECONDS);
      final List<Integer> expected = List.of(9999);
      assertTrue(stopped);
      assertEquals(expected, decoded);
      capture.execute(() -> decoded.add(-1));
      assertEquals(expected, decoded, "late notifications are discarded once capture shuts down");
    } finally {
      finish.countDown();
      capture.shutdownNow();
    }
  }

  @Test
  void boundsSeleniumInputAndPreservesAcceptedOrder() throws InterruptedException {
    final ThreadPoolExecutor input = SeleniumPlayer.createInputExecutor();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch finish = new CountDownLatch(1);
    final List<Integer> delivered = new ArrayList<>();
    final List<Integer> accepted = new ArrayList<>();
    try {
      input.execute(() -> block(entered, finish));
      final boolean performing = entered.await(5, TimeUnit.SECONDS);
      assertTrue(performing);
      for (int number = 0; number < 128; number++) {
        final int sequence = number;
        input.execute(() -> delivered.add(sequence));
        accepted.add(sequence);
      }
      assertThrows(RejectedExecutionException.class, () -> input.execute(() -> delivered.add(-1)));
      finish.countDown();
      input.shutdown();
      final boolean stopped = input.awaitTermination(5, TimeUnit.SECONDS);
      assertTrue(stopped);
      assertEquals(accepted, delivered);
    } finally {
      finish.countDown();
      input.shutdownNow();
    }
  }

  @Test
  void boundsPlaywrightInputWithoutReorderingAcceptedActions() {
    final PlaywrightPlayer.Session session = new PlaywrightPlayer.Session();
    final List<Integer> delivered = new ArrayList<>();
    final List<Integer> accepted = new ArrayList<>();
    for (int number = 0; number < 128; number++) {
      final int sequence = number;
      final boolean queued = session.queue(() -> delivered.add(sequence));
      assertTrue(queued);
      accepted.add(sequence);
    }
    final boolean excess = session.queue(() -> delivered.add(-1));
    assertFalse(excess);
    for (int batch = 0; batch < 8; batch++) {
      session.runQueuedInput();
      assertEquals((batch + 1) * 16, delivered.size());
    }
    assertEquals(accepted, delivered);
  }

  @Test
  void yieldsToTheBrowserPumpWhenInputKeepsRefillingTheQueue() {
    final PlaywrightPlayer.Session session = new PlaywrightPlayer.Session();
    final AtomicInteger delivered = new AtomicInteger();
    final Runnable continuousInput = new Runnable() {
      @Override
      public void run() {
        delivered.incrementAndGet();
        session.queue(this);
      }
    };
    session.queue(continuousInput);
    try {
      final Duration timeout = Duration.ofSeconds(2);
      assertTimeoutPreemptively(timeout, session::runQueuedInput);
      assertEquals(16, delivered.get(), "a continuously refilled input queue must still yield to browser events");
    } finally {
      session.stopInput();
    }
    session.runQueuedInput();
    assertEquals(16, delivered.get());
    final boolean afterStop = session.queue(continuousInput);
    assertFalse(afterStop);
  }

  private static void block(final CountDownLatch entered, final CountDownLatch finish) {
    entered.countDown();
    try {
      final boolean finished = finish.await(5, TimeUnit.SECONDS);
      assertTrue(finished, "test must unblock the worker");
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }
}
