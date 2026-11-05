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

import com.google.common.base.Preconditions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes hexadecimal digests for MCV2 resources.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class Mcv2Resources {

  private static final String SHA_256_ALGORITHM = "SHA-256";

  private Mcv2Resources() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Hashes bytes.
   *
   * @param bytes the bytes
   * @return the lowercase hexadecimal SHA-256
   * @throws NullPointerException if bytes is null
   * @since 1.0.0
   */
  public static String sha256(final byte[] bytes) {
    Preconditions.checkNotNull(bytes, "Bytes must not be null!");

    return digest(bytes, SHA_256_ALGORITHM);
  }

  static String digest(final byte[] bytes, final String algorithm) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (final NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }
}
