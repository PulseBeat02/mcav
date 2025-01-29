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
package me.brandonli.mcav.utils.interaction;

/**
 * Enum representing different types of mouse click events.
 */
public enum MouseClick {
  /**
   * Represents a left mouse click event.
   */
  LEFT(0),
  /**
   * Represents a right mouse click event.
   */
  RIGHT(1),
  /**
   * Represents a double mouse click event.
   */
  DOUBLE(2),
  /**
   * Represents a mouse click event where the mouse button is held down.
   */
  HOLD(3),
  /**
   * Represents a mouse click event where the mouse button is released.
   */
  RELEASE(4);

  private final int id;

  MouseClick(final int id) {
    this.id = id;
  }

  /**
   * Retrieves the unique identifier associated with this instance.
   *
   * @return the unique identifier as an integer.
   */
  public int getId() {
    return this.id;
  }
}
