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
package me.brandonli.mcav.media.player.multimedia.cv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * A session stops while its decoder queues one more frame. {@code stop()} replaces what is queued with the end marker,
 * and a decoder that is still running may refill the queue in between, so the queue is drained again until the marker
 * fits. Every frame holds an image of the pool, so each must end up exactly once either in the queue, where the
 * renderer hands back what follows its end marker, or with {@code stop()}, which hands back what it drained; and the
 * renderer must find its marker, or it waits forever. This races the real {@code replaceWithEndMarker} against the
 * decoder queuing a frame. The queue holds one frame, so a decoder that refills it between the draining and the
 * offering of the marker leaves no room for the marker, which is exactly what the loop of {@code replaceWithEndMarker}
 * is for. The results are whether the marker is queued and how often the queued frame and the decoded frame are
 * accounted for; a decoded frame that found no room stays with the decoder, which releases it itself.
 */
@JCStressTest
@Outcome(id = "1, 1, 1", expect = Expect.ACCEPTABLE, desc = "the marker is queued and each frame is accounted for exactly once")
@Outcome(id = "0, .*", expect = Expect.FORBIDDEN, desc = "no end marker: the renderer waits forever")
@Outcome(expect = Expect.FORBIDDEN, desc = "a frame was lost or handed back twice, which leaks or double-frees a pooled image")
@State
public class EndMarkerRace {

  private static final String END = "end";
  private static final String QUEUED = "queued";
  private static final String DECODED = "decoded";

  private final BlockingQueue<String> queue;
  private final List<String> discarded;
  private volatile boolean keptByDecoder;

  /**
   * Creates a video queue of one frame, full with a frame waiting in it.
   */
  public EndMarkerRace() {
    this.queue = new ArrayBlockingQueue<>(1);
    this.queue.add(QUEUED);
    this.discarded = Collections.synchronizedList(new ArrayList<>());
  }

  /**
   * The decoder queuing the frame it just decoded. The decoder blocks in {@code put} until there is room or it is
   * interrupted by {@code stop()}; one attempt that does not block covers the interleavings that matter here without a
   * thread that could wait forever.
   */
  @Actor
  public void decode() {
    final boolean queued = this.queue.offer(DECODED);
    this.keptByDecoder = !queued;
  }

  /**
   * {@code stop()} replacing the queued frames with the end marker.
   */
  @Actor
  public void stop() {
    PlaybackSession.replaceWithEndMarker(this.queue, END, this.discarded::add);
  }

  /**
   * Accounts for every frame.
   *
   * @param outcome whether the marker is queued, and how often each frame is found
   */
  @Arbiter
  public void account(final III_Result outcome) {
    final List<String> remaining = new ArrayList<>(this.queue);
    final List<String> everything = new ArrayList<>(remaining);
    everything.addAll(this.discarded);
    outcome.r1 = remaining.contains(END) ? 1 : 0;
    outcome.r2 = Collections.frequency(everything, QUEUED);
    final int decodedFound = Collections.frequency(everything, DECODED);
    outcome.r3 = this.keptByDecoder ? decodedFound + 1 : decodedFound;
  }
}
