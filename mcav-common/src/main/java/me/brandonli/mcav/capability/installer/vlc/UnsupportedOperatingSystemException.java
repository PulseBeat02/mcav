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
package me.brandonli.mcav.capability.installer.vlc;

import java.io.Serial;

/**
 * Thrown when VLC can neither be found on the system nor be installed for the current operating system.
 *
 * <p>This is an {@link UnsupportedOperationException}: the requested operation, providing VLC, is not supported on
 * this system, and retrying on the same system fails again. Callers catch it to disable VLC playback and fall back to
 * another player.
 */
public class UnsupportedOperatingSystemException extends UnsupportedOperationException {

  @Serial
  private static final long serialVersionUID = 885934601050385987L;

  /**
   * Constructs a new exception with a detail message and no cause.
   *
   * @param message the detail message, which names the system or explains why VLC cannot be provided on it
   */
  UnsupportedOperatingSystemException(final String message) {
    super(message);
  }
}
