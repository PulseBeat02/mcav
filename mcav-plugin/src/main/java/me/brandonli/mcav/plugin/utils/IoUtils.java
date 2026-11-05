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
package me.brandonli.mcav.plugin.utils;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import me.brandonli.mcav.plugin.MCAVSandbox;

/**
 * Locates the plugin's data folder and its bundled resources.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class IoUtils {

  private IoUtils() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  /**
   * Returns the absolute path of the plugin's data folder.
   *
   * @return the data folder
   * @since 1.0.0
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
   * @throws NullPointerException     if the path is {@code null}
   * @throws IllegalArgumentException if there is no such resource
   * @since 1.0.0
   */
  public static InputStream getResourceAsStream(final String path) {
    Preconditions.checkNotNull(path, "Path must not be null!");

    final Class<IoUtils> utilsClass = IoUtils.class;
    final ClassLoader nullableLoader = utilsClass.getClassLoader();
    final ClassLoader loader = requireNonNull(nullableLoader); // checker framework

    final InputStream stream = loader.getResourceAsStream(path);
    if (stream == null) {
      final String message = "Resource %s does not exist!".formatted(path);
      throw new IllegalArgumentException(message);
    }

    return stream;
  }
}
