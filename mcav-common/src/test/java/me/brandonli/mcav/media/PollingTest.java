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
package me.brandonli.mcav.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PollingTest {

  private static final Duration TIMEOUT = Duration.ofMillis(100);

  @Test
  void shouldCheckUntilTheConditionHoldsAndThenStop() throws InterruptedException {
    final AtomicInteger checks = new AtomicInteger();
    final int holdingCheck = 3;
    final Duration longTimeout = Duration.ofSeconds(10);
    final boolean held = Polling.pollUntil(longTimeout, () -> checks.incrementAndGet() >= holdingCheck);
    final int count = checks.get();
    assertTrue(held);
    assertEquals(holdingCheck, count);
  }

  @Test
  void shouldReportFalseOnlyOnceTheTimeoutEnds() throws InterruptedException {
    final long before = System.nanoTime();
    final boolean held = Polling.pollUntil(TIMEOUT, () -> false);
    final long elapsed = System.nanoTime() - before;
    final boolean waitedOutTheTimeout = elapsed >= TIMEOUT.toNanos();
    assertFalse(held);
    assertTrue(waitedOutTheTimeout);
  }

  @Test
  void shouldStopWithAnInterruptedExceptionWhenInterrupted() {
    final Thread current = Thread.currentThread();
    current.interrupt();
    assertThrows(InterruptedException.class, () -> Polling.pollUntil(TIMEOUT, () -> false));

    final boolean stillInterrupted = Thread.interrupted();
    assertFalse(stillInterrupted);
  }

  @Test
  void shouldFailNamingWhatItWaitedForWhenTheTimeoutEnds() {
    final AssertionError thrown = assertThrows(AssertionError.class, () -> Polling.awaitCondition("the flag is set", TIMEOUT, () -> false));
    final String message = thrown.getMessage();
    final boolean namesTheCondition = message.contains("timed out waiting until the flag is set");
    assertTrue(namesTheCondition);
  }
}
