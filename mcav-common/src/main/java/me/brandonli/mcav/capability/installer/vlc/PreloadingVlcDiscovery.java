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

import com.google.common.annotations.VisibleForTesting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Loads the dependencies of a private VLC installation before vlcj loads libvlc, for installations whose
// dependencies are not on the system, such as the Debian packages of 64-bit Linux. System discovery is left alone,
// because a system VLC finds its dependencies itself.
final class PreloadingVlcDiscovery implements VlcDiscovery {

  private static final Logger LOGGER = LoggerFactory.getLogger(PreloadingVlcDiscovery.class);

  // VLC unloads its plugins with its last instance, but the taglib plugin leaves resolvers in the preloaded libtag,
  // which calls them on its next read; keeping the plugin loaded keeps them valid.
  private static final Path TAGLIB_PLUGIN = Path.of("vlc", "plugins", "meta_engine", "libtaglib_plugin.so");

  private final Runnable preloader;
  private final Consumer<String> pluginLoader;
  private final VlcDiscovery delegate;

  @SuppressWarnings("restricted") // System.load loads native code, which the JVM restricts
  PreloadingVlcDiscovery(final Runnable preloader, final VlcDiscovery delegate) {
    this(preloader, System::load, delegate);
  }

  // Takes the plugin loader, so a test can see the plugin load, which kills the mutant that skips it.
  @VisibleForTesting
  PreloadingVlcDiscovery(final Runnable preloader, final Consumer<String> pluginLoader, final VlcDiscovery delegate) {
    this.preloader = preloader;
    this.pluginLoader = pluginLoader;
    this.delegate = delegate;
  }

  @Override
  public boolean discoverSystemInstallation() {
    return this.delegate.discoverSystemInstallation();
  }

  @Override
  public @Nullable Path getDiscoveredPath() {
    return this.delegate.getDiscoveredPath();
  }

  @Override
  public boolean loadInstalledLibraries(final Path libraryDirectory) {
    this.preloader.run();
    this.loadTaglibPlugin(libraryDirectory);
    return this.delegate.loadInstalledLibraries(libraryDirectory);
  }

  private void loadTaglibPlugin(final Path libraryDirectory) {
    final Path plugin = libraryDirectory.resolve(TAGLIB_PLUGIN);
    final boolean present = Files.isRegularFile(plugin);
    if (!present) {
      return;
    }

    try {
      final String path = plugin.toString();
      this.pluginLoader.accept(path);
    } catch (final UnsatisfiedLinkError exception) {
      LOGGER.debug("The taglib plugin {} could not be loaded, so VLC skips it too.", plugin, exception);
    }
  }
}
