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
package me.brandonli.mcav.lwjgl.testing;

import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.base.Preconditions;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

public final class Await {

  private static final long TIMEOUT_MILLISECONDS = 10_000L;
  private static final long POLL_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(5);

  private Await() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  public static void until(final String description, final BooleanSupplier condition) {
    Preconditions.checkNotNull(description, "Description must not be null!");
    Preconditions.checkNotNull(condition, "Condition must not be null!");

    final long timeout = TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLISECONDS);
    final long deadline = System.nanoTime() + timeout;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - deadline > 0) {
        final String message = "Waiting until %s timed out!".formatted(description);
        fail(message);
      }
      LockSupport.parkNanos(POLL_NANOSECONDS);
    }
  }
}
