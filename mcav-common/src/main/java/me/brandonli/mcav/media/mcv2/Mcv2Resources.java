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
package me.brandonli.mcav.media.mcv2;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Loads the static data of the MCV2 profile from the classpath and checks it against its SHA-256, so a corrupted or
 * replaced file can never silently change what the codec decodes.
 */
public final class Mcv2Resources {

  private Mcv2Resources() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Loads and checks one resource of this package.
   *
   * @param name   the file name
   * @param sha256 the expected lowercase hexadecimal SHA-256
   * @param length the expected length in bytes
   * @return the bytes
   * @throws IllegalStateException if the resource is missing or does not match
   * @throws UncheckedIOException  if it cannot be read
   */
  public static byte[] load(final String name, final String sha256, final int length) {
    return verify(read(Mcv2Resources.class.getResourceAsStream(name), name), sha256, length, name);
  }

  /**
   * Reads a stream completely and closes it.
   *
   * @param stream the stream, or null when the resource does not exist
   * @param name   the resource name, for messages
   * @return the bytes
   * @throws IllegalStateException if the stream is null
   * @throws UncheckedIOException  if reading fails
   */
  static byte[] read(final @Nullable InputStream stream, final String name) {
    if (stream == null) {
      throw new IllegalStateException("Missing MCV2 resource " + name);
    }
    try (stream) {
      return stream.readAllBytes();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Checks bytes against their expected length and SHA-256.
   *
   * @param bytes  the bytes
   * @param sha256 the expected lowercase hexadecimal SHA-256
   * @param length the expected length
   * @param name   the resource name, for messages
   * @return the same bytes
   * @throws IllegalStateException if the length or checksum is wrong
   */
  static byte[] verify(final byte[] bytes, final String sha256, final int length, final String name) {
    final String digest = sha256(bytes);
    if (bytes.length != length || !digest.equals(sha256)) {
      throw new IllegalStateException("MCV2 resource " + name + " has the wrong length or checksum: " + digest);
    }
    return bytes;
  }

  /**
   * Hashes bytes.
   *
   * @param bytes the bytes
   * @return the lowercase hexadecimal SHA-256
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
