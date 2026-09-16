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
 * Finds {@code libvlc.dll} and {@code libvlccore.dll} in the installation directory of VLC on Windows and the other
 * directories vlcj knows about.
 */
public class WindowsNativeDiscoveryStrategy extends DirectoryProviderDiscoveryStrategy {

  private static final String[] FILENAME_PATTERNS = new String[] { "libvlc\\.dll", "libvlccore\\.dll" };
  private static final String[] PLUGIN_PATH_FORMATS = new String[] { "%s\\plugins", "%s\\vlc\\plugins" };

  /**
   * Constructs a strategy that searches vlcj's directories and publishes the plugin path with {@code _putenv}.
   */
  public WindowsNativeDiscoveryStrategy() {
    final EnvironmentSetter windowsSetter = nativeSetter();
    super(FILENAME_PATTERNS, PLUGIN_PATH_FORMATS, windowsSetter);
  }

  /**
   * Creates the setter that publishes variables with {@code _putenv} of the C runtime of this process.
   *
   * @return the setter
   */
  static EnvironmentSetter nativeSetter() {
    final EnvironmentVariables variables = EnvironmentVariables.nativeVariables();
    return variables::setWindowsVariable;
  }

  /**
   * Constructs a strategy with custom directories and a custom way of publishing the plugin path.
   *
   * @param searchProviders   the providers of the directories to search
   * @param environmentSetter sets the plugin path variable
   */
  WindowsNativeDiscoveryStrategy(final List<SearchProvider> searchProviders, final EnvironmentSetter environmentSetter) {
    super(FILENAME_PATTERNS, PLUGIN_PATH_FORMATS, searchProviders, environmentSetter);
  }

  /**
   * Checks whether the system is Windows.
   *
   * @return true on Windows
   */
  @Override
  public boolean supported() {
    return RuntimeUtil.isWindows();
  }
}
