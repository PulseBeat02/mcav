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
package me.brandonli.mcav.installer;

import java.io.Serial;

/**
 * Thrown when jars cannot be added to a class loader, usually because the class loader is not a
 * {@link java.net.URLClassLoader} or the runtime forbids the access the injection needs.
 *
 * @since 1.0.0
 */
public final class JarInjectorException extends UnsupportedOperationException {

  @Serial
  private static final long serialVersionUID = 861375458420366052L;

  /**
   * Creates an exception with a message.
   *
   * @param message the detail message, which says why the jars cannot be added
   * @since 1.0.0
   */
  public JarInjectorException(final String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and the exception that caused it.
   *
   * @param message the detail message, which says why the jars cannot be added
   * @param cause   the failure that caused this exception, such as a denied reflective access
   * @since 1.0.0
   */
  public JarInjectorException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
