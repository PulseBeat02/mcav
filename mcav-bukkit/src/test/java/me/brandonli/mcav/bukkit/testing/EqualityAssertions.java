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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class EqualityAssertions {

  private static final String ORIGINAL_ENTRY = "original";
  private static final String DIFFERENT_ENTRY = "different";

  private EqualityAssertions() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  public static void assertEqualityContract(final Object value, final Object equalValue, final Object... differentValues) {
    Preconditions.checkNotNull(value, "Value must not be null!");
    Preconditions.checkNotNull(equalValue, "Equal value must not be null!");
    Preconditions.checkNotNull(differentValues, "Different values must not be null!");

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
    values.put(value, ORIGINAL_ENTRY);
    values.put(differentValue, DIFFERENT_ENTRY);
    final int size = values.size();
    final String original = values.get(equalValue);
    final String different = values.get(differentValue);
    assertEquals(2, size, "unequal values remain distinct keys, even if their hashes collide");
    assertEquals(ORIGINAL_ENTRY, original, "an equal value retrieves the original entry");
    assertEquals(DIFFERENT_ENTRY, different);
  }

  private static void assertNotEqualToNullOrUnrelated(final Object value) {
    final Object unrelated = new Object();
    final @Nullable Object[] outsiders = { null, unrelated };
    for (final @Nullable Object outsider : outsiders) {
      final boolean equal = isEqual(value, outsider);
      assertFalse(equal, () -> "a value must not equal %s".formatted(outsider));
    }
  }

  private static void assertDifferentInBothDirections(final Object value, final Object differentValue) {
    final boolean forward = isEqual(value, differentValue);
    final boolean backward = isEqual(differentValue, value);
    assertFalse(forward, () -> "the value must differ from %s".formatted(differentValue));
    assertFalse(backward, () -> "%s must differ from the value".formatted(differentValue));
  }

  // not Objects.equals, whose identity shortcut would skip the implementation
  private static boolean isEqual(final Object receiver, final @Nullable Object argument) {
    return receiver.equals(argument);
  }
}
