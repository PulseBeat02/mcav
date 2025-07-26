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
import sun.misc.Unsafe;

/**
 * A utility class to provide access to the {@link Unsafe} instance.
 */
@SuppressWarnings("all") // checker...
public final class UnsafeProvider {

  private static final Unsafe UNSAFE;

  static {
    try {
      final Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      UNSAFE = (Unsafe) field.get(null);
    } catch (final IllegalAccessException | NoSuchFieldException e) {
      throw new AssertionError(e);
    }
  }

  private UnsafeProvider() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Gets the Unsafe instance.
   *
   * @return the Unsafe instance
   */
  public static Unsafe getUnsafe() {
    return UNSAFE;
  }
}
