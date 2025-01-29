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
package me.brandonli.mcav.media.player;

import java.io.Serial;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An exception that is thrown when there is an error related to the player.
 */
public class PlayerException extends AssertionError {

  @Serial
  private static final long serialVersionUID = 5422221049222805060L;

  /**
   * Constructs a new PlayerException with the specified detail message.
   *
   * @param message the detail message
   */
  public PlayerException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs a new PlayerException with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public PlayerException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
