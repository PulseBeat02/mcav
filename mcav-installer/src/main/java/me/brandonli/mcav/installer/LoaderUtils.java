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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collection;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Appends jars to class loaders.
 */
final class LoaderUtils {

  private static final String KNOT_CLASS_LOADER = "net.fabricmc.loader.impl.launch.knot.KnotClassLoader";
  private static final String KNOT_URL_LOADER_FIELD = "urlLoader";

  private LoaderUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Appends jars to a class loader.
   *
   * @param jars   the jar files
   * @param loader the class loader
   */
  static void loadJarPaths(final Collection<Path> jars, final ClassLoader loader) {
    final URLClassLoader target = resolveUrlClassLoader(loader);
    final URLClassLoaderInjector injector = URLClassLoaderInjector.create(target);
    for (final Path jar : jars) {
      final URL url = toUrl(jar);
      injector.addURL(url);
    }
  }

  // the path is normalized so that the same jar always gets the same class path entry
  private static URL toUrl(final Path jar) {
    final Path absolute = jar.toAbsolutePath();
    final Path normalized = absolute.normalize();
    final URI uri = normalized.toUri();
    try {
      return uri.toURL();
    } catch (final MalformedURLException exception) {
      final String message = exception.getMessage();
      throw new JarInjectorException("Invalid jar path " + jar + ": " + message, exception);
    }
  }

  private static URLClassLoader resolveUrlClassLoader(final ClassLoader loader) {
    if (loader instanceof final URLClassLoader urlLoader) {
      return urlLoader;
    }
    final URLClassLoader knot = findKnotUrlLoader(loader);
    if (knot != null) {
      return knot;
    }
    final Class<? extends ClassLoader> type = loader.getClass();
    final String name = type.getName();
    throw new JarInjectorException(
      "Cannot add jars to a " + name + "; put the dependencies on the class path or load them from a URLClassLoader"
    );
  }

  // Fabric wraps the class path in a URLClassLoader field of its Knot class loader
  private static @Nullable URLClassLoader findKnotUrlLoader(final ClassLoader loader) {
    Class<?> type = loader.getClass();
    while (type != null) {
      final String name = type.getName();
      if (name.equals(KNOT_CLASS_LOADER)) {
        return readKnotUrlLoader(type, loader);
      }
      type = type.getSuperclass();
    }
    return null;
  }

  private static URLClassLoader readKnotUrlLoader(final Class<?> knotType, final ClassLoader loader) {
    try {
      final MethodHandles.Lookup lookup = MethodHandles.lookup();
      final MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(knotType, lookup);
      final Class<?> urlLoaderType = findFieldType(knotType);
      final VarHandle handle = privateLookup.findVarHandle(knotType, KNOT_URL_LOADER_FIELD, urlLoaderType);
      final Object value = handle.get(loader);
      if (!(value instanceof final URLClassLoader urlLoader)) {
        throw new JarInjectorException("The Fabric class loader has no URL class loader");
      }
      return urlLoader;
    } catch (final IllegalAccessException | NoSuchFieldException exception) {
      final String message = exception.getMessage();
      throw new JarInjectorException("Cannot access the Fabric class loader: " + message, exception);
    }
  }

  private static Class<?> findFieldType(final Class<?> knotType) throws NoSuchFieldException {
    final Field field = knotType.getDeclaredField(KNOT_URL_LOADER_FIELD);
    return field.getType();
  }
}
