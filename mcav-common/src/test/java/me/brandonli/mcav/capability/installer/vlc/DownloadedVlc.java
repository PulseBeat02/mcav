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
import java.util.Optional;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.Installer;
import me.brandonli.mcav.capability.installer.debian.DebianLibraryInstaller;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Assumptions;
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy;

// The VLC of the installation kit, never this machine's, so every host behaves alike: the pinned Debian packages on
// 64-bit x86 and ARM Linux, the VlcInstaller download elsewhere, installed once per machine.
public final class DownloadedVlc {

  private DownloadedVlc() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  // installs VLC first when the cache has none
  public static Optional<Path> load() {
    final Installer installer = createInstaller();
    final VlcDiscovery discovery = createLoadingDiscovery(installer);
    final VlcLoadState loadState = new VlcLoadState();
    final VlcInstallationKit kit = new VlcInstallationKit(installer, discovery, loadState);
    return kit.start();
  }

  // skips the test where VLC has no installation
  static Path install() {
    final Installer installer = createInstaller();
    final boolean supported = installer.isSupported();
    Assumptions.assumeTrue(supported, "VLC has no installation for this platform!");

    final Optional<Path> existing = installer.findInstallation();
    final Path directory = existing.orElseGet(() -> installer.download(FileMode.EXECUTABLE));
    if (installer instanceof final DebianLibraryInstaller debian) {
      debian.loadLibraries();
    }

    return directory;
  }

  private static Installer createInstaller() {
    final Platform current = Platform.getCurrentPlatform();
    final VlcInstallationKit kit = VlcInstallationKit.createForPlatform(current);
    return kit.getInstaller();
  }

  // libvlc links the Debian libraries, so they load first
  private static VlcDiscovery createLoadingDiscovery(final Installer installer) {
    final NativeVlcDiscovery discovery = createDiscovery();
    if (installer instanceof final DebianLibraryInstaller debian) {
      return new PreloadingVlcDiscovery(debian::loadLibraries, discovery);
    }

    return discovery;
  }

  // finds no VLC on this machine, so libvlc loads only from an installation directory
  static NativeVlcDiscovery createDiscovery() {
    return new NativeVlcDiscovery(DownloadedVlc::createNoStrategies);
  }

  // vlcj falls back to its default strategies when it is given none, so a strategy that never applies stands in.
  private static NativeDiscoveryStrategy[] createNoStrategies() {
    final NativeDiscoveryStrategy unsupported = new UnsupportedStrategy();
    return new NativeDiscoveryStrategy[] { unsupported };
  }

  // vlcj asks a strategy for nothing else once it reports itself unsupported.
  private static final class UnsupportedStrategy implements NativeDiscoveryStrategy {

    private static final String UNREACHABLE_MESSAGE = "vlcj only asks a supported strategy!";

    @Override
    public boolean supported() {
      return false;
    }

    @Override
    public String discover() {
      throw new UnsupportedOperationException(UNREACHABLE_MESSAGE);
    }

    @Override
    public boolean onFound(final String path) {
      throw new UnsupportedOperationException(UNREACHABLE_MESSAGE);
    }

    @Override
    public boolean onSetPluginPath(final String path) {
      throw new UnsupportedOperationException(UNREACHABLE_MESSAGE);
    }
  }
}
