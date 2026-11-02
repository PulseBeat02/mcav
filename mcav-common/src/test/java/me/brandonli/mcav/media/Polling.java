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

// Waits in tests for state that another thread changes without signalling it, such as a counter that a pipeline running
// on a playback thread increments. The condition is checked every few milliseconds until it holds or the timeout ends.
public final class Polling {

  private static final long POLL_INTERVAL_NANOSECONDS = 5_000_000L;

  private Polling() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static boolean pollUntil(final Duration timeout, final BooleanSupplier condition) throws InterruptedException {
    final long timeoutNanoseconds = timeout.toNanos();
    final long deadline = System.nanoTime() + timeoutNanoseconds;
    while (!condition.getAsBoolean()) {
      final long now = System.nanoTime();
      if (now - deadline >= 0) {
        return false;
      }
      LockSupport.parkNanos(POLL_INTERVAL_NANOSECONDS);
      if (Thread.interrupted()) {
        final String message = "Polling was interrupted!";
        throw new InterruptedException(message);
      }
    }

    return true;
  }

  public static void awaitCondition(final String description, final Duration timeout, final BooleanSupplier condition)
    throws InterruptedException {
    final boolean held = pollUntil(timeout, condition);
    assertTrue(held, "timed out waiting until " + description);
  }
}
