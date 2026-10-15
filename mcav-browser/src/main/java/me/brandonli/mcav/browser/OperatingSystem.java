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

import java.util.Locale;

// The family of the system the helper runs on, which decides the switches of Chromium and how it is confined; the
// helper cannot use the Os of MCAV's common module, which is not on its class path.
enum OperatingSystem {
  LINUX,
  MAC,
  OTHER;

  private static final String LINUX_NAME = "linux";
  private static final String MAC_NAME = "mac";

  static OperatingSystem fromName(final String osName) {
    final String name = osName.toLowerCase(Locale.ROOT);
    if (name.contains(LINUX_NAME)) {
      return LINUX;
    }

    if (name.contains(MAC_NAME)) {
      return MAC;
    }

    return OTHER;
  }
}
