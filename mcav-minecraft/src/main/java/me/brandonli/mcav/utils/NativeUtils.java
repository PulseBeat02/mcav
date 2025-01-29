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
package me.brandonli.mcav.utils;

import static java.util.Objects.requireNonNull;

import java.io.*;

public final class NativeUtils {

  private NativeUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Loads a native library from the JAR resources.
   * The library is extracted to a temporary file and then loaded.
   *
   * @param resource The path to the library within the JAR resources
   */
  public static void loadLibrary(final String resource) {
    try {
      final String filename = resource.contains("/") ? resource.substring(resource.lastIndexOf('/') + 1) : resource;
      final String prefix = filename.substring(0, filename.lastIndexOf('.'));
      final String suffix = filename.substring(filename.lastIndexOf('.'));
      final File tempFile = File.createTempFile(prefix, suffix);
      tempFile.deleteOnExit();
      try (
        final InputStream in = requireNonNull(NativeUtils.class.getClassLoader()).getResourceAsStream(resource);
        final OutputStream out = new FileOutputStream(tempFile)
      ) {
        if (in == null) {
          throw new IOException("Resource not found: " + resource);
        }
        final byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
          out.write(buffer, 0, bytesRead);
        }
        out.flush();
      }
      System.load(tempFile.getAbsolutePath());
    } catch (final IOException e) {
      throw new RuntimeException("Failed to load native library: " + resource, e);
    }
  }
}
