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
package me.brandonli.mcav.utils.http;

import java.io.IOException;
import java.io.Serial;

/**
 * Thrown when a pinned download is redirected to another host than its own, or too often. Another attempt would end
 * the same way, so it is not retried.
 */
final class RedirectRefusedException extends IOException {

  @Serial
  private static final long serialVersionUID = 7_310_484_118_903_542_617L;

  /**
   * Constructs the exception.
   *
   * @param message the detail message, which names the redirect
   */
  RedirectRefusedException(final String message) {
    super(message);
  }
}
