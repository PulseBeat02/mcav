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
package me.brandonli.mcav.media.mcv2.encode;

import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

/**
 * The live search's thresholds: the quantizer lambda suggests grows with lambda, a block ends at the early SKIP exactly
 * when its SKIP costs at most the threshold times lambda, and a block that ends there at one lambda ends there at any
 * larger one.
 */
final class LiveSearchPropertyTest {

  private static final String SEED = "20260926";

  private static final int SIZE = 32;

  @Property(seed = SEED, tries = 300)
  boolean derivesAQuantizerThatGrowsWithLambda(
    @ForAll @DoubleRange(min = 0, max = 1e7) final double a,
    @ForAll @DoubleRange(min = 0, max = 1e7) final double b
  ) {
    final int low = LiveSearch.quantizer(Math.min(a, b));
    final int high = LiveSearch.quantizer(Math.max(a, b));
    return low >= 0 && low <= high && high <= 4;
  }

  /** A textured reference, and a source that differs from it by noise of an amplitude. */
  private static byte[][] frames(final long seed, final int amplitude) {
    final Random random = new Random(seed);
    final byte[] reference = new byte[SIZE * SIZE * 3];
    final byte[] source = new byte[reference.length];
    for (int i = 0; i < reference.length; i++) {
      final int value = 40 + random.nextInt(176);
      reference[i] = (byte) value;
      source[i] = (byte) Math.clamp(value + random.nextInt(2 * amplitude + 1) - amplitude, 0, 255);
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
      0
    );
    final EncoderSettings settings = EncoderSettings.SHIP.withLambda(lambda).withLive(search);
    job[0] = new FrameJob(settings, frames[0], frames[1], SIZE, SIZE, false, new int[] { 0 }, new int[] { 0 }, null, null);
    final BlockCoder coder = new BlockCoder(job[0], SIZE);
    coder.code(0, 0, 0, 0, -1, 0);
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
    return coder.isSkipped() == (job[0].cost(0, 0)[0] <= skip * lambda);
  }

  @Property(seed = SEED, tries = 200)
  boolean keepsABlockSkippedAsLambdaGrows(
    @ForAll final long seed,
    @ForAll @IntRange(min = 0, max = 30) final int amplitude,
    @ForAll @DoubleRange(min = 1, max = 2000) final double a,
    @ForAll @DoubleRange(min = 1, max = 2000) final double b,
    @ForAll @DoubleRange(min = 1.5, max = 300) final double skip
  ) {
    final byte[][] frames = frames(seed, amplitude);
    final FrameJob[] job = new FrameJob[1];
    final boolean low = code(frames, Math.min(a, b), skip, job).isSkipped();
    final boolean high = code(frames, Math.max(a, b), skip, job).isSkipped();
    return !low || high;
  }
}
