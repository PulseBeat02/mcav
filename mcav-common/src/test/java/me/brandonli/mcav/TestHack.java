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
package me.brandonli.mcav;

import java.lang.reflect.Field;
import java.util.Arrays;
import sun.misc.Unsafe;

public class TestHack {

  @SuppressWarnings("all")
  public static void main(final String[] args) throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
    final Class<?> clazz = Class.forName("jdk.internal.loader.NativeLibraries$LibraryPaths");
    final Field field = clazz.getDeclaredField("USER_PATHS");

    final String property = "example";
    System.setProperty("java.library.path", System.getProperty("java.library.path") + ";" + property);

    final Field f = Unsafe.class.getDeclaredField("theUnsafe");
    f.setAccessible(true);

    final Unsafe unsafe = (Unsafe) f.get(null);
    final Object base = unsafe.staticFieldBase(field);
    final long offset = unsafe.staticFieldOffset(field);
    final String[] SYS_PATHS = (String[]) unsafe.getObject(base, offset);
    System.out.println(Arrays.toString(SYS_PATHS));
    //    unsafe.putObject(base, offset, null);

    System.out.println(System.getProperty("java.library.path"));
  }
}
