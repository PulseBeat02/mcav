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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An immutable point in two dimensions.
 */
public final class Point {

  private final double x;
  private final double y;

  Point(final double x, final double y) {
    this.x = x;
    this.y = y;
  }

  /**
   * Creates a point.
   *
   * @param x the x coordinate
   * @param y the y coordinate
   * @return the point
   */
  public static Point point(final double x, final double y) {
    return new Point(x, y);
  }

  /**
   * Gets the x coordinate.
   *
   * @return the x coordinate
   */
  public double getX() {
    return this.x;
  }

  /**
   * Gets the y coordinate.
   *
   * @return the y coordinate
   */
  public double getY() {
    return this.y;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof final Point point)) {
      return false;
    }
    final int xComparison = Double.compare(this.x, point.x);
    final int yComparison = Double.compare(this.y, point.y);
    return xComparison == 0 && yComparison == 0;
  }

  @Override
  public int hashCode() {
    final long xBits = Double.doubleToLongBits(this.x);
    final long yBits = Double.doubleToLongBits(this.y);
    return Long.hashCode(xBits) * 31 + Long.hashCode(yBits);
  }

  @Override
  public String toString() {
    return "Point[" + this.x + ", " + this.y + "]";
  }
}
