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
package me.brandonli.mcav.installer;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class IOUtils {

  private IOUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  static String getFileName(final Path file) {
    final Path name = requireNonNull(file.getFileName());
    return name.toString();
  }

  static String calculateSha256Hash(final Path file) throws IOException {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] fileBytes = Files.readAllBytes(file);
      final byte[] hashBytes = digest.digest(fileBytes);
      return tohexString(hashBytes);
    } catch (final NoSuchAlgorithmException e) {
      throw new InstallationError("SHA-256 algorithm not available");
    }
  }

  private static String tohexString(final byte[] hashBytes) {
    final StringBuilder hexString = new StringBuilder();
    for (final byte hashByte : hashBytes) {
      final String hex = Integer.toHexString(0xff & hashByte);
      if (hex.length() == 1) {
        hexString.append('0');
      }
      hexString.append(hex);
    }
    return hexString.toString();
  }
}
