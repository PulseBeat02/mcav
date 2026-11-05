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
package me.brandonli.mcav;

import java.io.Serial;

/**
 * Thrown when the library cannot be installed, or when it is used before it was installed. The failure is
 * recoverable: a failed installation can be retried, so this is an unchecked exception rather than an error.
 *
 * @since 1.0.0
 */
public final class MCAVLoadingException extends MCAVException {

  @Serial
  private static final long serialVersionUID = 5512824472714665790L;

  MCAVLoadingException(final String message) {
    super(message);
  }

  MCAVLoadingException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
