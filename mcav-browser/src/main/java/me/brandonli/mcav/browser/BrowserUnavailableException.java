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
 * Thrown by the start of a browser when the browser cannot run on this machine: there is no CEF build for its operating
 * system and processor, the download or the verification of the CEF build or of the libraries it needs failed, or the
 * server lacks a library that the browser needs and mcav does not bring. The message tells which; see also
 * {@link BrowserPlayer#isSupported()}.
 *
 * @since 1.0.0
 */
public final class BrowserUnavailableException extends BrowserException {

  @Serial
  private static final long serialVersionUID = 5362540682227068077L;

  /**
   * Creates an exception with a message.
   *
   * @param message why the browser cannot run
   * @since 1.0.0
   */
  public BrowserUnavailableException(final String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and the exception that caused it.
   *
   * @param message why the browser cannot run
   * @param cause   the underlying failure
   * @since 1.0.0
   */
  public BrowserUnavailableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
