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
package me.brandonli.mcav.http.testing;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Polls a condition until it holds or a timeout passes.
 */
public final class Await {

  private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
  private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

  private Await() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Waits until a condition holds, failing the test after ten seconds.
   *
   * @param description what is waited for, used in the failure message
   * @param condition   the condition
   */
  public static void until(final String description, final BooleanSupplier condition) {
    final long start = System.nanoTime();
    final long deadline = start + TIMEOUT_NANOS;
    while (!condition.getAsBoolean()) {
      final long now = System.nanoTime();
      if (now - deadline > 0) {
        fail("Timed out waiting until " + description);
      }
      LockSupport.parkNanos(POLL_NANOS);
    }
  }
}
