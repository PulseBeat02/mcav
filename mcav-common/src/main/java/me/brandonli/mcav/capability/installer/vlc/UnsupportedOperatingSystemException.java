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
import me.brandonli.mcav.MCAVException;

/**
 * Thrown when VLC cannot be found or installed, or when an installed private copy cannot be loaded.
 *
 * <p>This is an {@link MCAVException}: providing VLC is not possible on this system in its current state, which the
 * caller cannot change. Callers catch it to disable VLC playback and fall back to another player.
 *
 * @since 1.0.0
 */
public final class UnsupportedOperatingSystemException extends MCAVException {

  @Serial
  private static final long serialVersionUID = 885934601050385987L;

  UnsupportedOperatingSystemException(final String message) {
    super(message);
  }

  UnsupportedOperatingSystemException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
