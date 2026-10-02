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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SKIP;

import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

/**
 * The live search's thresholds: the quantizer lambda suggests grows with lambda, a block ends at the early SKIP exactly
 * when its SKIP costs at most the threshold times lambda, and a block that ends there at one lambda ends there at any
 * larger one. A superblock above the split threshold tries no other leaf, and one below it decides as if there were
 * none.
 */
final class LiveSearchPropertyTest {

  private static final String SEED = "20260926";

  private static final int SIZE = 32;

  @Property(seed = SEED, tries = 300)
  boolean derivesAQuantizerThatGrowsWithLambda(
    @ForAll @DoubleRange(min = 0, max = 1e7) final double oneLambda,
    @ForAll @DoubleRange(min = 0, max = 1e7) final double otherLambda
  ) {
    final int low = LiveSearch.quantizer(Math.min(oneLambda, otherLambda));
    final int high = LiveSearch.quantizer(Math.max(oneLambda, otherLambda));
    return low >= 0 && low <= high && high <= 4;
  }

  /** A textured reference, and a source that differs from it by noise of an amplitude. */
  private static byte[][] frames(final long seed, final int amplitude) {
    final Random random = new Random(seed);
    final byte[] reference = new byte[SIZE * SIZE * 3];
    final byte[] source = new byte[reference.length];
    for (int index = 0; index < reference.length; index++) {
      final int value = 40 + random.nextInt(176);
      reference[index] = (byte) value;
      source[index] = (byte) Math.clamp(value + random.nextInt(2 * amplitude + 1) - amplitude, 0, 255);
    }
    return new byte[][] { source, reference };
  }

  /** Codes the root of a P frame with a search that tries SKIP alone, so the block's cost is SKIP's. */
  private static BlockCoder code(final byte[][] frames, final double lambda, final double skip, final FrameJob[] job) {
    final LiveSearch search = new LiveSearch(
      32,
      skip,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      LiveSearch.ALL_MODES,
      0,
      LiveSearch.FROM_LAMBDA,
      false,
      32,
      false,
      0,
      0,
      false
    );
    final EncoderSettings settings = EncoderSettings.SHIP.withLambda(lambda).withLive(search);
    job[0] = new FrameJob(settings, frames[0], frames[1], SIZE, SIZE, false, new int[] { 0 }, new int[] { 0 }, null, null);
    final BlockCoder coder = new BlockCoder(job[0], SIZE);
    coder.code(0, 0, 0, 0, BlockCoder.NO_VECTOR, 0);
    return coder;
  }

  @Property(seed = SEED, tries = 200)
  boolean endsABlockAtTheEarlySkipExactlyAtOrBelowTheThreshold(
    @ForAll final long seed,
    @ForAll @IntRange(min = 0, max = 30) final int amplitude,
    @ForAll @DoubleRange(min = 1, max = 2000) final double lambda,
    @ForAll @DoubleRange(min = 0, max = 300) final double skip
  ) {
    final FrameJob[] job = new FrameJob[1];
    final BlockCoder coder = code(frames(seed, amplitude), lambda, skip, job);
    return coder.isSkipped() == job[0].cost(0, 0)[0] <= skip * lambda;
  }

  @Property(seed = SEED, tries = 200)
  boolean keepsABlockSkippedAsLambdaGrows(
    @ForAll final long seed,
    @ForAll @IntRange(min = 0, max = 30) final int amplitude,
    @ForAll @DoubleRange(min = 1, max = 2000) final double oneLambda,
    @ForAll @DoubleRange(min = 1, max = 2000) final double otherLambda,
    @ForAll @DoubleRange(min = 1.5, max = 300) final double skip
  ) {
    final byte[][] frames = frames(seed, amplitude);
    final FrameJob[] job = new FrameJob[1];
    final boolean low = code(frames, Math.min(oneLambda, otherLambda), skip, job).isSkipped();
    final boolean high = code(frames, Math.max(oneLambda, otherLambda), skip, job).isSkipped();
    return !low || high;
  }

  /** A search from the top with every keyframe mode and the given P frame modes and split threshold. */
  private static LiveSearch every(final int modes, final double splitAbove) {
    return new LiveSearch(
      8,
      LiveSearch.EXACT_SKIP,
      0,
      0,
      0,
      0,
      0,
      modes,
      modes,
      LiveSearch.ALL_MODES,
      LiveSearch.ALL_CLASSES,
      LiveSearch.FROM_LAMBDA,
      true,
      SIZE,
      true,
      0,
      splitAbove,
      false
    );
  }

  /** Codes the root of a P frame with a search, and returns the frame's search state. */
  private static FrameJob root(final byte[][] frames, final double lambda, final LiveSearch search) {
    final EncoderSettings settings = EncoderSettings.SHIP.withLambda(lambda).withLive(search);
    final FrameJob job = new FrameJob(settings, frames[0], frames[1], SIZE, SIZE, false, new int[] { 0 }, new int[] { 0 }, null, null);
    new BlockCoder(job, SIZE).code(0, 0, 0, 0, BlockCoder.NO_VECTOR, 0);
    return job;
  }

  @Property(seed = SEED, tries = 100)
  boolean triesNoOtherLeafOfARootAboveTheSplitThreshold(
    @ForAll final long seed,
    @ForAll @IntRange(min = 0, max = 60) final int amplitude,
    @ForAll @DoubleRange(min = 1, max = 2000) final double lambda,
    @ForAll @DoubleRange(min = 0.5, max = 2000) final double splitAbove
  ) {
    final byte[][] frames = frames(seed, amplitude);
    // the cost after SKIP and local motion: the cost of a search that tries nothing else
    final double motion = root(frames, lambda, every(1 << MODE_MOTION, 0)).cost(0, 0)[0];
    final double full = root(frames, lambda, every(LiveSearch.ALL_MODES, 0)).cost(0, 0)[0];
    final FrameJob job = root(frames, lambda, every(LiveSearch.ALL_MODES, splitAbove));
    final double cost = job.cost(0, 0)[0];
    final int mode = job.mode(0, 0, 0);
    if (motion > splitAbove * lambda) {
      return cost == motion && (mode == MODE_SKIP || mode == MODE_MOTION);
    }
    return cost == full;
  }
}
