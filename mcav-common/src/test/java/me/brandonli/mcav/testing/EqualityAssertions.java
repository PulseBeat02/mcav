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
package me.brandonli.mcav.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Assertions for the {@link Object#equals(Object)} and {@link Object#hashCode()} contract of value types.
 */
public final class EqualityAssertions {

  private EqualityAssertions() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Asserts that a value equals itself, that two values are equal in both directions with equal hash codes, that
   * the value equals neither {@code null} nor an unrelated object, and that the value differs from every one of the
   * different values in both directions and remains distinct from them as a hash map key.
   *
   * <p>Unequal values may have equal hash codes, even when only one property differs. Hash maps use equality to
   * distinguish keys that collide; the contract requires equal hashes only for equal values.
   *
   * @param value           the value under test
   * @param equalValue      a distinct instance that must be equal to the value
   * @param differentValues instances that must not be equal to the value, each differing in one property
   */
  public static void assertEqualityContract(final Object value, final Object equalValue, final Object... differentValues) {
    Preconditions.checkNotNull(value, "Value must not be null");
    Preconditions.checkNotNull(equalValue, "Equal value must not be null");
    Preconditions.checkNotNull(differentValues, "Different values must not be null");

    assertReflexiveAndSymmetric(value, equalValue);
    assertEqualHashCodes(value, equalValue);
    assertNotEqualToNullOrUnrelated(value);
    for (final Object differentValue : differentValues) {
      assertDifferentInBothDirections(value, differentValue);
      assertDistinctMapKeys(value, equalValue, differentValue);
    }
  }

  private static void assertReflexiveAndSymmetric(final Object value, final Object equalValue) {
    final boolean reflexive = isEqual(value, value);
    final boolean forward = isEqual(value, equalValue);
    final boolean backward = isEqual(equalValue, value);
    assertTrue(reflexive, "a value must equal itself");
    assertTrue(forward, "the value must equal the equal value");
    assertTrue(backward, "the equal value must equal the value");
  }

  private static void assertEqualHashCodes(final Object value, final Object equalValue) {
    final int hash = value.hashCode();
    final int equalHash = equalValue.hashCode();
    assertEquals(hash, equalHash, "equal values must have equal hash codes");
  }

  private static void assertDistinctMapKeys(final Object value, final Object equalValue, final Object differentValue) {
    final Map<Object, String> values = new HashMap<>();
    values.put(value, "original");
    values.put(differentValue, "different");
    final int size = values.size();
    final String original = values.get(equalValue);
    final String different = values.get(differentValue);
    assertEquals(2, size, "unequal values remain distinct keys, even if their hashes collide");
    assertEquals("original", original, "an equal value retrieves the original entry");
    assertEquals("different", different);
  }

  private static void assertNotEqualToNullOrUnrelated(final Object value) {
    final Object unrelated = new Object();
    @Nullable final Object[] outsiders = { null, unrelated };
    for (@Nullable final Object outsider : outsiders) {
      final boolean equal = isEqual(value, outsider);
      assertFalse(equal, () -> "a value must not equal " + outsider);
    }
  }

  private static void assertDifferentInBothDirections(final Object value, final Object differentValue) {
    final boolean forward = isEqual(value, differentValue);
    final boolean backward = isEqual(differentValue, value);
    assertFalse(forward, () -> "the value must differ from " + differentValue);
    assertFalse(backward, () -> differentValue + " must differ from the value");
  }

  /**
   * Calls {@link Object#equals(Object)} directly, so the assertions check the implementation of the value rather than
   * a shortcut such as the identity check of {@link java.util.Objects#equals(Object, Object)}.
   */
  private static boolean isEqual(final Object receiver, final @Nullable Object argument) {
    return receiver.equals(argument);
  }
}
