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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random;

/**
 * The {@code MurmurHash3} class provides static methods to generate 32-bit and
 * 64-bit hash codes using the MurmurHash3 algorithm. It is a non-cryptographic hash
 * function known for its high performance and good collision resistance.
 * <p>
 * This class is final and cannot be instantiated. All methods are static.
 * <p>
 * Thread-safety: The methods in this class are thread-safe as they perform no
 * mutation of shared state.
 */
public final class MurmurHash3 {

  private MurmurHash3() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Computes a hash value for the given integer using a variant of the MurmurHash3 algorithm.
   * This method performs bitwise operations and multiplication to ensure a uniformly distributed
   * and high-quality hash for integer inputs.
   *
   * @param k the integer value to hash
   * @return the hashed value as an integer
   */
  public static int hash(int k) {
    k ^= k >>> 16;
    k *= 0x85ebca6b;
    k ^= k >>> 13;
    k *= 0xc2b2ae35;
    k ^= k >>> 16;
    return k;
  }

  /**
   * Computes a hash value for a given long input using a variation of the MurmurHash3 algorithm.
   * This method is designed to generate a highly scrambled and uniformly distributed hash value
   * from the input value, providing good performance for non-cryptographic usage.
   *
   * @param k the long value to be hashed
   * @return a hashed long value derived from the input
   */
  public static long hash(long k) {
    k ^= k >>> 33;
    k *= 0xff51afd7ed558ccdL;
    k ^= k >>> 33;
    k *= 0xc4ceb9fe1a85ec53L;
    k ^= k >>> 33;
    return k;
  }
}
