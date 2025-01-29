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

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Enumeration;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class LoaderUtils {

  private static final int MAX_ENTRIES = 100_000;
  private static final int MAX_BYTES = 1024 * 1024 * 1024;
  private static final String META_INF_SERVICES = "META-INF/services/";
  private static final int MAX_SERVICE_NAME_LENGTH = META_INF_SERVICES.length();

  private LoaderUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  static boolean loadJarPaths(final Collection<Path> jars, final ClassLoader loader) {
    jars.forEach(jar -> loadJarPath(jar, loader));
    return true;
  }

  static boolean loadJarPath(final Path jarPath, final ClassLoader loader) {
    try {
      addJarPath(jarPath, loader);
      loadServiceProviders(jarPath, loader);
    } catch (final IOException e) {
      throw new JarInjectorException(e.getMessage());
    }
    return true;
  }

  private static boolean addJarPath(final Path jarPath, final ClassLoader loader) throws MalformedURLException {
    if (isUrlClassLoader(loader)) {
      loadIntoUrlClassLoader(jarPath, (URLClassLoader) loader);
      return true;
    } else if (isKnotClassLoader(loader)) {
      loadIntoUrlClassLoader(jarPath, getUrlClassLoaderFromKnotClassLoader(loader));
      return true;
    } else {
      throw new JarInjectorException("Unsupported ClassLoader type!");
    }
  }

  private static URLClassLoader getUrlClassLoaderFromKnotClassLoader(final ClassLoader loader) {
    try {
      final Class<?> clazz = loader.getClass();
      final String name = clazz.getName();
      final String innerClass = String.format("%s$DynamicURLClassLoader", name);
      final Class<?> dynamicLoaderClass = Class.forName(innerClass);
      final MethodHandles.Lookup defaultLookup = MethodHandles.lookup();
      final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, defaultLookup);
      final VarHandle urlLoaderHandle = lookup.findVarHandle(clazz, "urlLoader", dynamicLoaderClass);
      return (URLClassLoader) urlLoaderHandle.get(loader);
    } catch (final IllegalAccessException | ClassNotFoundException | NoSuchFieldException e) {
      throw new JarInjectorException(e.getMessage());
    }
  }

  private static void loadIntoUrlClassLoader(final Path jarPath, final URLClassLoader loader) throws MalformedURLException {
    final URLClassLoaderInjector injector = URLClassLoaderInjector.create(loader);
    final URI uri = jarPath.toUri();
    final URL url = uri.toURL();
    injector.addURL(url);
  }

  private static boolean isUrlClassLoader(final ClassLoader loader) {
    return loader instanceof URLClassLoader;
  }

  private static boolean isKnotClassLoader(final ClassLoader loader) {
    try {
      final Class<?> clazz = Class.forName("net.fabricmc.loader.impl.launch.knot.KnotClassLoader");
      final Class<?> loaderClazz = loader.getClass();
      return loaderClazz.isAssignableFrom(clazz);
    } catch (final ClassNotFoundException e) {
      return false;
    }
  }

  private static boolean loadServiceProviders(final Path jarPath, final ClassLoader loader) throws IOException {
    int entryCount = 0;
    final Path absolute = jarPath.toAbsolutePath();
    final String pathString = absolute.toString();
    try (final JarFile jarFile = new JarFile(pathString, true)) {
      final Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        final JarEntry entry = entries.nextElement();
        final String name = entry.getName();
        verifyEntry(++entryCount, entry);
        if (!name.startsWith(META_INF_SERVICES) || entry.isDirectory()) {
          continue;
        }
        loadServiceEntry(loader, name);
      }
    }
    return true;
  }

  private static void loadServiceEntry(final ClassLoader loader, final String name) {
    final String serviceName = name.substring(MAX_SERVICE_NAME_LENGTH);
    try {
      final Class<?> serviceClass = Class.forName(serviceName, false, loader);
      ServiceLoader.load(serviceClass, loader);
    } catch (final ClassNotFoundException ignored) {
      // ignore classes that cannot be loaded
    }
  }

  private static void verifyEntry(final int entryCount, final JarEntry entry) {
    if (entryCount > MAX_ENTRIES) {
      throw new JarEntryIntegrityException("Too many entries in JAR file!");
    }

    final String name = entry.getName();
    if (name.contains("..")) {
      throw new JarEntryIntegrityException("Invalid JAR entry name!");
    }

    final long size = entry.getSize();
    if (size > MAX_BYTES) {
      throw new JarEntryIntegrityException("JAR entry too large!");
    }
  }
}
