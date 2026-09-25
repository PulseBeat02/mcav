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
package me.brandonli.mcav.bukkit.media.map;

import java.util.Arrays;
import java.util.List;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * A snapshot for a viewer who just started watching, taken while the next frame is encoded, without the lock the
 * encoder asks its callers for. Pass 1 flagged the encoder's mutable state as unsynchronised and without a locking
 * contract; it now documents that callers must synchronise, and its only caller, {@code CompressedMapResult}, encodes
 * and takes snapshots under one lock. This test shows why the contract is needed: a snapshot taken during an encode can
 * hold part of the old frame and part of the new one, a picture the other viewers never saw, which the deltas that
 * follow would never repair for that viewer. The results are how many pixels of the snapshot have the color of the
 * old frame and how many the color of the new one.
 */
@JCStressTest
@Outcome(id = "256, 0", expect = Expect.ACCEPTABLE, desc = "the snapshot was taken before the frame was encoded")
@Outcome(id = "0, 256", expect = Expect.ACCEPTABLE, desc = "the snapshot was taken after the frame was encoded")
@Outcome(
  expect = Expect.ACCEPTABLE_INTERESTING,
  desc = "a torn snapshot of two frames: why the encoder must not be shared without the lock CompressedMapResult holds"
)
@State
public class EncodeSnapshotRace {

  private static final int SIDE = 16;
  private static final byte OLD_COLOR = 4;
  private static final byte NEW_COLOR = 8;

  private final DeltaMapEncoder encoder;
  private final byte[] nextFrame;

  /**
   * Creates an encoder whose clients already display a frame of one color.
   */
  public EncodeSnapshotRace() {
    final MapLayout layout = new MapLayout(0, 1, 1, SIDE, SIDE);
    this.encoder = new DeltaMapEncoder(layout, DeltaMapEncoder.DEFAULT_MAX_BYTES_PER_FRAME);
    final byte[] oldFrame = new byte[SIDE * SIDE];
    Arrays.fill(oldFrame, OLD_COLOR);
    this.encoder.encode(oldFrame);
    this.nextFrame = new byte[SIDE * SIDE];
    Arrays.fill(this.nextFrame, NEW_COLOR);
  }

  /**
   * Encodes the next frame, in which every pixel changed.
   */
  @Actor
  public void encode() {
    this.encoder.encode(this.nextFrame);
  }

  /**
   * Takes the snapshot a new viewer receives.
   *
   * @param outcome how many pixels have the old color and how many the new one
   */
  @Actor
  public void snapshot(final II_Result outcome) {
    final List<MapTilePatch> snapshot = this.encoder.snapshot();
    int oldPixels = 0;
    int newPixels = 0;
    for (final MapTilePatch patch : snapshot) {
      final byte[] colors = patch.getColors();
      for (final byte color : colors) {
        if (color == OLD_COLOR) {
          oldPixels++;
        } else if (color == NEW_COLOR) {
          newPixels++;
        }
      }
    }
    outcome.r1 = oldPixels;
    outcome.r2 = newPixels;
  }
}
