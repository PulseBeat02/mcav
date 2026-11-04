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

import java.nio.file.Path;
import java.util.function.Supplier;
import me.brandonli.mcav.capability.installer.vlc.discovery.VlcDiscoveryStrategies;
import org.checkerframework.checker.nullness.qual.Nullable;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy;

// vlcj remembers a successful discovery for the rest of the JVM's lifetime, so once the libraries are loaded, every
// later discovery succeeds immediately without reporting a directory
final class NativeVlcDiscovery implements VlcDiscovery {

  private final Supplier<NativeDiscoveryStrategy[]> systemStrategies;

  private @Nullable String discoveredPath;

  NativeVlcDiscovery() {
    this(VlcDiscoveryStrategies::createSystemStrategies);
  }

  // test seam for the boolean return mutants of discoverSystemInstallation, so tests need no VLC on the machine
  NativeVlcDiscovery(final Supplier<NativeDiscoveryStrategy[]> systemStrategies) {
    this.systemStrategies = systemStrategies;
  }

  @Override
  public boolean discoverSystemInstallation() {
    final NativeDiscoveryStrategy[] strategies = this.systemStrategies.get();
    return this.discover(strategies);
  }

  @Override
  public boolean loadInstalledLibraries(final Path libraryDirectory) {
    final NativeDiscoveryStrategy[] strategies = VlcDiscoveryStrategies.createDirectoryStrategies(libraryDirectory);
    return this.discover(strategies);
  }

  private boolean discover(final NativeDiscoveryStrategy[] strategies) {
    final NativeDiscovery discovery = new NativeDiscovery(strategies);
    final boolean found = discovery.discover();
    this.discoveredPath = discovery.discoveredPath();

    return found;
  }

  @Override
  public @Nullable Path getDiscoveredPath() {
    final String raw = this.discoveredPath;
    if (raw == null) {
      return null;
    }

    return Path.of(raw);
  }
}
