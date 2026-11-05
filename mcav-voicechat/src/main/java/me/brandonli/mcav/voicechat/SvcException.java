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
package me.brandonli.mcav.voicechat;

import java.io.Serial;
import me.brandonli.mcav.MCAVException;

/**
 * Thrown when Simple Voice Chat fails for a reason outside the caller's control, such as an entity it refuses an
 * audio channel.
 *
 * <p>Every failure of {@code mcav-voicechat} that comes from Simple Voice Chat itself is this type; catch it to
 * handle them all. Errors and runtime exceptions that Simple Voice Chat throws are passed on unchanged.
 *
 * @since 1.0.0
 */
public final class SvcException extends MCAVException {

  @Serial
  private static final long serialVersionUID = 7827813584530439056L;

  /**
   * Creates an exception with a message.
   *
   * @param message the detail message
   * @since 1.0.0
   */
  public SvcException(final String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and the exception that caused it.
   *
   * @param message the detail message
   * @param cause   the cause
   * @since 1.0.0
   */
  public SvcException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
