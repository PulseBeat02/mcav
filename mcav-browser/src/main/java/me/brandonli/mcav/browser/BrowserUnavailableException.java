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

import me.brandonli.mcav.media.player.PlayerException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thrown by the start of a browser when the browser cannot run on this machine: there is no CEF build for its operating
 * system and processor, the download or the verification of the CEF build or of the libraries it needs failed, or the
 * server lacks a library that the browser needs and mcav does not bring. The message tells which; see also
 * {@link BrowserPlayer#isSupported()}.
 */
public final class BrowserUnavailableException extends PlayerException {

  private static final long serialVersionUID = 1L;

  /**
   * Constructs the exception.
   *
   * @param message why the browser cannot run
   */
  public BrowserUnavailableException(final String message) {
    super(message);
  }

  /**
   * Constructs the exception.
   *
   * @param message why the browser cannot run
   * @param cause   the failure behind it
   */
  public BrowserUnavailableException(final String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
