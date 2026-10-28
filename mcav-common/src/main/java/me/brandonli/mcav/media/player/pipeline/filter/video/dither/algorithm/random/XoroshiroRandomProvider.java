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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random;

import com.google.common.base.Preconditions;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fast {@link RandomNumberProvider} based on the xoroshiro128+ generator, which produces a random number in a
 * handful of instructions. Instances are not thread-safe, so random dithering keeps one per thread.
 *
 * @since 1.0.0
 */
public final class XoroshiroRandomProvider implements RandomNumberProvider {

  private static final AtomicLong SEED_SEQUENCE = new AtomicLong(System.nanoTime());
  private static final long SEED_INCREMENT = 0x9E3779B97F4A7C15L;
  private static final double DOUBLE_UNIT = 0x1.0p-53;
  private static final int DOUBLE_DISCARDED_BITS = 11;

  private static final int SPLIT_MIX_FIRST_SHIFT = 30;
  private static final long SPLIT_MIX_FIRST_MULTIPLIER = 0xBF58476D1CE4E5B9L;
  private static final int SPLIT_MIX_SECOND_SHIFT = 27;
  private static final long SPLIT_MIX_SECOND_MULTIPLIER = 0x94D049BB133111EBL;
  private static final int SPLIT_MIX_FINAL_SHIFT = 31;

  private static final int FIRST_ROTATION = 24;
  private static final int STATE_SHIFT = 16;
  private static final int SECOND_ROTATION = 37;

  private static final double HALF = 0.5;
  private static final double DOUBLE = 2.0;

  private long state0;
  private long state1;

  /**
   * Constructs a new provider with a seed that differs for every instance.
   *
   * @since 1.0.0
   */
  public XoroshiroRandomProvider() {
    final long seed = nextSeed();
    this.state0 = firstStateWord(seed);
    this.state1 = secondStateWord(seed);
  }

  /**
   * Constructs a new provider with a fixed seed, which produces the same sequence every time.
   *
   * @param seed the seed
   * @since 1.0.0
   */
  public XoroshiroRandomProvider(final long seed) {
    this.state0 = firstStateWord(seed);
    this.state1 = secondStateWord(seed);
  }

  // Lets a test make the next output exactly zero, which kills the boundary mutant of nextBoolean.
  XoroshiroRandomProvider(final long state0, final long state1) {
    this.state0 = state0;
    this.state1 = state1;
  }

  // the two words of the state are the first two outputs of SplitMix64 seeded with the seed, as the authors of
  // xoroshiro recommend, so similar seeds produce unrelated sequences; the mixing function of SplitMix64 is a
  // bijection and its two counters differ, so the words are never both zero, which would leave the generator stuck
  private static long firstStateWord(final long seed) {
    return splitMix64(seed);
  }

  private static long secondStateWord(final long seed) {
    final long secondCounter = seed + SEED_INCREMENT;
    return splitMix64(secondCounter);
  }

  private static long nextSeed() {
    return SEED_SEQUENCE.addAndGet(SEED_INCREMENT);
  }

  private static long splitMix64(final long input) {
    long value = input + SEED_INCREMENT;
    value = (value ^ (value >>> SPLIT_MIX_FIRST_SHIFT)) * SPLIT_MIX_FIRST_MULTIPLIER;
    value = (value ^ (value >>> SPLIT_MIX_SECOND_SHIFT)) * SPLIT_MIX_SECOND_MULTIPLIER;
    return value ^ (value >>> SPLIT_MIX_FINAL_SHIFT);
  }

  private long nextLong() {
    final long first = this.state0;
    long second = this.state1;
    final long result = first + second;
    second ^= first;
    this.state0 = Long.rotateLeft(first, FIRST_ROTATION) ^ second ^ (second << STATE_SHIFT);
    this.state1 = Long.rotateLeft(second, SECOND_ROTATION);

    return result;
  }

  /**
   * Returns a random integer in a range, scaled from the high bits of the next output.
   *
   * <p>The offset is the high half of the unsigned product of the output and the width of the range, which is the
   * multiply-shift reduction of Lemire. It is taken from the high bits on purpose: the authors of xoroshiro128+
   * state that its lowest bits are of low linear complexity and recommend the high bits, so a modulo reduction,
   * which depends on the lowest bits, would carry that weakness straight into the dithering noise. Scaling also
   * leaves a bias below one part in 2^32 of the range, where a modulo reduction leaves one part in the range.
   *
   * @param minimum the smallest value, inclusive
   * @param maximum the largest value, exclusive
   * @return a random value from {@code minimum} to {@code maximum - 1}
   * @throws IllegalArgumentException if {@code maximum} is not greater than {@code minimum}
   * @since 1.0.0
   */
  @Override
  public int nextInt(final int minimum, final int maximum) {
    Preconditions.checkArgument(maximum > minimum, "Max must be greater than min!");

    final long range = (long) maximum - minimum;
    final long nextBits = this.nextLong();
    final long offset = Math.unsignedMultiplyHigh(nextBits, range);
    return (int) (minimum + offset);
  }

  /**
   * Returns a random floating point number in a range, drawn from the top 53 bits of the next output.
   *
   * @param minimum the finite smallest value, inclusive
   * @param maximum the finite largest value, exclusive
   * @return a random value greater than or equal to {@code minimum} and less than {@code maximum}
   * @throws IllegalArgumentException if either bound is not finite or {@code maximum} is not greater than {@code minimum}
   * @since 1.0.0
   */
  @Override
  public double nextDouble(final double minimum, final double maximum) {
    Preconditions.checkArgument(
      Double.isFinite(minimum) && Double.isFinite(maximum) && maximum > minimum,
      "Bounds must be finite and max greater than min!"
    );
    final long nextBits = this.nextLong();
    final double unit = (nextBits >>> DOUBLE_DISCARDED_BITS) * DOUBLE_UNIT;
    final double range = maximum - minimum;
    final double result;
    if (Double.isInfinite(range)) {
      // Scale before subtracting, as in JDK RandomSupport.boundedNextDouble, to avoid an infinite range.
      final double halfMinimum = minimum * HALF;
      result = (unit * (maximum * HALF - halfMinimum) + halfMinimum) * DOUBLE;
    } else {
      result = minimum + unit * range;
    }

    // Floating-point rounding can reach the exclusive bound, especially when the bounds are adjacent doubles.
    return result >= maximum ? Math.nextDown(maximum) : result;
  }

  /**
   * Returns a random boolean from the highest bit of the next output, which is the best bit of xoroshiro128+.
   *
   * @return true or false with equal probability
   * @since 1.0.0
   */
  @Override
  public boolean nextBoolean() {
    final long value = this.nextLong();
    return value < 0;
  }
}
