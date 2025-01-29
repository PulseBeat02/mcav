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
 * Provides methods to generate random values of various types.
 */
public interface RandomNumberProvider {
  /**
   * Generates a random integer within the given range, inclusive of the minimum value and
   * exclusive of the maximum value.
   *
   * @param min the minimum value (inclusive) for the range.
   * @param max the maximum value (exclusive) for the range. Must be greater than min.
   * @return a randomly generated integer between min (inclusive) and max (exclusive).
   */
  int nextInt(int min, int max);

  /**
   * Generates a random double value within the specified range.
   *
   * @param min the lower bound (inclusive) of the range
   * @param max the upper bound (exclusive) of the range
   * @return a random double value greater than or equal to {@code min} and less than {@code max}
   */
  double nextDouble(double min, double max);

  /**
   * Generates a random boolean value.
   *
   * @return a randomly generated boolean value, either true or false
   */
  boolean nextBoolean();
}
