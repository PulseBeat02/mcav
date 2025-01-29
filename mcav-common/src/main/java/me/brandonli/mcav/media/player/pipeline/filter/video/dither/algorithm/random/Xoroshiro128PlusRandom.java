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

import java.io.Serial;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A random number generator based on the Xoroshiro128+ algorithm.
 */
public final class Xoroshiro128PlusRandom extends Random {

  private static final double DOUBLE_UNIT = 0x1.0p-53;
  private static final float FLOAT_UNIT = 0x1.0p-24f;

  @Serial
  private static final long serialVersionUID = 7871099129105026239L;

  /**
   * internal state variable
   */
  private long s0, s1;

  /**
   * Default constructor for the {@link Xoroshiro128PlusRandom} class.
   */
  public Xoroshiro128PlusRandom() {
    this(createSeed() ^ System.nanoTime());
  }

  /**
   * Constructs a new instance of the Xoroshiro128PlusRandom random number generator,
   * initializing its internal state using the specified seed.
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
