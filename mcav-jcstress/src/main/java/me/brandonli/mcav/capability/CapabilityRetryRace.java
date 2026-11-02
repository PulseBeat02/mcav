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
package me.brandonli.mcav.capability;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * Retrying a failed preparation changes an unavailable capability directly to preparing. A usability check racing
 * with that transition must refuse either state: there is no instant at which the capability is usable. Reading
 * separately for preparing and unavailable can instead miss both states and let playback start without its backend.
 */
@JCStressTest
@Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "the unavailable or preparing capability was refused")
@Outcome(expect = Expect.FORBIDDEN, desc = "a capability that was never usable passed its readiness guard")
@State
public class CapabilityRetryRace {

  private static final int REFUSED = 0;
  private static final int ALLOWED = 1;

  private final CapabilityGuard guard;

  /**
   * Starts with a failed preparation.
   */
  public CapabilityRetryRace() {
    this.guard = new CapabilityGuard();
    this.guard.markPrepared(Capability.VLC, Availability.UNAVAILABLE);
  }

  /**
   * Starts another preparation without publishing a successful outcome.
   */
  @Actor
  public void retry() {
    this.guard.markPreparing(Capability.VLC);
  }

  /**
   * Tries to use the capability before its retry succeeds.
   *
   * @param outcome whether the guard allowed use of the capability
   */
  @Actor
  public void use(final I_Result outcome) {
    try {
      this.guard.checkUsable(Capability.VLC);
      outcome.r1 = ALLOWED;
    } catch (final IllegalStateException exception) {
      outcome.r1 = REFUSED;
    }
  }
}
