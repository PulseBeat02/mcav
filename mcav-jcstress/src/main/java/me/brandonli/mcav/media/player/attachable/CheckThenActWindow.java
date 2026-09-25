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
package me.brandonli.mcav.media.player.attachable;

import me.brandonli.mcav.utils.immutable.Dimension;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * The pattern pass 1 removed from four places, run against the real callback: ask whether a size is attached, then ask
 * for it. This is not code of mcav any more; it shows that the window between the two calls is real under actual
 * scheduling, which is why every place now reads the size once. No place of mcav still asks {@code isAttached()}
 * before {@code retrieve()}; {@code grep} finds the method only in its declaration and implementation.
 */
@JCStressTest
@Outcome(id = "320, 240", expect = Expect.ACCEPTABLE, desc = "attached, and still attached when the size was read")
@Outcome(id = "-1, -1", expect = Expect.ACCEPTABLE, desc = "already detached when asked")
@Outcome(
  id = "0, 0",
  expect = Expect.ACCEPTABLE_INTERESTING,
  desc = "the removed bug: attached when asked, detached when read, so the size is the empty fallback"
)
@State
public class CheckThenActWindow {

  private final DimensionAttachableCallback dimensionCallback;

  /**
   * Creates a callback with a size attached.
   */
  public CheckThenActWindow() {
    final Dimension target = Dimension.of(320, 240);
    this.dimensionCallback = DimensionAttachableCallback.create();
    this.dimensionCallback.attach(target);
  }

  /**
   * Checks, then acts, as the four places did before pass 1.
   *
   * @param outcome the size that was used, or -1 when the callback said it was detached
   */
  @Actor
  public void checkThenRead(final II_Result outcome) {
    final boolean attached = this.dimensionCallback.isAttached();
    if (!attached) {
      outcome.r1 = -1;
      outcome.r2 = -1;
      return;
    }
    final Dimension size = this.dimensionCallback.retrieve();
    outcome.r1 = size.getWidth();
    outcome.r2 = size.getHeight();
  }

  /**
   * Detaches the size.
   */
  @Actor
  public void detach() {
    this.dimensionCallback.detach();
  }
}
