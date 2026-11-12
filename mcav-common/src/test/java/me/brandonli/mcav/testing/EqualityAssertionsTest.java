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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class EqualityAssertionsTest {

  @Test
  void shouldAllowUnequalValuesToHaveTheSameHashCode() {
    final String first = "Aa";
    final String equal = String.valueOf(new char[] { 'A', 'a' });
    final String different = "BB";
    final int firstHash = first.hashCode();
    final int differentHash = different.hashCode();
    assertEquals(firstHash, differentHash, "31 * 65 + 97 equals 31 * 66 + 66");

    EqualityAssertions.assertEqualityContract(first, equal, different);
  }

  @ParameterizedTest
  @MethodSource("brokenContracts")
  void shouldFailForValuesThatBreakTheContract(final Object value, final Object equalValue, final Object[] differentValues) {
    assertThrows(AssertionError.class, () -> EqualityAssertions.assertEqualityContract(value, equalValue, differentValues));
  }

  static Stream<Arguments> brokenContracts() {
    final String text = "text";
    final ScriptedValue equalToNothing = new ScriptedValue(0, _ -> false);
    final ScriptedValue equalToEverything = new ScriptedValue(0, _ -> true);
    final ScriptedValue equalToAnyObject = new ScriptedValue(0, Objects::nonNull);
    final ScriptedValue firstHash = new ScriptedValue(1, ScriptedValue.class::isInstance);
    final ScriptedValue secondHash = new ScriptedValue(2, ScriptedValue.class::isInstance);
    final Object[] noDifferentValues = {};
    final Object[] equalText = { text };
    final Object[] equalFromItsSideOnly = { equalToAnyObject };
    return Stream.of(
      Arguments.argumentSet("a value that differs from itself", equalToNothing, equalToNothing, noDifferentValues),
      Arguments.argumentSet("an equal value that is not equal", text, "other", noDifferentValues),
      Arguments.argumentSet("equality in one direction only", equalToAnyObject, equalToNothing, noDifferentValues),
      Arguments.argumentSet("equal values with different hash codes", firstHash, secondHash, noDifferentValues),
      Arguments.argumentSet("a value equal to null", equalToEverything, equalToEverything, noDifferentValues),
      Arguments.argumentSet("a value equal to an unrelated object", equalToAnyObject, equalToAnyObject, noDifferentValues),
      Arguments.argumentSet("a different value that is equal", text, text, equalText),
      Arguments.argumentSet("a different value that is equal from its side only", text, text, equalFromItsSideOnly)
    );
  }

  private static final class ScriptedValue {

    private final int hash;
    private final Predicate<@Nullable Object> equality;

    ScriptedValue(final int hash, final Predicate<@Nullable Object> equality) {
      this.hash = hash;
      this.equality = equality;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      return this.equality.test(other);
    }

    @Override
    public int hashCode() {
      return this.hash;
    }
  }
}
