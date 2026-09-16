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
package me.brandonli.mcav.capability.installer.vlc.discovery;

/*
 * This file is part of VLCJ.
 *
 * VLCJ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VLCJ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VLCJ.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2009-2025 Caprica Software Limited.
 */

import java.util.List;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;

/**
 * Finds {@code libvlc.so} and {@code libvlccore.so} in the well-known directories of Linux and other Unix
 * systems. Versioned library names such as {@code libvlc.so.5} and {@code libvlccore.so.9.0.1} are recognized.
 */
public class LinuxNativeDiscoveryStrategy extends DirectoryProviderDiscoveryStrategy {

  private static final String[] FILENAME_PATTERNS = new String[] { "libvlc\\.so(?:\\.\\d+)*", "libvlccore\\.so(?:\\.\\d+)*" };
  private static final String[] PLUGIN_PATH_FORMATS = new String[] { "%s/plugins", "%s/vlc/plugins" };

  /**
   * Constructs a strategy that searches vlcj's directories and publishes the plugin path with {@code setenv}.
   */
  public LinuxNativeDiscoveryStrategy() {
    final EnvironmentSetter posixSetter = nativeSetter();
    super(FILENAME_PATTERNS, PLUGIN_PATH_FORMATS, posixSetter);
  }

  /**
   * Creates the setter that publishes variables with {@code setenv} of the C library of this process.
   *
   * @return the setter
   */
  static EnvironmentSetter nativeSetter() {
    final EnvironmentVariables variables = EnvironmentVariables.nativeVariables();
    return variables::setPosixVariable;
  }

  /**
   * Constructs a strategy with custom directories and a custom way of publishing the plugin path.
   *
   * @param searchProviders   the providers of the directories to search
   * @param environmentSetter sets the plugin path variable
   */
  LinuxNativeDiscoveryStrategy(final List<SearchProvider> searchProviders, final EnvironmentSetter environmentSetter) {
    super(FILENAME_PATTERNS, PLUGIN_PATH_FORMATS, searchProviders, environmentSetter);
  }

  /**
   * Checks whether the system is Linux or another Unix system other than macOS.
   *
   * @return true on Linux and other Unix systems
   */
  @Override
  public boolean supported() {
    return RuntimeUtil.isNix();
  }
}
