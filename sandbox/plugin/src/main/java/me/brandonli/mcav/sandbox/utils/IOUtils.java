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
package me.brandonli.mcav.sandbox.utils;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import me.brandonli.mcav.sandbox.MCAVSandbox;

/**
 * Locates the plugin's data folder and its bundled resources.
 */
public final class IOUtils {

  private IOUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Gets the absolute path of the plugin's data folder.
   *
   * @return the data folder
   */
  public static Path getPluginDataFolderPath() {
    final MCAVSandbox plugin = MCAVSandbox.getPlugin(MCAVSandbox.class);
    final File dataFolder = plugin.getDataFolder();
    final Path dataFolderPath = dataFolder.toPath();
    return dataFolderPath.toAbsolutePath();
  }

  /**
   * Opens a resource bundled with the plugin.
   *
   * @param path the path of the resource inside the plugin jar, such as {@code config.yml}
   * @return the stream of the resource, which the caller closes
   * @throws NullPointerException if the path is {@code null} or there is no such resource, with the path as the
   *                              message in the second case
   */
  public static InputStream getResourceAsStream(final String path) {
    Preconditions.checkNotNull(path, "Path must not be null");
    final Class<IOUtils> utilsClass = IOUtils.class;
    final ClassLoader nullableLoader = utilsClass.getClassLoader();
    final ClassLoader loader = requireNonNull(nullableLoader);

    final InputStream stream = loader.getResourceAsStream(path);
    if (stream == null) {
      throw new NullPointerException(path);
    }
    return stream;
  }
}
