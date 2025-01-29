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
package me.brandonli.mcav.utils.examinable;

import java.io.Serial;

/**
 * Exception thrown when an error occurs during the examination of an object.
 */
public class ExaminableException extends AssertionError {

  @Serial
  private static final long serialVersionUID = 2932131150288680009L;

  /**
   * Constructs a new ExaminableException with the specified detail message.
   *
   * @param message the detail message
   */
  public ExaminableException(final String message) {
    super(message);
  }
}
