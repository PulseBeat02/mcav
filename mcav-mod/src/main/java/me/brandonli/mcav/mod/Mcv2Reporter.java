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
package me.brandonli.mcav.mod;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.checkerframework.checker.nullness.qual.Nullable;

// reports once Iris has said the same for a second: Iris has no shader pipeline for a moment while it reloads a
// pack or the world changes, and the server limits the rate of reports
final class Mcv2Reporter {

  static final long STEADY_NANOSECONDS = TimeUnit.SECONDS.toNanos(1);

  private final IrisShaders shaders;

  private final ReportChannel channel;

  private final LongSupplier clock;

  private @Nullable Mcv2Report seen;

  private long seenSince;

  private @Nullable Mcv2Report sent;

  Mcv2Reporter(final IrisShaders shaders, final ReportChannel channel, final LongSupplier clock) {
    this.shaders = shaders;
    this.channel = channel;
    this.clock = clock;
  }

  void reset() {
    this.seen = null;
    this.sent = null;
  }

  void tick() {
    if (!this.channel.isOpen()) {
      return;
    }

    final long now = this.clock.getAsLong();
    final Mcv2Report report = this.shaders.report();
    if (!Objects.equals(this.seen, report)) {
      this.seen = report;
      this.seenSince = now;
    }

    if (Objects.equals(this.sent, report) || now - this.seenSince < STEADY_NANOSECONDS) {
      return;
    }

    this.channel.send(report.toBytes());
    this.sent = report;
  }
}
