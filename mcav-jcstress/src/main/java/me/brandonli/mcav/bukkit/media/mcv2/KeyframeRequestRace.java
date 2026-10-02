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
package me.brandonli.mcav.bukkit.media.mcv2;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.ZZ_Result;

/**
 * A keyframe request, such as the one the main thread makes when a viewer is shown the screen, while the encoder's
 * thread takes the requests for its next frame. A request is taken by that frame or waits for the next one; it is never
 * lost, which a take that read the request and cleared it in two steps allowed until the next periodic keyframe, seconds
 * later (pass-5 synthesis S40). This drives the requests {@link Mcv2Channel} keeps, as the channel itself cannot be
 * created without the Minecraft server. The results are whether the take saw the request, and whether it still waits
 * after.
 */
@JCStressTest
@Outcome(id = "true, false", expect = Expect.ACCEPTABLE, desc = "the frame took the request")
@Outcome(id = "false, true", expect = Expect.ACCEPTABLE, desc = "the request came after the take and waits for the next frame")
@Outcome(id = "false, false", expect = Expect.FORBIDDEN, desc = "the request was lost between the read and the clear of the take")
@State
public class KeyframeRequestRace {

  private final Mcv2KeyframeRequest requests = new Mcv2KeyframeRequest();

  /**
   * Requests a keyframe, as showing the screen to a viewer does.
   */
  @Actor
  public void request() {
    this.requests.request();
  }

  /**
   * Takes the requests for the next frame, as the encoder does.
   *
   * @param outcome whether the take saw the request
   */
  @Actor
  public void take(final ZZ_Result outcome) {
    outcome.r1 = this.requests.take();
  }

  /**
   * Checks whether the request still waits once both are done.
   *
   * @param outcome whether a request still waits
   */
  @Arbiter
  public void after(final ZZ_Result outcome) {
    outcome.r2 = this.requests.take();
  }
}
