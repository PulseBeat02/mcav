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
package me.brandonli.mcav.sandbox.utils.unsafe;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import sun.misc.Unsafe;

public final class UnsafeUtils {

  private static final Unsafe UNSAFE = UnsafeProvider.getUnsafe();

  private UnsafeUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static Object getField(final Object object, final String name) throws NoSuchFieldException {
    return getField(object.getClass(), object, name);
  }

  @SuppressWarnings("deprecation")
  public static Object getField(final Class<?> clazz, final Object object, final String name) throws NoSuchFieldException {
    return UNSAFE.getObject(object, UNSAFE.objectFieldOffset(clazz.getDeclaredField(name)));
  }

  public static void loadJarIntoRuntime(final Path path) {
    try {
      final URI uri = path.toUri();
      final URL url = uri.toURL();
      final ClassLoader unwritable = ClassLoader.getSystemClassLoader();
      final Class<?> clazz = Class.forName("jdk.internal.loader.BuiltinClassLoader");
      final Object writable = UnsafeUtils.getField(clazz, unwritable, "ucp");
      @SuppressWarnings("unchecked")
      final ArrayList<URL> urls = (ArrayList<URL>) UnsafeUtils.getField(writable, "path");
      @SuppressWarnings("unchecked")
      final ArrayDeque<URL> unopenedUrls = (ArrayDeque<URL>) UnsafeUtils.getField(writable, "unopenedUrls");
      urls.add(url);
      unopenedUrls.addLast(url);
    } catch (final NoSuchFieldException | ClassNotFoundException | MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }
}
