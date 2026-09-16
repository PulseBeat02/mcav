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

/**
 * Creates the failures of the VLC installer for tests in other packages, whose constructors are package-private so
 * that only the installer throws them.
 */
public final class VlcInstallerFailures {

  private VlcInstallerFailures() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Creates the failure the installer throws when VLC cannot be provided on the system.
   *
   * @param message the detail message
   * @return the failure
   */
  public static UnsupportedOperatingSystemException unsupportedSystem(final String message) {
    return new UnsupportedOperatingSystemException(message);
  }
}
