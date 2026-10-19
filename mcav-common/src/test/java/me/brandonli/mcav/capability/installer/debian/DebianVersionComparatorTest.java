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
package me.brandonli.mcav.capability.installer.debian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class DebianVersionComparatorTest {

  private DebianVersionComparator comparator;

  @BeforeEach
  void setUp() {
    this.comparator = new DebianVersionComparator();
  }

  @ParameterizedTest(name = "{0} < {1}")
  @CsvSource({
    "1.0, 1.1",
    "1.2.3, 1.10",
    "1.0~rc1, 1.0",
    "1.0~~, 1.0~",
    "1.0, 1.0.1",
    "1.0a, 1.0+",
    "1.0a, 1.0b",
    "1.0, 1.0-1",
    "1.0-1, 1.0-1+deb12u1",
    "2.36-9+deb12u7, 2.36-9+deb12u10",
    "252.38-1~deb12u1, 252.39-1~deb12u2",
    "3.0.20-1~deb12u2, 3.0.22-1~deb12u1",
    "9.9, 1:0.1",
    "1:2.0, 2:1.0",
    "1.0-1-1, 1.0-1-2",
  })
  void shouldSortTheOlderVersionFirst(final String older, final String newer) {
    final int forward = this.comparator.compare(older, newer);
    final int backward = this.comparator.compare(newer, older);
    assertTrue(forward < 0);
    assertTrue(backward > 0);
  }

  @ParameterizedTest(name = "{0} = {1}")
  @CsvSource({ "1.01, 1.1", "0:1.0, 1.0", "1.0-0, 1.0", "1.0-1, 1.0-1" })
  void shouldTreatVersionsThatDifferOnlyInFormAsEqual(final String first, final String second) {
    final int order = this.comparator.compare(first, second);
    assertEquals(0, order);
  }
}
