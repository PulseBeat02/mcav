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
package me.brandonli.mcav.bukkit.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

/**
 * Assertions for utility classes, which must be final and must refuse to be instantiated.
 */
public final class UtilityClassAssertions {

  private UtilityClassAssertions() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Asserts that a class is final, has exactly one constructor, that the constructor is private, and that calling
   * it anyway through reflection throws an {@link UnsupportedOperationException}.
   *
   * @param utilityClass the utility class
   */
  public static void assertNotInstantiable(final Class<?> utilityClass) {
    final int classModifiers = utilityClass.getModifiers();
    final boolean finalClass = Modifier.isFinal(classModifiers);
    assertTrue(finalClass, "utility classes must be final");

    final Constructor<?>[] constructors = utilityClass.getDeclaredConstructors();
    assertEquals(1, constructors.length);
    final Constructor<?> constructor = constructors[0];
    final int constructorModifiers = constructor.getModifiers();
    final boolean privateConstructor = Modifier.isPrivate(constructorModifiers);
    assertTrue(privateConstructor, "the constructor of a utility class must be private");

    constructor.setAccessible(true);
    final InvocationTargetException exception = assertThrows(InvocationTargetException.class, constructor::newInstance);
    final Throwable cause = exception.getCause();
    assertInstanceOf(UnsupportedOperationException.class, cause);
  }
}
