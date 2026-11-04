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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

final class PairTest {

  private static final String FIRST_VALUE = "a";

  @Test
  void shouldStoreBothValues() {
    final Pair<String, Integer> pair = Pair.pair("width", 42);
    final String first = pair.getFirst();
    final Integer second = pair.getSecond();
    assertEquals("width", first);
    assertEquals(42, second);
  }

  @Test
  void shouldKeepItsValuesByReference() {
    final List<String> first = new ArrayList<>();
    final StringBuilder second = new StringBuilder();
    final Pair<List<String>, StringBuilder> pair = Pair.pair(first, second);
    final List<String> keptFirst = pair.getFirst();
    final StringBuilder keptSecond = pair.getSecond();
    assertSame(first, keptFirst);
    assertSame(second, keptSecond);
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final Pair<String, Integer> pair = Pair.pair(FIRST_VALUE, 1);
    final Pair<String, Integer> equalPair = Pair.pair(FIRST_VALUE, 1);
    final Pair<String, Integer> otherFirst = Pair.pair("b", 1);
    final Pair<String, Integer> otherSecond = Pair.pair(FIRST_VALUE, 2);
    EqualityAssertions.assertEqualityContract(pair, equalPair, otherFirst, otherSecond);
  }

  @Test
  void shouldHashBothValues() {
    final Pair<String, Integer> pair = Pair.pair(FIRST_VALUE, 1);

    final int hash = pair.hashCode();
    final int expected = Objects.hash(FIRST_VALUE, 1);
    assertEquals(expected, hash);
  }

  @Test
  void shouldRejectNullValues() {
    assertThrows(NullPointerException.class, () -> Pair.pair(null, 1));
    assertThrows(NullPointerException.class, () -> Pair.pair(1, null));
  }

  @Test
  void shouldRenderBothValues() {
    final Pair<String, Integer> pair = Pair.pair("x", 7);
    final String text = pair.toString();
    assertEquals("Pair{first=x, second=7}", text);
  }
}
