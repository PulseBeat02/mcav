/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import me.brandonli.mcav.capability.installer.vlc.discovery.LinuxNativeDiscoveryStrategy;
import me.brandonli.mcav.capability.installer.vlc.discovery.OsxNativeDiscoveryStrategy;
import me.brandonli.mcav.capability.installer.vlc.discovery.WindowsNativeDiscoveryStrategy;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;

/**
 * VLC Installation manager.
 */
public final class VLCInstallationKit {

  private static final Path CONFIG_DIRECTORY = Path.of(System.getProperty("user.home"), ".config", "vlcj");
  private static final Path CONFIG_FILE = CONFIG_DIRECTORY.resolve("vlcj.config");

  VLCInstallationKit() {
    // hidden
  }

  /**
   * Attempts to search for the VLC binary in common installation paths. If a library has been found
   * and loaded successfully, it will be available to be used by VLCJ. Otherwise, if a library could
   * not be found, it will download the respective binary for the user operating system and load
   * that libvlc that way.
   *
   * @return Optional containing path if found, otherwise empty
   * @throws IOException if an issue occurred during installation
   */
  public Optional<Path> start() throws IOException {
    return this.installBinary(true);
  }

  private Optional<Path> installBinary(final boolean chmod) throws IOException {
    final VLCInstaller installer = VLCInstaller.create();
    final Path download = installer.download(chmod);
    Files.createDirectories(CONFIG_DIRECTORY);

    final Path absolute = download.toAbsolutePath();
    final String value = absolute.toString();

    final Properties properties = new Properties();
    properties.setProperty("nativeDirectory", value);

    try (final OutputStream stream = Files.newOutputStream(CONFIG_FILE)) {
      properties.store(stream, "VLC native directory configuration");
    }

    // needed for ServiceLoader issues
    final LinuxNativeDiscoveryStrategy linuxNativeDiscoveryStrategy = new LinuxNativeDiscoveryStrategy();
    final OsxNativeDiscoveryStrategy osxNativeDiscoveryStrategy = new OsxNativeDiscoveryStrategy();
    final WindowsNativeDiscoveryStrategy windowsNativeDiscoveryStrategy = new WindowsNativeDiscoveryStrategy();
    final NativeDiscovery discovery = new NativeDiscovery(
      linuxNativeDiscoveryStrategy,
      osxNativeDiscoveryStrategy,
      windowsNativeDiscoveryStrategy
    );
    final NativeDiscovery defaultDiscovery = new NativeDiscovery();
    if (!discovery.discover() && !defaultDiscovery.discover()) {
      throw new UnsupportedOperatingSystemException("Failed to discover VLC native libraries.");
    }

    return Optional.of(absolute);
  }

  /**
   * Constructs a new VLCInstallationKit with default parameters for downloading/loading libvlc into
   * the runtime.
   *
   * @return a new VLCInstallationKit
   */
  public static VLCInstallationKit create() {
    return new VLCInstallationKit();
  }
}
