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

/**
 * Tells the server what Iris says, once the channel is open on each connection and again whenever it changes. A report
 * goes out once Iris has said the same for a second: Iris has no shader pipeline for a moment while it reloads one or
 * the player changes world, which would otherwise tell the server the shaders went off and on. That also keeps the
 * reports to at most one a second, under the rate the server allows. Runs on the client thread.
 */
final class Mcv2Reporter {

  /** How long Iris must say the same before it is reported. */
  static final long STEADY_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final IrisShaders shaders;

  private final ReportChannel channel;

  private final LongSupplier clock;

  private @Nullable Mcv2Report seen;

  private long seenSince;

  private @Nullable Mcv2Report sent;

  /**
   * Constructs the reporter of a client.
   *
   * @param shaders asks Iris
   * @param channel the channel to the server
   * @param clock   the clock, in nanoseconds
   */
  Mcv2Reporter(final IrisShaders shaders, final ReportChannel channel, final LongSupplier clock) {
    this.shaders = shaders;
    this.channel = channel;
    this.clock = clock;
  }

  /** Starts over on a new connection, whose server has been told nothing. */
  void reset() {
    this.seen = null;
    this.sent = null;
  }

  /** Asks Iris, and sends a report that held a second and the server has not been sent. Called every client tick. */
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
    if (Objects.equals(this.sent, report) || now - this.seenSince < STEADY_NANOS) {
      return;
    }
    this.channel.send(report.toBytes());
    this.sent = report;
  }
}
