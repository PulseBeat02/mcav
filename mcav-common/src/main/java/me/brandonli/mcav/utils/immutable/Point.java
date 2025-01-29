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

/**
 * Represents a two-dimensional point with immutable coordinates.
 */
public final class Point {

  private final double x;
  private final double y;

  Point(final double x, final double y) {
    this.x = x;
    this.y = y;
  }

  /**
   * Creates a new {@code Point} instance with the specified x and y coordinates.
   *
   * @param x the x-coordinate of the point
   * @param y the y-coordinate of the point
   * @return a new {@code Point} instance with the given coordinates
   */
  public static Point point(final double x, final double y) {
    return new Point(x, y);
  }

  /**
   * Retrieves the x-coordinate of this point.
   *
   * @return the x-coordinate of this point
   */
  public double getX() {
    return this.x;
  }

  /**
   * Retrieves the y-coordinate of this point.
   *
   * @return the y-coordinate of this point
   */
  public double getY() {
    return this.y;
  }
}
