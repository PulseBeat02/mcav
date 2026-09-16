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
package me.brandonli.mcav.http;

import java.io.Serial;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thrown when the HTTP server cannot be started, for example because the port is already in use.
 *
 * <p>This is a plain {@link RuntimeException}: starting the server depends on the machine, such as another program
 * that already listens on the port, not on the state of the server object or on a programming error, so neither
 * {@link IllegalStateException} nor an {@link Error} fits. It is unchecked, so a caller that cannot recover does not
 * have to declare it, and a caller that can recover catches it and tries another port.
 */
public class HttpException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = -4536819561346624904L;

  /**
   * Constructs a new exception with a detail message and no cause.
   *
   * @param message the detail message, which says why the server did not start
   */
  HttpException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs a new exception with a detail message and the failure that caused it.
   *
   * @param message the detail message, which says why the server did not start
   * @param cause   the failure of the web framework that caused this exception, or null if it is unknown
   */
  HttpException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
