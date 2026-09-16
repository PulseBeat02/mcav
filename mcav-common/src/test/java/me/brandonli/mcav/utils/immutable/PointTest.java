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

import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Point}.
 */
final class PointTest {

  @Test
  void storesCoordinates() {
    final Point point = Point.point(1.5, -2.25);
    final double x = point.getX();
    final double y = point.getY();
    assertEquals(1.5, x);
    assertEquals(-2.25, y);
  }

  @Test
  void followsTheEqualityContract() {
    final Point point = Point.point(1.0, 2.0);
    final Point equalPoint = Point.point(1.0, 2.0);
    final Point otherX = Point.point(3.0, 2.0);
    final Point otherY = Point.point(1.0, 3.0);
    EqualityAssertions.assertEqualityContract(point, equalPoint, otherX, otherY);
  }

  @Test
  void treatsNotANumberAsEqualToItself() {
    final Point point = Point.point(Double.NaN, 0.0);
    final Point equalPoint = Point.point(Double.NaN, 0.0);
    EqualityAssertions.assertEqualityContract(point, equalPoint);
  }

  @Test
  void rendersBothCoordinates() {
    final Point point = Point.point(0.5, 4.0);
    final String text = point.toString();
    assertEquals("Point[0.5, 4.0]", text);
  }
}
