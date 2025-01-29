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
package me.brandonli.mcav.installer;

import java.nio.file.Path;
import java.util.Collection;

/**
 * An interface for loading JAR files into a given {@link ClassLoader}.
 * This is useful for dynamically adding JAR files to the runtime classpath.
 */
public interface JarLoader {
  /**
   * Loads the specified JAR files into the given ClassLoader.
   *
   * @param jars   the collection of paths to the JAR files to be loaded
   * @param loader the ClassLoader into which the JAR files should be loaded
   */
  void loadJars(final Collection<Path> jars, final ClassLoader loader);

  JarLoader DEFAULT_URL_LOADER = LoaderUtils::loadJarPaths;
}
