/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.video.dither.algorithm.random;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Xoroshiro128PlusRandom is a subclass of {@link Random}, implementing the
 * Xoroshiro128+ pseudo-random number generator algorithm. It is designed to offer
 * high performance while maintaining high quality randomness suitable for most
 * applications requiring random number generation.
 * <p>
 * This implementation uses two internal 64-bit state variables and employs fast
 * bitwise operations to generate random numbers. The Xoroshiro128+ generator is
 * non-cryptographic and not suitable for cryptographic use cases.
 * <p>
 * The state is initialized using a seed, which may be provided by the user or
 * generated automatically if no seed is supplied. The implementation also ensures
 * that the internal state does not contain all zeroes, which would break the generator.
 * <p>
 * Key features:
 * - Supports all methods of the {@link Random} class.
 * - Efficient generation of random integers, floats, doubles, and booleans.
 * - Thread-safe initialization of unique seeds using atomic operations.
 *
 * @see Random
 */
public final class Xoroshiro128PlusRandom extends Random {

  private static final double DOUBLE_UNIT = 0x1.0p-53;
  private static final float FLOAT_UNIT = 0x1.0p-24f;
  private static final long serialVersionUID = 7871099129105026239L;

  private long s0, s1;

  /**
   * Default constructor for the {@link Xoroshiro128PlusRandom} class.
   * This constructor initializes the internal state of the random number generator
   * using a generated seed that combines the output of the {@code createSeed()} method
   * with the current value of {@link System#nanoTime()}.
   * <p>
   * The {@code createSeed()} method provides a unique, non-repeating value, ensuring
   * that the resulting seed is different for each instantiation of the generator,
   * except in rare edge cases where timing collisions occur.
   */
  public Xoroshiro128PlusRandom() {
    this(createSeed() ^ System.nanoTime());
  }

  /**
   * Constructs a new instance of the Xoroshiro128PlusRandom random number generator,
   * initializing its internal state using the specified seed. This implementation uses
   * the MurmurHash3 algorithm to create two internal state variables (s0 and s1) based
   * on the given seed. If the resulting state values are both zero, a fallback initialization
   * process is used to avoid invalid all-zeroes state.
   *
   * @param seed the initial seed value used to initialize the internal state of the
   *             random number generator. The seed is passed through the MurmurHash3
   *             algorithm to generate two internal state variables.
   */
  public Xoroshiro128PlusRandom(final long seed) {
    super(0);
    this.s0 = MurmurHash3.hash(seed);
    this.s1 = MurmurHash3.hash(this.s0);
    if (this.s0 == 0 && this.s1 == 0) {
      this.s0 = MurmurHash3.hash(0xdeadbeefL);
      this.s1 = MurmurHash3.hash(this.s0);
    }
  }

  private static final AtomicLong UNIQUE = new AtomicLong(8682522807148012L);

  private static long createSeed() {
    for (;;) {
      final long current = UNIQUE.get();
      final long next = current * 1181783497276652981L;
      if (UNIQUE.compareAndSet(current, next)) {
        return next;
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean nextBoolean() {
    return this.nextLong() >= 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void nextBytes(final byte[] bytes) {
    for (int i = 0, len = bytes.length; i < len;) {
      long rnd = this.nextInt();
      for (int n = Math.min(len - i, 8); n-- > 0; rnd >>>= 8) {
        bytes[i++] = (byte) rnd;
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double nextDouble() {
    return (this.nextLong() >>> 11) * DOUBLE_UNIT;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public float nextFloat() {
    return (this.nextInt() >>> 8) * FLOAT_UNIT;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int nextInt() {
    return (int) this.nextLong();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int nextInt(final int n) {
    return super.nextInt(n);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized double nextGaussian() {
    return super.nextGaussian();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long nextLong() {
    final long s0 = this.s0;
    long s1 = this.s1;
    final long result = s0 + s1;
    s1 ^= s0;
    this.s0 = Long.rotateLeft(s0, 55) ^ s1 ^ (s1 << 14);
    this.s1 = Long.rotateLeft(s1, 36);
    return result;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected int next(final int bits) {
    return ((int) this.nextLong()) >>> (32 - bits);
  }
}
