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

import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import org.checkerframework.checker.nullness.qual.Nullable;

final class ReflectiveInjector extends URLClassLoaderInjector {

  private static final @Nullable Method ADD_URL_METHOD = getUrlMethod();

  private static @Nullable Method getUrlMethod() {
    try {
      final Class<URLClassLoader> clazz = URLClassLoader.class;
      final Method addUrlMethod = clazz.getDeclaredMethod("addURL", URL.class);
      addUrlMethod.setAccessible(true);
      return addUrlMethod;
    } catch (final NoSuchMethodException | SecurityException | InaccessibleObjectException e) {
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
  public void addURL(final URL url) {
    try {
      if (ADD_URL_METHOD == null) {
        throw new UnsupportedOperationException("Not supported");
      }
      ADD_URL_METHOD.invoke(super.getClassLoader(), url);
    } catch (final ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
