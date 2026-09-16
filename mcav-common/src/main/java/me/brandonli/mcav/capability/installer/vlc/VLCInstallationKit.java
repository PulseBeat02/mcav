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
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import me.brandonli.mcav.capability.installer.Installer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the VLC native libraries available to vlcj.
 *
 * <p>The kit looks for VLC in this order, and uses the first one it finds:
 * <ol>
 *   <li>a VLC installation on the system, in the well-known installation directories of every operating system;
 *   <li>the private copy an earlier run installed with {@link VLCInstaller}, which needs no network at all;
 *   <li>a new private copy, downloaded with {@link VLCInstaller}.
 * </ol>
 * The libraries of a private copy are loaded straight from its installation directory, and the kit never touches
 * vlcj's own configuration files. Either way, vlcj is ready to create media players once {@link #start()} returns.
 *
 * <pre><code>
 *   final VLCInstallationKit kit = VLCInstallationKit.create();
 *   final Optional&lt;Path&gt; libraryDirectory = kit.start();
 * </code></pre>
 */
public final class VLCInstallationKit {

  private static final Logger LOGGER = LoggerFactory.getLogger(VLCInstallationKit.class);
  private static final VLCLoadState SHARED_LOAD_STATE = new VLCLoadState();

  private final Installer installer;
  private final VLCDiscovery discovery;
  private final VLCLoadState loadState;

  /**
   * Constructs a new kit.
   *
   * @param installer the installer used when the system has no VLC
   * @param discovery finds and loads the VLC libraries
   * @param loadState remembers whether libvlc was loaded
   */
  @VisibleForTesting
  VLCInstallationKit(final Installer installer, final VLCDiscovery discovery, final VLCLoadState loadState) {
    this.installer = installer;
    this.discovery = discovery;
    this.loadState = loadState;
  }

  /**
   * Creates a kit that installs VLC into the cache folder of the library when no system installation exists.
   *
   * @return the kit
   */
  public static VLCInstallationKit create() {
    final VLCInstaller installer = VLCInstaller.create();
    return create(installer);
  }

  /**
   * Creates a kit that uses the specified installer when no system installation exists.
   *
   * @param installer the installer used to download VLC
   * @return the kit
   */
  public static VLCInstallationKit create(final VLCInstaller installer) {
    Preconditions.checkNotNull(installer, "Installer must not be null");
    final NativeVLCDiscovery discovery = new NativeVLCDiscovery();
    return new VLCInstallationKit(installer, discovery, SHARED_LOAD_STATE);
  }

  /**
   * Gets the installer this kit uses when the system has no VLC.
   *
   * @return the installer
   */
  @VisibleForTesting
  Installer getInstaller() {
    return this.installer;
  }

  /**
   * Gets the state that remembers whether libvlc was loaded, which kits created by the factory methods share.
   *
   * @return the load state
   */
  @VisibleForTesting
  VLCLoadState getLoadState() {
    return this.loadState;
  }

  /**
   * Finds or installs VLC and loads its native libraries into vlcj. This method blocks while VLC is downloaded,
   * which only happens once per machine. libvlc can only be loaded once per JVM, so after the first successful call
   * every call returns the same result immediately.
   *
   * @return the directory the native libraries were loaded from, or empty if vlcj had already loaded them without
   * reporting a location
   * @throws IOException                          if VLC has to be downloaded and the download fails
   * @throws UnsupportedOperatingSystemException if VLC cannot be found and cannot be installed on this system
   */
  public Optional<Path> start() throws IOException {
    return this.loadState.loadOnce(this::discoverOrInstall);
  }

  private Optional<Path> discoverOrInstall() throws IOException {
    final boolean found = this.discovery.discoverSystemInstallation();
    if (found) {
      final Path systemPath = this.discovery.getDiscoveredPath();
      logSystemInstallation(systemPath);
      return Optional.ofNullable(systemPath);
    }
    // an earlier private installation is used before anything asks the network whether VLC can be downloaded, so a
    // machine keeps working offline, and GitHub is not asked on every start
    final Optional<Path> existing = this.installer.findInstallation();
    if (existing.isPresent()) {
      final Path existingDirectory = existing.get();
      return this.loadPrivateInstallation(existingDirectory);
    }
    final boolean supported = this.installer.isSupported();
    if (!supported) {
      throw new UnsupportedOperatingSystemException("VLC is not installed and cannot be installed automatically on this system");
    }
    final Path libraryDirectory = this.installer.download(true);
    return this.loadPrivateInstallation(libraryDirectory);
  }

  private Optional<Path> loadPrivateInstallation(final Path libraryDirectory) {
    final boolean loaded = this.discovery.loadInstalledLibraries(libraryDirectory);
    if (!loaded) {
      throw new UnsupportedOperatingSystemException("VLC was installed to " + libraryDirectory + " but its libraries could not be loaded");
    }
    LOGGER.info("Using the VLC installation of the library at {}", libraryDirectory);
    return Optional.of(libraryDirectory);
  }

  private static void logSystemInstallation(final @Nullable Path systemPath) {
    if (systemPath == null) {
      LOGGER.info("Using the VLC libraries vlcj loaded earlier in this JVM");
      return;
    }
    LOGGER.info("Using the VLC installation of the system at {}", systemPath);
  }
}
