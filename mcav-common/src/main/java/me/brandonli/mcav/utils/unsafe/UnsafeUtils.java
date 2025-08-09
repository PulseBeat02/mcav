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
import java.util.HashMap;
import java.util.Map;
import me.brandonli.mcav.CLibrary;
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

  private static final Object SYSTEM_BASE;
  private static final long SYSTEM_OFFSET;

  private static final Object ENV_BASE;
  private static final long ENV_OFFSET;

  static {
    final Unsafe unsafe = UnsafeProvider.getUnsafe();
    try {
      final Class<?> clazz = Class.forName("jdk.internal.loader.NativeLibraries$LibraryPaths");
      final Class<?> systemClass = Class.forName("java.lang.ProcessEnvironment");
      final Field field = clazz.getDeclaredField("USER_PATHS");
      final Field systemField = clazz.getDeclaredField("SYS_PATHS");
      final Field envField = systemClass.getDeclaredField("theUnmodifiableEnvironment"); // sike
      USER_BASE = unsafe.staticFieldBase(field);
      USER_OFFSET = unsafe.staticFieldOffset(field);
      SYSTEM_BASE = unsafe.staticFieldBase(systemField);
      SYSTEM_OFFSET = unsafe.staticFieldOffset(systemField);
      ENV_BASE = unsafe.staticFieldBase(envField);
      ENV_OFFSET = unsafe.staticFieldOffset(envField);
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
   * @param path the path to add
   */
  @SuppressWarnings("all") // checker
  public static void setJavaLibraryPath(final String path) {
    final Unsafe unsafe = UnsafeProvider.getUnsafe();
    final String[] currentPaths = (String[]) unsafe.getObject(USER_BASE, USER_OFFSET);
    final String[] newPaths = new String[currentPaths.length + 1];
    System.arraycopy(currentPaths, 0, newPaths, 0, currentPaths.length);
    newPaths[newPaths.length - 1] = path;
    unsafe.putObject(USER_BASE, USER_OFFSET, newPaths);

    final String[] systemPaths = (String[]) unsafe.getObject(SYSTEM_BASE, SYSTEM_OFFSET);
    final String[] newSystemPaths = new String[systemPaths.length + 1];
    System.arraycopy(systemPaths, 0, newSystemPaths, 0, systemPaths.length);
    newSystemPaths[newSystemPaths.length - 1] = path;
    unsafe.putObject(SYSTEM_BASE, SYSTEM_OFFSET, newSystemPaths);

    final String current = System.getProperty("platform.linkpath");
    final String newPath = current == null ? path : current + ":" + path;
    System.setProperty("platform.linkpath", newPath);

    final Map<String, String> env = (Map<String, String>) unsafe.getObject(ENV_BASE, ENV_OFFSET);
    final Map<String, String> mutableCopy = new HashMap<>();
    for (final String key : env.keySet()) {
      final String value = env.get(key);
      if (key.equals("LD_LIBRARY_PATH")) {
        mutableCopy.put(key, value == null ? path : value + ":" + path);
        return;
      } else {
        mutableCopy.put(key, value);
      }
    }
    unsafe.putObject(ENV_BASE, ENV_OFFSET, mutableCopy);

    final CLibrary library = CLibrary.INSTANCE;
    final String currentLDPath = library.getenv("LD_LIBRARY_PATH");
    library.setenv("LD_LIBRARY_PATH", currentLDPath == null ? path : currentLDPath + ":" + path, 1);
  }
}
