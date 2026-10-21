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

import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

// The page decides how often the helper reports, and players how often input is dropped, so neither may fill the
// log of the server: BURST lines at once, then one per second, with the skipped lines counted.
final class LogBudget {

  static final int BURST = 32;

  static final long REFILL_NANOSECONDS = TimeUnit.SECONDS.toNanos(1);

  private final LongSupplier clock;

  private long available;
  private long refilled;
  private long skipped;

  LogBudget(final LongSupplier clock) {
    this.clock = clock;
    this.available = BURST;
    this.refilled = clock.getAsLong();
  }

  synchronized void log(final Runnable line, final LongConsumer skipped) {
    final long refills = Math.max(0L, this.clock.getAsLong() - this.refilled) / REFILL_NANOSECONDS;
    this.available = Math.min(BURST, this.available + refills);
    this.refilled += refills * REFILL_NANOSECONDS;

    if (this.available == 0) {
      this.skipped++;
      return;
    }

    this.available--;
    if (this.skipped > 0) {
      skipped.accept(this.skipped);
      this.skipped = 0;
    }

    line.run();
  }
}
