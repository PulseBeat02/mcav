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
package me.brandonli.mcav.browser;

final class MouseInput {

  private final int action;
  private final int pageX;
  private final int pageY;
  private final int button;
  private final int clickCount;
  private final int deltaX;
  private final int deltaY;

  // not checked; HelperProtocol checks what it reads
  MouseInput(
    final int action,
    final int pageX,
    final int pageY,
    final int button,
    final int clickCount,
    final int deltaX,
    final int deltaY
  ) {
    this.action = action;
    this.pageX = pageX;
    this.pageY = pageY;
    this.button = button;
    this.clickCount = clickCount;
    this.deltaX = deltaX;
    this.deltaY = deltaY;
  }

  int getAction() {
    return this.action;
  }

  int getX() {
    return this.pageX;
  }

  int getY() {
    return this.pageY;
  }

  int getButton() {
    return this.button;
  }

  int getClickCount() {
    return this.clickCount;
  }

  int getDeltaX() {
    return this.deltaX;
  }

  int getDeltaY() {
    return this.deltaY;
  }
}
