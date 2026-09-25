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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The environment variables a browser helper gets. The helper runs untrusted web content without the Chromium
 * sandbox, so it inherits only the variables a program needs to find the system, its user and its language, never the
 * rest of the server's environment, which may hold credentials such as bot tokens or cloud keys.
 */
final class HelperEnvironment {

  private static final Set<String> KEPT = Set.of(
    "PATH",
    "HOME",
    "USER",
    "LOGNAME",
    "LANG",
    "LANGUAGE",
    "LC_ALL",
    "LC_CTYPE",
    "TZ",
    "TMPDIR",
    "TMP",
    "TEMP",
    "XDG_RUNTIME_DIR",
    "SYSTEMROOT",
    "SYSTEMDRIVE",
    "WINDIR",
    "USERPROFILE",
    "USERNAME",
    "LOCALAPPDATA",
    "APPDATA",
    "PROGRAMDATA",
    "PROGRAMFILES",
    "PROGRAMFILES(X86)",
    "COMMONPROGRAMFILES",
    "COMSPEC",
    "PATHEXT",
    "PROCESSOR_ARCHITECTURE",
    "NUMBER_OF_PROCESSORS"
  );

  private HelperEnvironment() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Picks the variables a helper keeps from the environment of the server. Names are compared without case, as Windows
   * does.
   *
   * @param parent the environment of the server
   * @return the kept variables
   */
  static Map<String, String> filter(final Map<String, String> parent) {
    final Map<String, String> kept = new HashMap<>();
    for (final Map.Entry<String, String> variable : parent.entrySet()) {
      final String name = variable.getKey();
      final String upperName = name.toUpperCase(Locale.ROOT);
      if (KEPT.contains(upperName)) {
        final String value = variable.getValue();
        kept.put(name, value);
      }
    }
    return kept;
  }
}
