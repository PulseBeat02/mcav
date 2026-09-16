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

import java.lang.invoke.MethodHandle;
import java.net.URL;
import java.net.URLClassLoader;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Calls the protected {@code addURL} method of a {@link URLClassLoader}. Two strategies exist because the
 * runtime may deny the reflective access the first one needs.
 */
abstract class URLClassLoaderInjector {

  private final URLClassLoader classLoader;

  URLClassLoaderInjector(final URLClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  /**
   * Creates the best injector the runtime allows.
   *
   * @param classLoader the class loader to add jars to
   * @return the injector
   * @throws JarInjectorException if no strategy works on this runtime
   */
  static URLClassLoaderInjector create(final URLClassLoader classLoader) {
    return create(classLoader, ReflectiveInjector.ADD_URL, UnsafeInjector.UNSAFE);
  }

  /**
   * Creates the first injector whose strategy is available. Visible for testing.
   *
   * @param classLoader the class loader to add jars to
   * @param addUrl      the {@code addURL} handle of the reflective strategy, or null if it is not available
   * @param unsafe      the access of the Unsafe strategy, or null if it is not available
   * @return the injector
   * @throws JarInjectorException if neither strategy is available
   */
  static URLClassLoaderInjector create(
    final URLClassLoader classLoader,
    final @Nullable MethodHandle addUrl,
    final UnsafeInjector.@Nullable UnsafeAccess unsafe
  ) {
    if (addUrl != null) {
      return new ReflectiveInjector(classLoader, addUrl);
    }
    if (unsafe != null) {
      return new UnsafeInjector(classLoader, unsafe);
    }
    throw new JarInjectorException(
      "No way to add jars to a URLClassLoader on this runtime; add --add-opens java.base/java.net=ALL-UNNAMED"
    );
  }

  /**
   * Appends a jar to the class loader.
   *
   * @param url the URL of the jar
   */
  abstract void addURL(final URL url);

  /**
   * Gets the class loader jars are added to.
   *
   * @return the class loader
   */
  URLClassLoader getClassLoader() {
    return this.classLoader;
  }
}
