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

import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;

/**
 * Finds {@code libvlc.dll} and {@code libvlccore.dll} in the installation directory of VLC on Windows and the other
 * directories vlcj knows about.
 *
 * <p>Instances hold no mutable state, but discovery sets environment variables of the process, so run one discovery at
 * a time.
 *
 * @since 1.0.0
 */
public class WindowsNativeDiscoveryStrategy extends DirectoryProviderDiscoveryStrategy {

  private static final String[] FILENAME_PATTERNS = new String[] { "libvlc\\.dll", "libvlccore\\.dll" };
  private static final List<Path> PLUGIN_DIRECTORIES = List.of(Path.of("plugins"), Path.of("vlc", "plugins"));

  private final Os operatingSystem;

  /**
   * Creates a strategy that searches vlcj's directories and publishes the plugin path with {@code _putenv}.
   *
   * @since 1.0.0
   */
  public WindowsNativeDiscoveryStrategy() {
    final EnvironmentSetter windowsSetter = createNativeSetter();
    super(FILENAME_PATTERNS, PLUGIN_DIRECTORIES, windowsSetter);
    this.operatingSystem = OsUtils.getOs();
  }

  WindowsNativeDiscoveryStrategy(
    final List<SearchProvider> searchProviders,
    final EnvironmentSetter environmentSetter,
    final Os operatingSystem
  ) {
    super(FILENAME_PATTERNS, PLUGIN_DIRECTORIES, searchProviders, environmentSetter);
    this.operatingSystem = operatingSystem;
  }

  static EnvironmentSetter createNativeSetter() {
    final EnvironmentVariables variables = EnvironmentVariables.create();
    return variables::setWindowsVariable;
  }

  /**
   * Returns whether the running operating system is Windows.
   *
   * @return true on Windows
   * @since 1.0.0
   */
  @Override
  public boolean supported() {
    return this.operatingSystem == Os.WINDOWS;
  }
}
