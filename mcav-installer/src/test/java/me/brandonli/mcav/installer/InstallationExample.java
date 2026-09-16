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

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Downloads the core module into {@code dependencies} and loads it into a fresh class loader.
 */
public final class InstallationExample {

  /**
   * Runs the example.
   *
   * @throws ClassNotFoundException if the core module did not end up in the class loader
   * @throws IOException            if the class loader cannot be closed
   */
  static void main() throws ClassNotFoundException, IOException {
    final Path folder = Path.of("dependencies");
    final ClassLoader parent = InstallationExample.class.getClassLoader();
    final URL[] noUrls = new URL[0];
    try (final URLClassLoader loader = new URLClassLoader(noUrls, parent)) {
      final MCAVInstaller installer = MCAVInstaller.injector(folder, loader);
      installer.loadMCAVDependencies(Artifact.COMMON);

      final Class<?> api = Class.forName("me.brandonli.mcav.MCAV", false, loader);
      final String name = api.getName();
      System.out.println("Loaded " + name);
    }
  }
}
