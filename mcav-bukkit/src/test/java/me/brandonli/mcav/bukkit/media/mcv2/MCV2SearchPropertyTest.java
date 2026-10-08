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

import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

final class MCV2SearchPropertyTest {

  private static final String SEED = "20260926";
  private static final int SIZE = 32;

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

  static long distortion(final byte[] source, final byte[] reference) {
    long sum = 0;
    for (int at = 0; at < source.length; at += 3) {
      final long red = (source[at] & 255L) - (reference[at] & 255);
      final long green = (source[at + 1] & 255L) - (reference[at + 1] & 255);
      final long blue = (source[at + 2] & 255L) - (reference[at + 2] & 255);
      final long y = red + 2 * green + blue;
      final long co = 2 * (red - blue);
      final long cg = -red + 2 * green - blue;
      sum += 4 * y * y + co * co + cg * cg;
    }
    return sum;
  }

  private static boolean skipped(final byte[][] frames, final double lambda, final boolean fast) {
    final Mcv2BlockState job = new Mcv2BlockState(frames[0], frames[1], SIZE, SIZE, false, fast, lambda, null);
    return Mcv2BlockState.skipped(job.code(SIZE, 0, 0, 0, 0, Mcv2BlockState.NO_VECTOR));
  }

  @Property(seed = SEED, tries = 200)
  boolean endsABlockAtTheEarlySkipExactlyAtOrBelowTheThreshold(
    @ForAll final long seed,
    @ForAll @IntRange(min = 0, max = 30) final int amplitude,
    @ForAll @DoubleRange(min = 1, max = 2000) final double lambda,
    @ForAll final boolean fast
  ) {
    final byte[][] frames = frames(seed, amplitude);
    final double skipCost = distortion(frames[0], frames[1]) / 96.0 + lambda;
    return skipped(frames, lambda, fast) == skipCost <= (fast ? 60 : 28) * lambda;
  }

  @Property(seed = SEED, tries = 200)
  boolean keepsABlockSkippedAsLambdaGrows(
    @ForAll final long seed,
    @ForAll @IntRange(min = 0, max = 30) final int amplitude,
    @ForAll @DoubleRange(min = 1, max = 2000) final double oneLambda,
    @ForAll @DoubleRange(min = 1, max = 2000) final double otherLambda,
    @ForAll final boolean fast
  ) {
    final byte[][] frames = frames(seed, amplitude);
    final boolean low = skipped(frames, Math.min(oneLambda, otherLambda), fast);
    final boolean high = skipped(frames, Math.max(oneLambda, otherLambda), fast);
    return !low || high;
  }
}
