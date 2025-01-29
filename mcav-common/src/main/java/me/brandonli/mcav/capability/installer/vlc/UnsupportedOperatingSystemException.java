/*
 * This file is part of mcav, a media playback library for Minecraft
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

/**
 * Exception thrown to indicate that the current operating system is not supported.
 * This exception typically signals that an operation or functionality cannot proceed
 * because the operating system being used is incompatible or unsupported by the application.
 */
public class UnsupportedOperatingSystemException extends AssertionError {

  private static final long serialVersionUID = 885934601050385987L;

  public UnsupportedOperatingSystemException(final String message) {
    super(message);
  }
}
