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
package me.brandonli.mcav.sandbox.utils;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import me.brandonli.mcav.sandbox.MCAV;

public final class IOUtils {

  private IOUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static Path getPluginDataFolderPath() {
    final MCAV plugin = MCAV.getPlugin(MCAV.class);
    final File dataFolder = plugin.getDataFolder();
    final Path dataFolderPath = dataFolder.toPath();
    return dataFolderPath.toAbsolutePath();
  }

  public static Reader getResourceAsStreamReader(final String path) {
    final InputStream stream = getResourceAsStream(path);
    return new InputStreamReader(stream);
  }

  public static InputStream getResourceAsStream(final String path) {
    final Class<IOUtils> clazz = IOUtils.class;
    final ClassLoader loader = requireNonNull(clazz.getClassLoader());
    return requireNonNull(loader.getResourceAsStream(path));
  }

  public static boolean createFileIfNotExists(final Path path) {
    try {
      if (Files.notExists(path)) {
        final Path parent = requireNonNull(path.getParent());
        Files.createDirectories(parent);
        Files.createFile(path);
        return true;
      }
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
    return false;
  }
}
