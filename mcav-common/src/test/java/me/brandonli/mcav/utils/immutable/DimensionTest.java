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

import java.util.Objects;
import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

final class DimensionTest {

  private static final int WIDTH = 3;
  private static final int HEIGHT = 4;

  @Test
  void shouldStoreWidthAndHeight() {
    final Dimension dimension = Dimension.of(640, 360);
    final int width = dimension.getWidth();
    final int height = dimension.getHeight();
    assertEquals(640, width);
    assertEquals(360, height);
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final Dimension dimension = new Dimension(WIDTH, HEIGHT);
    final Dimension equalDimension = Dimension.of(WIDTH, HEIGHT);
    final Dimension otherWidth = Dimension.of(5, HEIGHT);
    final Dimension otherHeight = Dimension.of(WIDTH, 5);
    EqualityAssertions.assertEqualityContract(dimension, equalDimension, otherWidth, otherHeight);
  }

  @Test
  void shouldHashTheWidthThenTheHeight() {
    final Dimension dimension = Dimension.of(WIDTH, HEIGHT);

    final int hash = dimension.hashCode();
    final int expected = Objects.hash(WIDTH, HEIGHT);
    assertEquals(expected, hash);
  }

  @Test
  void shouldRejectNegativeSides() {
    assertThrows(IllegalArgumentException.class, () -> Dimension.of(-1, 1));
    assertThrows(IllegalArgumentException.class, () -> Dimension.of(1, -1));
  }

  @Test
  void shouldBeEmptyWhenEitherSideIsZero() {
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
  void shouldHaveNeitherWidthNorHeightWhenNone() {
    final Dimension none = Dimension.NONE;
    final Dimension expected = Dimension.of(0, 0);
    assertEquals(expected, none);
  }

  @Test
  void shouldRenderBothSides() {
    final Dimension dimension = Dimension.of(1920, 1080);
    final String text = dimension.toString();
    assertEquals("Dimension{width=1920, height=1080}", text);
  }
}
