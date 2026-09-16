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

import com.google.common.base.Preconditions;
import com.sun.jna.NativeLibrary;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;

/**
 * Finds {@code libvlc.dylib} and {@code libvlccore.dylib} in the well-known directories of macOS, including
 * {@code /Applications/VLC.app}.
 *
 * <p>On recent macOS versions {@code libvlc} fails to load unless {@code libvlccore} was loaded first, so the core
 * library is loaded from the found directory before vlcj loads {@code libvlc}.
 */
public class OsxNativeDiscoveryStrategy extends DirectoryProviderDiscoveryStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(OsxNativeDiscoveryStrategy.class);
  private static final String[] FILENAME_PATTERNS = new String[] { "libvlc\\.dylib", "libvlccore\\.dylib" };
  private static final String[] PLUGIN_PATH_FORMATS = new String[] { "%s/../plugins" };

  private final Consumer<String> corePreloader;

  /**
   * Constructs a strategy that searches vlcj's directories, publishes the plugin path with {@code setenv}, and
   * preloads the core library through JNA.
   */
  public OsxNativeDiscoveryStrategy() {
    final EnvironmentSetter posixSetter = LinuxNativeDiscoveryStrategy.nativeSetter();
    super(FILENAME_PATTERNS, PLUGIN_PATH_FORMATS, posixSetter);
    this.corePreloader = OsxNativeDiscoveryStrategy::preloadCoreLibrary;
  }

  /**
   * Constructs a strategy with custom directories, a custom way of publishing the plugin path, and a custom way of
   * preloading the core library.
   *
   * @param searchProviders   the providers of the directories to search
   * @param environmentSetter sets the plugin path variable
   * @param corePreloader     loads the core library from the found directory
   */
  OsxNativeDiscoveryStrategy(
    final List<SearchProvider> searchProviders,
    final EnvironmentSetter environmentSetter,
    final Consumer<String> corePreloader
  ) {
    super(FILENAME_PATTERNS, PLUGIN_PATH_FORMATS, searchProviders, environmentSetter);
    Preconditions.checkNotNull(corePreloader, "Core preloader must not be null");
    this.corePreloader = corePreloader;
  }

  /**
   * Checks whether the system is macOS.
   *
   * @return true on macOS
   */
  @Override
  public boolean supported() {
    return RuntimeUtil.isMac();
  }

  /**
   * Loads the core library from the found directory, so {@code libvlc} can link against it. vlcj does not catch
   * errors thrown here, so a core library that cannot be linked, such as an Intel build of VLC on Apple silicon, is
   * reported by returning {@code false} instead.
   *
   * @param path the directory that contains the libraries
   * @return {@code true} to let vlcj add the directory to the JNA search path, {@code false} if the core library
   * cannot be loaded from it
   */
  @Override
  public boolean onFound(final String path) {
    Preconditions.checkNotNull(path, "Path must not be null");
    try {
      this.corePreloader.accept(path);
      return true;
    } catch (final UnsatisfiedLinkError error) {
      final String reason = error.getMessage();
      LOGGER.warn("The VLC core library in {} cannot be loaded, for example because it was built for another processor: {}", path, reason);
      return false;
    }
  }

  /**
   * Adds a directory to the JNA search path of the core library and loads the library from it.
   *
   * @param path the directory that contains the core library
   */
  static void preloadCoreLibrary(final String path) {
    final String coreLibraryName = RuntimeUtil.getLibVlcCoreLibraryName();
    NativeLibrary.addSearchPath(coreLibraryName, path);
    NativeLibrary.getInstance(coreLibraryName);
  }
}
