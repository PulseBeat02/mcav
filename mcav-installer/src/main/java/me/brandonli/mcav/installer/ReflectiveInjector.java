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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Injector that uses reflection to add URLs to a {@link URLClassLoader}.
 * This is used when the addUrl method is not accessible.
 */
final class ReflectiveInjector extends URLClassLoaderInjector {

  private static final @Nullable Method ADD_URL_METHOD = getUrlMethod();

  private static @Nullable Method getUrlMethod() {
    try {
      final MethodHandles.Lookup normal = MethodHandles.lookup();
      final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(URLClassLoader.class, normal);
      final MethodType methodType = MethodType.methodType(Void.TYPE, URL.class);
      final MethodHandle methodHandle = lookup.findVirtual(URLClassLoader.class, "addURL", methodType);
      return MethodHandles.reflectAs(Method.class, methodHandle);
    } catch (final Throwable t) {
      return null;
    }
  }

  static boolean isSupported() {
    return ADD_URL_METHOD != null;
  }

  ReflectiveInjector(final URLClassLoader classLoader) {
    super(classLoader);
  }

  @Override
  void addURL(final URL url) {
    if (ADD_URL_METHOD == null) {
      throw new JarInjectorException("Reflective injector not supported");
    }
    try {
      final ClassLoader classLoader = super.getClassLoader();
      ADD_URL_METHOD.invoke(classLoader, url);
    } catch (final IllegalAccessException | InvocationTargetException e) {
      throw new JarInjectorException(e.getMessage(), e);
    }
  }
}
