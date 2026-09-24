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

import org.junit.jupiter.api.Test;

/** Tests that the equality helper accepts valid hash collisions. */
final class EqualityAssertionsTest {

  @Test
  void unequalValuesMayHaveTheSameHashCode() {
    final String first = "Aa";
    final String equal = String.valueOf(new char[] { 'A', 'a' });
    final String different = "BB";
    final int firstHash = first.hashCode();
    final int differentHash = different.hashCode();
    assertEquals(firstHash, differentHash, "31 * 65 + 97 equals 31 * 66 + 66");
    EqualityAssertions.assertEqualityContract(first, equal, different);
  }
}
