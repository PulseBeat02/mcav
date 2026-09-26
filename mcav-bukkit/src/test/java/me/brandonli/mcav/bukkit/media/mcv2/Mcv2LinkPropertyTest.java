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

import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/** Whatever frames come and however the connection drains, a viewer is only sent frames it can decode. */
final class Mcv2LinkPropertyTest {

  private static final String SEED = "20260926";

  /** One step of a stream: a frame (its size, and whether it is a keyframe) and how much the connection wrote before. */
  record Step(boolean keyframe, int bytes, int drained) {}

  @Provide
  Arbitrary<List<Step>> streams() {
    final Arbitrary<Step> step = Combinators.combine(
      Arbitraries.integers().between(0, 9).map(i -> i == 0),
      Arbitraries.integers().between(0, 400),
      Arbitraries.integers().between(0, 500)
    ).as(Step::new);
    return step.list().ofMinSize(1).ofMaxSize(200);
  }

  /**
   * Simulates an encoder with either reference policy and a client model, and checks that every frame sent is one
   * the client could decode, that nothing is sent while the backlog is over the limit (twice it for a keyframe), and
   * that the backlog is what was sent and not yet written.
   */
  @Property(seed = SEED, tries = 400)
  boolean sendsOnlyFramesTheViewerCanDecode(
    @ForAll("streams") final List<Step> steps,
    @ForAll @IntRange(min = 0, max = 1000) final int limit,
    @ForAll final boolean fromKeyframe
  ) {
    final Mcv2Link link = new Mcv2Link(limit);
    // the client: the last frame it decoded and the last keyframe it decoded, as its status pass keeps them
    long clientLast = -1;
    long clientKey = -1;
    long lastKey = -1;
    long outstanding = 0;
    final List<Long> inFlight = new ArrayList<>();
    for (int id = 0; id < steps.size(); id++) {
      final Step step = steps.get(id);
      // the connection writes some of what it holds, frame by frame
      long budget = step.drained();
      while (!inFlight.isEmpty() && inFlight.get(0) <= budget) {
        final long bytes = inFlight.remove(0);
        budget -= bytes;
        outstanding -= bytes;
        link.written(bytes);
      }
      final boolean keyframe = id == 0 || step.keyframe();
      final long reference = keyframe ? id : (fromKeyframe ? lastKey : id - 1);
      if (keyframe) {
        lastKey = id;
      }
      final boolean over = outstanding > (keyframe ? Mcv2Link.allowance(limit) : limit);
      final boolean sent = link.offer(id, reference, keyframe, step.bytes());
      if (sent) {
        // the client must hold the frame's reference, and the connection must have been at or under the limit
        if (over || !(keyframe || reference == clientLast || reference == clientKey)) {
          return false;
        }
        clientLast = id;
        if (keyframe) {
          clientKey = id;
        }
        outstanding += step.bytes();
        inFlight.add((long) step.bytes());
      } else if (!over && (keyframe || reference == clientLast || reference == clientKey)) {
        // a frame the viewer could take and decode is never held back
        return false;
      }
      if (link.getBacklog() != outstanding) {
        return false;
      }
    }
    return link.getSent() + link.getBehind() + link.getUndecodable() == steps.size();
  }
}
