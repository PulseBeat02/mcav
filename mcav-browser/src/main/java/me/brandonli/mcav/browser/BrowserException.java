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

import java.io.Serial;
import me.brandonli.mcav.MCAVException;

/**
 * Thrown when a browser operation fails for a reason outside the caller's control, such as a helper process that
 * cannot be started, fails or exits, a page that cannot be loaded, or a browser module that is stopped.
 *
 * <p>Every failure of {@code mcav-browser} that comes from a process, the machine or the network is this type or a
 * subtype, such as {@link BrowserUnavailableException}; catch it to handle them all.
 *
 * @since 1.0.0
 */
public class BrowserException extends MCAVException {

  @Serial
  private static final long serialVersionUID = 7751006235485610444L;

  /**
   * Creates an exception with a message.
   *
   * @param message the detail message
   * @since 1.0.0
   */
  public BrowserException(final String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and the exception that caused it.
   *
   * @param message the detail message
   * @param cause   the cause
   * @since 1.0.0
   */
  public BrowserException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
