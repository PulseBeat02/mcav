/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.player.driver;

/**
 * Enum representing different types of mouse click events.
 * These events can be used to simulate mouse interactions with browser interfaces
 * or for handling custom actions triggered by mouse inputs.
 */
public enum MouseClick {
  /**
   * Represents a left mouse click event.
   * This event is typically used to simulate a primary mouse click
   * action, often associated with selecting or interacting with elements
   * on a user interface.
   */
  LEFT(0),
  /**
   * Represents a right mouse click event.
   * <p>
   * This event is commonly used to simulate actions such as opening context menus or triggering
   * custom functionality tied to the right mouse button. Typically used in graphical interfaces
   * or browser-based player interactions.
   * <p>
   * Associated with the integer value {@code 1}.
   */
  RIGHT(1),
  /**
   * Represents a double mouse click event.
   * Typically used to simulate or handle interactions requiring
   * two consecutive clicks within a short duration.
   */
  DOUBLE(2),
  /**
   * Represents a mouse click event where the mouse button is held down.
   * This type of event can be used to simulate or handle scenarios where
   * sustained mouse input is required, such as dragging or pressing and holding
   * for interaction with UI elements.
   */
  HOLD(3),
  /**
   * Represents a mouse click event triggered by releasing the mouse button.
   * This event is commonly used to signal the end of a mouse click or drag action
   * and can be utilized in scenarios requiring precise detection of button release
   * interactions within a graphical interface or application.
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
