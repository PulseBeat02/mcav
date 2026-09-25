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
package me.brandonli.mcav.sandbox.command.interaction;

import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * A browser or virtual machine start that a second {@code create} supersedes while it runs. Pass 1 left this open: a
 * superseded start that then succeeded was never released, which leaks a Chrome or QEMU process. The screen now
 * decides who releases the player: the worker when the start was running as the screen was cancelled, the command
 * otherwise. This drives the real {@link Screen} exactly as {@code AbstractInteractiveCommand} does, from the worker
 * that runs the start and from the main thread that replaces the screen. The result is how often the player was
 * released, which must be exactly once whatever the order.
 */
@JCStressTest
@Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "the player was released exactly once")
@Outcome(id = "0", expect = Expect.FORBIDDEN, desc = "nobody released the player: a browser or QEMU process leaks")
@Outcome(id = "2", expect = Expect.FORBIDDEN, desc = "the player was released twice")
@State
public class StartCancelRace {

  private final Screen screen = new Screen(null, null, 0, 1);
  private final AtomicInteger releases = new AtomicInteger();

  /**
   * The worker running the start, as {@code runStartTask} and {@code finishStartTask} do: a start that begins releases
   * its player itself when the screen was cancelled while it ran.
   */
  @Actor
  public void runStart() {
    final boolean began = this.screen.beginStart();
    if (!began) {
      return;
    }
    final boolean cancelled = this.screen.finishStart();
    if (cancelled) {
      this.releases.incrementAndGet();
    }
  }

  /**
   * A second {@code create} replacing the screen, as {@code releaseCurrent} does: it releases the player unless the
   * worker owns the cleanup because the start is running.
   */
  @Actor
  public void supersede() {
    final boolean workerOwnsCleanup = this.screen.cancel();
    if (!workerOwnsCleanup) {
      this.releases.incrementAndGet();
    }
  }

  /**
   * Counts the releases.
   *
   * @param outcome how often the player was released
   */
  @Arbiter
  public void count(final I_Result outcome) {
    outcome.r1 = this.releases.get();
  }
}
