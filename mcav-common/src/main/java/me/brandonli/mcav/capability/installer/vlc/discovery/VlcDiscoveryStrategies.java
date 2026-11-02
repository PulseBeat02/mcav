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

import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.capability.installer.vlc.discovery.DirectoryProviderDiscoveryStrategy.EnvironmentSetter;
import me.brandonli.mcav.capability.installer.vlc.discovery.DirectoryProviderDiscoveryStrategy.SearchProvider;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy;

/**
 * Creates the discovery strategies of the library for vlcj's {@link NativeDiscovery}: one strategy per operating
 * system, of which vlcj uses the one that supports the running system.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class VlcDiscoveryStrategies {

  private static final int DIRECT_DEPTH = 0;

  private VlcDiscoveryStrategies() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  /**
   * Creates strategies that look for a VLC installation in the well-known directories of every operating system,
   * without ever walking a large directory tree.
   *
   * @return the strategies for Linux, macOS and Windows
   * @since 1.0.0
   */
  public static NativeDiscoveryStrategy[] createSystemStrategies() {
    final LinuxNativeDiscoveryStrategy linux = new LinuxNativeDiscoveryStrategy();
    final OsxNativeDiscoveryStrategy osx = new OsxNativeDiscoveryStrategy();
    final WindowsNativeDiscoveryStrategy windows = new WindowsNativeDiscoveryStrategy();
    return new NativeDiscoveryStrategy[] { linux, osx, windows };
  }

  /**
   * Creates strategies that only look into one directory, for loading a VLC installation whose location is known.
   *
   * @param libraryDirectory the directory that contains the VLC libraries
   * @return the strategies for Linux, macOS and Windows
   * @throws NullPointerException if {@code libraryDirectory} is null
   * @since 1.0.0
   */
  public static NativeDiscoveryStrategy[] createDirectoryStrategies(final Path libraryDirectory) {
    Preconditions.checkNotNull(libraryDirectory, "Library directory must not be null!");

    final FixedDirectoryProvider directoryProvider = new FixedDirectoryProvider(libraryDirectory);
    final SearchProvider searchProvider = new SearchProvider(directoryProvider, DIRECT_DEPTH);
    final List<SearchProvider> searchProviders = List.of(searchProvider);
    final EnvironmentSetter posixSetter = LinuxNativeDiscoveryStrategy.nativeSetter();
    final EnvironmentSetter windowsSetter = WindowsNativeDiscoveryStrategy.nativeSetter();
    final Os operatingSystem = OsUtils.getOs();
    final LinuxNativeDiscoveryStrategy linux = new LinuxNativeDiscoveryStrategy(searchProviders, posixSetter, operatingSystem);
    final OsxNativeDiscoveryStrategy osx = new OsxNativeDiscoveryStrategy(
      searchProviders,
      posixSetter,
      OsxNativeDiscoveryStrategy::preloadCoreLibrary,
      operatingSystem
    );
    final WindowsNativeDiscoveryStrategy windows = new WindowsNativeDiscoveryStrategy(searchProviders, windowsSetter, operatingSystem);
    return new NativeDiscoveryStrategy[] { linux, osx, windows };
  }
}
