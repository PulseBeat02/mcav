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
package me.brandonli.mcav.utils.natives;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import me.brandonli.mcav.utils.IOUtils;

/**
 * Utility class for loading native libraries from JAR resources.
 */
public final class NativeUtils {

  private NativeUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Loads a native library from the JAR resources.
   * The library is extracted to a temporary file and then loaded.
   *
   * @param resourcePath The path to the library within the JAR resources
   */
  public static void loadLibrary(final String resourcePath) {
    try {
      final String fileName = getNativeFileName(resourcePath);
      final int lastDotIndex = fileName.lastIndexOf('.');
      final String fileNamePrefix = fileName.substring(0, lastDotIndex);
      final String fileNameSuffix = fileName.substring(lastDotIndex);
      final Path parent = IOUtils.getCachedFolder();
      final Path natives = parent.resolve("natives");
      IOUtils.createDirectoryIfNotExists(natives);

      final File file = natives.toFile();
      final File tempFile = File.createTempFile(fileNamePrefix, fileNameSuffix, file);
      tempFile.deleteOnExit();

      final Path tempFilePath = tempFile.toPath();
      final ClassLoader classLoader = requireNonNull(NativeUtils.class.getClassLoader());
      try (final InputStream resourceStream = requireNonNull(classLoader.getResourceAsStream(resourcePath))) {
        Files.copy(resourceStream, tempFilePath, StandardCopyOption.REPLACE_EXISTING);
      }

      final File absolute = tempFile.getAbsoluteFile();
      final String absolutePath = absolute.toString();
      System.load(absolutePath);
    } catch (final IOException e) {
      throw new NativeLoadingException(e.getMessage(), e);
    }
  }

  private static String getNativeFileName(final String resourcePath) {
    final String fileName;
    if (resourcePath.contains("/")) {
      final int lastSlashIndex = resourcePath.lastIndexOf('/');
      fileName = resourcePath.substring(lastSlashIndex + 1);
    } else {
      fileName = resourcePath;
    }
    return fileName;
  }
}
