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
 */
public final class XoroshiroRandomProvider implements RandomNumberProvider {

  private static final AtomicLong SEED_SEQUENCE = new AtomicLong(System.nanoTime());
  private static final long SEED_INCREMENT = 0x9E3779B97F4A7C15L;
  private static final double DOUBLE_UNIT = 0x1.0p-53;

  private long state0;
  private long state1;

  /**
   * Constructs a new provider with a seed that differs for every instance.
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
   */
  public XoroshiroRandomProvider(final long seed) {
    this.state0 = firstStateWord(seed);
    this.state1 = secondStateWord(seed);
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
    value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
    value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
    return value ^ (value >>> 31);
  }

  private long nextLong() {
    final long first = this.state0;
    long second = this.state1;
    final long result = first + second;
    second ^= first;
    this.state0 = Long.rotateLeft(first, 24) ^ second ^ (second << 16);
    this.state1 = Long.rotateLeft(second, 37);
    return result;
  }

  /**
   * Gets a random integer in a range. The modulo reduction is very slightly biased toward small offsets for huge
   * ranges, which does not matter for dithering noise.
   *
   * @param min the smallest value, inclusive
   * @param max the largest value, exclusive
   * @return a random value from {@code min} to {@code max - 1}
   * @throws IllegalArgumentException if {@code max} is not greater than {@code min}
   */
  @Override
  public int nextInt(final int min, final int max) {
    Preconditions.checkArgument(max > min, "Max must be greater than min");
    final long range = (long) max - min;
    final long nextBits = this.nextLong();
    final long value = nextBits >>> 1;
    final long offset = value % range;
    return (int) (min + offset);
  }

  /**
   * Gets a random floating point number in a range, drawn from the top 53 bits of the next output.
   *
   * @param min the smallest value, inclusive
   * @param max the largest value, exclusive
   * @return a random value from {@code min} to {@code max}
   * @throws IllegalArgumentException if {@code max} is not greater than {@code min}
   */
  @Override
  public double nextDouble(final double min, final double max) {
    Preconditions.checkArgument(max > min, "Max must be greater than min");
    final long nextBits = this.nextLong();
    final double unit = (nextBits >>> 11) * DOUBLE_UNIT;
    return min + unit * (max - min);
  }

  /**
   * Gets a random boolean from the highest bit of the next output, which is the best bit of xoroshiro128+.
   *
   * @return true or false with equal probability
   */
  @Override
  public boolean nextBoolean() {
    final long value = this.nextLong();
    return value < 0;
  }
}
