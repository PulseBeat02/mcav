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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Calls {@code URLClassLoader.addURL} through a private method handle. Works when {@code java.net} is open to the
 * installer, which is the case for most server launchers.
 */
final class ReflectiveInjector extends URLClassLoaderInjector {

  /**
   * The {@code addURL} method of {@link URLClassLoader}, or null if {@code java.net} is not opened to this module.
   */
  static final @Nullable MethodHandle ADD_URL = findAddUrl();

  private final MethodHandle addUrl;

  /**
   * Creates an injector.
   *
   * @param classLoader the class loader to add jars to
   * @param addUrl      a handle that takes the class loader and the URL, normally {@link #ADD_URL}
   */
  ReflectiveInjector(final URLClassLoader classLoader, final MethodHandle addUrl) {
    super(classLoader);
    this.addUrl = addUrl;
  }

  private static @Nullable MethodHandle findAddUrl() {
    final MethodHandles.Lookup lookup = MethodHandles.lookup();
    return findAddUrl(lookup);
  }

  /**
   * Finds the {@code addURL} method of {@link URLClassLoader}. Visible for testing.
   *
   * @param lookup the lookup that needs private access to {@link URLClassLoader}
   * @return the handle, or null if the lookup has no access
   */
  static @Nullable MethodHandle findAddUrl(final MethodHandles.Lookup lookup) {
    try {
      final MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(URLClassLoader.class, lookup);
      final MethodType type = MethodType.methodType(void.class, URL.class);
      return privateLookup.findVirtual(URLClassLoader.class, "addURL", type);
    } catch (final IllegalAccessException | NoSuchMethodException | RuntimeException exception) {
      return null;
    }
  }

  @Override
  void addURL(final URL url) {
    final URLClassLoader loader = this.getClassLoader();
    try {
      this.addUrl.invoke(loader, url);
    } catch (final RuntimeException | Error exception) {
      // unchecked failures of addURL, every Error included, reach the caller unchanged, so none is hidden or wrapped
      throw exception;
    } catch (final Throwable throwable) {
      // invoke declares Throwable, so only checked exceptions get here; they mean that the jar cannot be added
      final String message = throwable.getMessage();
      throw new JarInjectorException("Failed to add " + url + ": " + message, throwable);
    }
  }
}
