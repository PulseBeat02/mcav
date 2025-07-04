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
package me.brandonli.mcav.utils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Utility class for reflection operations.
 */
public final class ReflectionUtils {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  private ReflectionUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Creates a new instance of the specified class using its default constructor.
   *
   * @param clazz the class to instantiate
   * @param <T>   the type of the class
   * @return a new instance of the specified class
   * @throws RuntimeException if instantiation fails
   */
  @SuppressWarnings("unchecked")
  public static <T> T newInstance(final Class<T> clazz) {
    try {
      final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, LOOKUP);
      final MethodType type = MethodType.methodType(void.class);
      final MethodHandle constructor = lookup.findConstructor(clazz, type);
      return (T) constructor.invoke();
    } catch (final Throwable e) {
      throw new RuntimeException(e);
    }
  }
}
