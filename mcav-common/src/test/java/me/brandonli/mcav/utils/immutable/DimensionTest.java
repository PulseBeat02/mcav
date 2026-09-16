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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Dimension}.
 */
final class DimensionTest {

  @Test
  void storesWidthAndHeight() {
    final Dimension dimension = Dimension.of(640, 360);
    final int width = dimension.getWidth();
    final int height = dimension.getHeight();
    assertEquals(640, width);
    assertEquals(360, height);
  }

  @Test
  void followsTheEqualityContract() {
    final Dimension dimension = new Dimension(3, 4);
    final Dimension equalDimension = Dimension.of(3, 4);
    final Dimension otherWidth = Dimension.of(5, 4);
    final Dimension otherHeight = Dimension.of(3, 5);
    EqualityAssertions.assertEqualityContract(dimension, equalDimension, otherWidth, otherHeight);
  }

  @Test
  void rejectsNegativeSides() {
    assertThrows(IllegalArgumentException.class, () -> Dimension.of(-1, 1));
    assertThrows(IllegalArgumentException.class, () -> Dimension.of(1, -1));
  }

  @Test
  void isEmptyWhenEitherSideIsZero() {
    final Dimension noWidth = Dimension.of(0, 5);
    final Dimension noHeight = Dimension.of(5, 0);
    final Dimension filled = Dimension.of(1, 1);
    final boolean noneEmpty = Dimension.NONE.isEmpty();
    final boolean noWidthEmpty = noWidth.isEmpty();
    final boolean noHeightEmpty = noHeight.isEmpty();
    final boolean filledEmpty = filled.isEmpty();
    assertTrue(noneEmpty);
    assertTrue(noWidthEmpty);
    assertTrue(noHeightEmpty);
    assertFalse(filledEmpty);
  }

  @Test
  void rendersAsWidthByHeight() {
    final Dimension dimension = Dimension.of(1920, 1080);
    final String text = dimension.toString();
    assertEquals("1920x1080", text);
  }
}
