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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Waits in tests for state that another thread changes without signalling it, such as a counter that a pipeline running
 * on a playback thread increments. The condition is checked every few milliseconds until it holds or the timeout ends.
 */
public final class Polling {

  private static final long POLL_INTERVAL_NANOS = 5_000_000L;

  private Polling() {
    throw new UnsupportedOperationException("utility class");
  }

  /**
   * Waits until the condition holds or the timeout ends, whichever comes first.
   *
   * @param timeout   how long to wait at most
   * @param condition the condition to wait for, checked at least once
   * @return true if the condition held, false if the timeout ended first
   * @throws InterruptedException if the waiting thread is interrupted
   */
  public static boolean pollUntil(final Duration timeout, final BooleanSupplier condition) throws InterruptedException {
    final long timeoutNanos = timeout.toNanos();
    final long deadline = System.nanoTime() + timeoutNanos;
    while (!condition.getAsBoolean()) {
      final long now = System.nanoTime();
      if (now - deadline >= 0) {
        return false;
      }
      LockSupport.parkNanos(POLL_INTERVAL_NANOS);
      if (Thread.interrupted()) {
        throw new InterruptedException("interrupted while polling");
      }
    }
    return true;
  }

  /**
   * Waits until the condition holds, and fails the test if the timeout ends first.
   *
   * @param description what the condition means, completing the sentence "timed out waiting until ..."
   * @param timeout     how long to wait at most
   * @param condition   the condition to wait for, checked at least once
   * @throws InterruptedException if the waiting thread is interrupted
   */
  public static void awaitCondition(final String description, final Duration timeout, final BooleanSupplier condition)
    throws InterruptedException {
    final boolean held = pollUntil(timeout, condition);
    assertTrue(held, "timed out waiting until " + description);
  }
}
