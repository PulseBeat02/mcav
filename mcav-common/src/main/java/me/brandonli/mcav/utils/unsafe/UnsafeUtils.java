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
package me.brandonli.mcav.utils.unsafe;

import java.lang.reflect.Field;
import java.util.Collection;
import me.brandonli.mcav.utils.natives.NativeLoadingException;
import sun.misc.Unsafe;

/**
 * A utility class for unsafe operations, specifically for modifying the Java library path.
 * This class uses the Unsafe API to add paths to the Java library path at runtime.
 */
@SuppressWarnings("deprecation")
public final class UnsafeUtils {

  private static final Object USER_BASE;
  private static final long USER_OFFSET;

  private static final Object SYS_BASE;
  private static final long SYS_OFFSET;

  static {
    final Unsafe unsafe = UnsafeProvider.getUnsafe();
    try {
      final Class<?> clazz = Class.forName("jdk.internal.loader.NativeLibraries$LibraryPaths");
      final Field field = clazz.getDeclaredField("USER_PATHS");
      final Field sysField = clazz.getDeclaredField("SYS_PATHS");
      USER_BASE = unsafe.staticFieldBase(field);
      USER_OFFSET = unsafe.staticFieldOffset(field);
      SYS_BASE = unsafe.staticFieldBase(sysField);
      SYS_OFFSET = unsafe.staticFieldOffset(sysField);
    } catch (final ClassNotFoundException | NoSuchFieldException e) {
      throw new NativeLoadingException("Failed to initialize Unsafe", e);
    }
  }

  private UnsafeUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Adds a new path to the Java library path using Unsafe.
   *
   * @param paths the path to add
   */
  @SuppressWarnings("all") // checker
  public static void addJavaLibraryPaths(final Collection<String> paths) {
    final Unsafe unsafe = UnsafeProvider.getUnsafe();
    final String[] currentPaths = (String[]) unsafe.getObject(USER_BASE, USER_OFFSET);
    final String[] newPaths = new String[currentPaths.length + paths.size()];
    System.arraycopy(currentPaths, 0, newPaths, 0, currentPaths.length);
    int index = currentPaths.length;
    for (final String path : paths) {
      newPaths[index++] = path;
    }
    unsafe.putObject(USER_BASE, USER_OFFSET, newPaths);
    System.setProperty("java.library.path", String.join(":", newPaths));
  }
}
