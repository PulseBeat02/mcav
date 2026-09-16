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

import java.nio.file.Path;
import java.util.Collection;

/**
 * Adds downloaded jars to a class loader so their classes can be loaded.
 */
@FunctionalInterface
public interface JarLoader {
  /**
   * The loader that appends jars to a {@link java.net.URLClassLoader}, including the plugin class loaders of
   * Bukkit and Paper and the Knot class loader of Fabric.
   */
  JarLoader DEFAULT_URL_LOADER = LoaderUtils::loadJarPaths;

  /**
   * Adds jars to a class loader.
   *
   * @param jars   the jar files
   * @param loader the class loader
   * @throws JarInjectorException if the jars cannot be added
   */
  void loadJars(final Collection<Path> jars, final ClassLoader loader);
}
