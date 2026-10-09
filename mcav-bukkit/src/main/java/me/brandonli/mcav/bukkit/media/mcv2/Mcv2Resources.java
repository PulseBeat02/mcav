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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Mcv2Resources {

  private Mcv2Resources() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Hashes bytes.
   *
   * @param bytes the bytes
   * @return the lowercase hexadecimal SHA-256
   * @throws NullPointerException if bytes is null
   */
  public static String sha256(final byte[] bytes) {
    return digest(bytes, "SHA-256");
  }

  /**
   * Hashes bytes with a named algorithm.
   *
   * @param bytes     the bytes
   * @param algorithm the algorithm
   * @return the lowercase hexadecimal digest
   * @throws IllegalStateException if the algorithm does not exist
   */
  static String digest(final byte[] bytes, final String algorithm) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
