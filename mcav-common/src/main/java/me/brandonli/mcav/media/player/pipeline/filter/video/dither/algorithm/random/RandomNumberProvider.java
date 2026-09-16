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
 * A source of random numbers for {@link RandomDither}. Implementations are called for every channel of every
 * pixel, so they must be fast, and they are not required to be thread-safe.
 */
public interface RandomNumberProvider {
  /**
   * Gets a random integer in a range.
   *
   * @param min the smallest value, inclusive
   * @param max the largest value, exclusive
   * @return a random value from {@code min} to {@code max - 1}
   */
  int nextInt(final int min, final int max);

  /**
   * Gets a random floating point number in a range.
   *
   * @param min the smallest value, inclusive
   * @param max the largest value, exclusive
   * @return a random value from {@code min} to {@code max}
   */
  double nextDouble(final double min, final double max);

  /**
   * Gets a random boolean.
   *
   * @return true or false with equal probability
   */
  boolean nextBoolean();
}
