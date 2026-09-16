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
package me.brandonli.mcav.utils.immutable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Pair}.
 */
final class PairTest {

  @Test
  void storesBothValues() {
    final Pair<String, Integer> pair = Pair.pair("width", 42);
    final String first = pair.getFirst();
    final Integer second = pair.getSecond();
    assertEquals("width", first);
    assertEquals(42, second);
  }

  @Test
  void followsTheEqualityContract() {
    final Pair<String, Integer> pair = Pair.pair("a", 1);
    final Pair<String, Integer> equalPair = Pair.pair("a", 1);
    final Pair<String, Integer> otherFirst = Pair.pair("b", 1);
    final Pair<String, Integer> otherSecond = Pair.pair("a", 2);
    EqualityAssertions.assertEqualityContract(pair, equalPair, otherFirst, otherSecond);
  }

  @Test
  void rejectsNullValues() {
    assertThrows(NullPointerException.class, () -> Pair.pair(null, 1));
    assertThrows(NullPointerException.class, () -> Pair.pair(1, null));
  }

  @Test
  void rendersBothValues() {
    final Pair<String, Integer> pair = Pair.pair("x", 7);
    final String text = pair.toString();
    assertEquals("Pair[x, 7]", text);
  }
}
