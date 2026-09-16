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
package me.brandonli.mcav.browser.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Assertions for the {@link Object#equals(Object)} and {@link Object#hashCode()} contract of value types.
 */
public final class EqualityAssertions {

  private EqualityAssertions() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Asserts that two values are equal in both directions with equal hash codes, that neither equals {@code null} or
   * an unrelated object, and that the value differs from every one of the different values in both directions.
   *
   * @param value           the value under test
   * @param equalValue      a distinct instance that must be equal to the value
   * @param differentValues instances that must not be equal to the value, ideally each differing in one property
   */
  public static void assertEqualityContract(final Object value, final Object equalValue, final Object... differentValues) {
    final Object[] sameInstance = { value };
    assertEquals(value, sameInstance[0]);
    assertEquals(value, equalValue);
    assertEquals(equalValue, value);
    final int hash = value.hashCode();
    final int equalHash = equalValue.hashCode();
    assertEquals(hash, equalHash);

    final Object unrelated = new Object();
    final List<@Nullable Object> strangers = Arrays.asList(null, unrelated);
    for (final Object stranger : strangers) {
      final boolean equalsStranger = value.equals(stranger);
      assertFalse(equalsStranger, "a value must not equal null or an unrelated object");
    }

    for (final Object differentValue : differentValues) {
      assertNotEquals(value, differentValue);
      assertNotEquals(differentValue, value);
    }
  }
}
