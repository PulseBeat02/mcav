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
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Mode;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Signal;
import org.openjdk.jcstress.annotations.State;

/**
 * The screen's thread waits for a frame while the result is released, which closes the frame slot of
 * {@link Mcv2Result}: the close must wake the waiting thread, or a release would wait for it in vain (pass-5 S59).
 */
@JCStressTest(Mode.Termination)
@Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "the close woke the waiting thread")
@Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "the thread kept waiting after the close")
@State
public class LatestFrameCloseRace {

  private final Mcv2LatestFrame<Integer> slot;

  /**
   * Creates an open slot without a frame, as a started result has.
   */
  public LatestFrameCloseRace() {
    this.slot = new Mcv2LatestFrame<>();
    this.slot.open();
  }

  /**
   * Waits for a frame, as the screen's thread does.
   */
  @Actor
  public void screen() {
    try {
      this.slot.take();
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    }
  }

  /**
   * Closes the slot, as releasing the result does.
   */
  @Signal
  public void release() {
    this.slot.close();
  }
}
