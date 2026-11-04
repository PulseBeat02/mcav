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
 * Finds {@code libvlc.so} and {@code libvlccore.so} in the well-known directories of Linux and other Unix
 * systems. Versioned library names such as {@code libvlc.so.5} and {@code libvlccore.so.9.0.1} are recognized.
 *
 * <p>Instances hold no mutable state, but discovery sets environment variables of the process, so run one discovery at
 * a time.
 *
 * @since 1.0.0
 */
public final class LinuxNativeDiscoveryStrategy extends DirectoryProviderDiscoveryStrategy {

  private static final String[] FILENAME_PATTERNS = new String[] { "libvlc\\.so(?:\\.\\d+)*", "libvlccore\\.so(?:\\.\\d+)*" };
  private static final List<Path> PLUGIN_DIRECTORIES = List.of(Path.of("plugins"), Path.of("vlc", "plugins"));

  private final Os operatingSystem;

  /**
   * Creates a strategy that searches vlcj's directories and publishes the plugin path with {@code setenv}.
   *
   * @since 1.0.0
   */
  public LinuxNativeDiscoveryStrategy() {
    final EnvironmentSetter posixSetter = createNativeSetter();
    super(FILENAME_PATTERNS, PLUGIN_DIRECTORIES, posixSetter);
    this.operatingSystem = OsUtils.getOs();
  }

  LinuxNativeDiscoveryStrategy(
    final List<SearchProvider> searchProviders,
    final EnvironmentSetter environmentSetter,
    final Os operatingSystem
  ) {
    super(FILENAME_PATTERNS, PLUGIN_DIRECTORIES, searchProviders, environmentSetter);
    this.operatingSystem = operatingSystem;
  }

  static EnvironmentSetter createNativeSetter() {
    final EnvironmentVariables variables = EnvironmentVariables.create();
    return variables::setPosixVariable;
  }

  /**
   * Returns whether the running operating system is Linux or another Unix system other than macOS.
   *
   * @return true on Linux and other Unix systems
   * @since 1.0.0
   */
  @Override
  public boolean supported() {
    return this.operatingSystem != Os.WINDOWS && this.operatingSystem != Os.MAC;
  }
}
