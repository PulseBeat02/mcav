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
package me.brandonli.mcav.http;

import java.util.List;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * A browser whose WebSocket handshake finishes while the audio server stops. Pass 1 found that {@code addListener} took
 * no lock and checked no flag, so a listener registered after {@code stop()} emptied the map stayed registered forever,
 * with its sender thread parked for good; {@code acceptingListeners}, checked before and after the registration,
 * closed it. The first result is the number of listeners left once both are done, the second whether the handshake
 * was refused before a listener was created at all.
 */
@JCStressTest
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "stop came first: the handshake was refused and its session closed")
@Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "the listener was registered, then disconnected by stop or by itself")
@Outcome(id = "1, .*", expect = Expect.FORBIDDEN, desc = "a listener outlived stop(): its sender thread parks forever")
@State
public class ListenerStopRace {

  private final HttpResultImpl result = new HttpResultImpl("localhost", 0, null, List.of());
  private final RecordingSession session = new RecordingSession();

  /**
   * Stops the server, which was never started, exactly as a plugin that shuts down does.
   */
  @Actor
  public void stop() {
    this.result.stop();
  }

  /**
   * Finishes the handshake of a browser.
   */
  @Actor
  public void connect() {
    this.result.addListener(this.session.proxy());
  }

  /**
   * Records what is left once both are done.
   *
   * @param outcome the listener count and whether the handshake was refused
   */
  @Arbiter
  public void inspect(final II_Result outcome) {
    outcome.r1 = this.result.getListenerCount();
    outcome.r2 = this.session.wasClosedSynchronously() ? 1 : 0;
    // a leaked listener would keep its sender thread; stopping again releases it whatever the outcome
    this.result.stop();
  }
}
