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

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import org.checkerframework.checker.nullness.qual.Nullable;
import sun.misc.Unsafe;

/**
 * Accesses using sun.misc.Unsafe, supported on Java 9+.
 *
 * @author Vaishnav Anil (<a href="https://github.com/slimjar/slimjar">...</a>)
 */
final class UnsafeInjector extends URLClassLoaderInjector {

  private static final @Nullable Unsafe UNSAFE = getUnsafe();

  @SuppressWarnings("nullness")
  private static @Nullable Unsafe getUnsafe() {
    try {
      final Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
      theUnsafeField.setAccessible(true);
      return (Unsafe) theUnsafeField.get(null);
    } catch (final Throwable t) {
      return null;
    }
  }

  static boolean isSupported() {
    return UNSAFE != null;
  }

  private final @Nullable Collection<URL> unopenedURLs;
  private final @Nullable Collection<URL> pathURLs;

  UnsafeInjector(final URLClassLoader classLoader) {
    super(classLoader);
    this.unopenedURLs = this.getUnopenedURLs(classLoader);
    this.pathURLs = this.getPathURLs(classLoader);
  }

  @SuppressWarnings("unchecked")
  private @Nullable Collection<URL> getPathURLs(final URLClassLoader classLoader) {
    try {
      final Object ucp = fetchField(URLClassLoader.class, classLoader, "ucp");
      final Class<?> clazz = ucp.getClass();
      return (Collection<URL>) fetchField(clazz, ucp, "path");
    } catch (final Throwable e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private @Nullable Collection<URL> getUnopenedURLs(final URLClassLoader classLoader) {
    try {
      final Object ucp = fetchField(URLClassLoader.class, classLoader, "ucp");
      final Class<?> clazz = ucp.getClass();
      return (Collection<URL>) fetchField(clazz, ucp, "unopenedUrls");
    } catch (final Throwable e) {
      return null;
    }
  }

  private static Object fetchField(final Class<?> clazz, final Object object, final String name) throws NoSuchFieldException {
    if (UNSAFE == null) {
      throw new IllegalStateException("Not supported");
    }
    final Field field = clazz.getDeclaredField(name);
    final long offset = UNSAFE.objectFieldOffset(field);
    return UNSAFE.getObject(object, offset);
  }

  @Override
  public void addURL(final URL url) {
    if (this.unopenedURLs == null || this.pathURLs == null) {
      throw new UnsupportedOperationException("Not supported");
    }
    this.unopenedURLs.add(url);
    this.pathURLs.add(url);
  }
}
