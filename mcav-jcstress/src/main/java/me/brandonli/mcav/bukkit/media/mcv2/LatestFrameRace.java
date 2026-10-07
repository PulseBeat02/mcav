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
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * The video of a screen hands two frames over while the screen's thread takes twice, as {@link Mcv2Result} hands
 * frames to its encoder through a {@link Mcv2LatestFrame}. A frame may be replaced before it is taken, but the newest
 * is never lost, no frame is taken twice, and an older frame never follows a newer one (pass-5 S59). The results are
 * the frames the two takes got and what is left once both are done, 0 for none.
 */
@JCStressTest
@Outcome(id = { "0, 0, 2", "0, 1, 2", "1, 0, 2" }, expect = Expect.ACCEPTABLE, desc = "the newest frame waits for the next take")
@Outcome(id = { "0, 2, 0", "1, 2, 0", "2, 0, 0" }, expect = Expect.ACCEPTABLE, desc = "the newest frame was taken")
@Outcome(expect = Expect.FORBIDDEN, desc = "the newest frame was lost, a frame was taken twice, or an older one after a newer")
@State
public class LatestFrameRace {

  private final Mcv2LatestFrame<Integer> slot = new Mcv2LatestFrame<>();

  /**
   * Hands two frames over, as the video does.
   */
  @Actor
  public void video() {
    this.slot.offer(1);
    this.slot.offer(2);
  }

  /**
   * Takes twice, as the screen's thread does.
   *
   * @param outcome the frames taken
   */
  @Actor
  public void screen(final III_Result outcome) {
    outcome.r1 = number(this.slot.poll());
    outcome.r2 = number(this.slot.poll());
  }

  /**
   * Takes what is left.
   *
   * @param outcome the frame left
   */
  @Arbiter
  public void after(final III_Result outcome) {
    outcome.r3 = number(this.slot.poll());
  }

  private static int number(final Integer frame) {
    return frame == null ? 0 : frame;
  }
}
