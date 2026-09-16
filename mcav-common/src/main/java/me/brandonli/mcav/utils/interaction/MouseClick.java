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
 * The kinds of mouse events that interactive players such as browsers, VNC sessions, and virtual machines accept.
 */
public enum MouseClick {
  /**
   * A click with the left button.
   */
  LEFT(0),
  /**
   * A click with the right button.
   */
  RIGHT(1),
  /**
   * A double click with the left button.
   */
  DOUBLE(2),
  /**
   * Pressing and holding the left button.
   */
  HOLD(3),
  /**
   * Releasing the left button after {@link #HOLD}.
   */
  RELEASE(4);

  private final int id;

  MouseClick(final int id) {
    this.id = id;
  }

  /**
   * Gets a small stable number for the event, useful for tables and protocols.
   *
   * @return the identifier
   */
  public int getId() {
    return this.id;
  }
}
