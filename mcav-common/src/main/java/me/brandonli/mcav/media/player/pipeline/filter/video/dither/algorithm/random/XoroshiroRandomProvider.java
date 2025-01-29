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

/**
 * A random number generator provider that wraps the Xoroshiro128PlusRandom implementation.
 */
public class XoroshiroRandomProvider implements RandomNumberProvider {

  private final Xoroshiro128PlusRandom random;

  /**
   * Constructs a new instance of {@code XoroshiroRandomProvider}.
   */
  public XoroshiroRandomProvider() {
    this.random = new Xoroshiro128PlusRandom();
  }

  /**
   * Constructs a new XoroshiroRandomProvider using the specified seed.
   * Initializes the underlying Xoroshiro128PlusRandom instance with the given seed.
   *
   * @param seed the initial seed value to create the random number generator
   */
  public XoroshiroRandomProvider(final long seed) {
    this.random = new Xoroshiro128PlusRandom(seed);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int nextInt(final int min, final int max) {
    return min + this.random.nextInt(max - min);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double nextDouble(final double min, final double max) {
    return min + this.random.nextDouble() * (max - min);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean nextBoolean() {
    return this.random.nextBoolean();
  }
}
