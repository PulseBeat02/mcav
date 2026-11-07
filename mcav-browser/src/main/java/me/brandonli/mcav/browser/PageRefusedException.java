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

/**
 * Thrown by the start of a browser when the network guard refused the page's own address, because the address is not
 * public and the options do not allow private networks (see {@link BrowserOptionsBuilder#privateNetworks(boolean)}).
 * Allowing private networks, or opening a public page, is the remedy; the message names the page.
 *
 * <p>Chromium reports a refused connection like an unreachable host, so the start tells them apart by the guard's
 * notice about the page's host. A page that the guard refuses later, such as after a redirect to a private address,
 * fails with a plain {@link BrowserException}.
 *
 * @since 1.0.0
 */
public final class PageRefusedException extends BrowserException {

  @Serial
  private static final long serialVersionUID = -5912049936306866841L;

  /**
   * Creates an exception with a message.
   *
   * @param message which page was refused
   * @since 1.0.0
   */
  public PageRefusedException(final String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and the exception that caused it.
   *
   * @param message which page was refused
   * @param cause   the failed load of the page
   * @since 1.0.0
   */
  public PageRefusedException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
