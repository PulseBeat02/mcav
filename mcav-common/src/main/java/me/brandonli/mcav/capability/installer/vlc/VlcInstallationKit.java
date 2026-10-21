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
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.Installer;
import me.brandonli.mcav.capability.installer.debian.DebianLibraryInstaller;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.http.DownloadException;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the VLC native libraries available to vlcj.
 *
 * <p>The kit looks for VLC in this order, and uses the first one it finds:
 * <ol>
 *   <li>a VLC installation on the system, in the well-known installation directories of every operating system;
 *   <li>the private copy an earlier run installed with {@link VlcInstaller}, which needs no network at all;
 *   <li>a new private copy, downloaded with {@link VlcInstaller}, or on 64-bit x86 and ARM Linux installed from the
 *   pinned Debian packages of {@link DebianLibraryInstaller#createVlcInstaller()}, which bring every library that VLC
 *   needs besides glibc.
 * </ol>
 * The libraries of a private copy are loaded straight from its installation directory, and the kit never touches
 * vlcj's own configuration files. Either way, vlcj is ready to create media players once {@link #start()} returns.
 * Factory-created kits share synchronized load state, so concurrent starts use the first successful native
 * load. The kit does not own media players and has no close operation; callers release their players separately.
 *
 * {@snippet :
 * final VlcInstallationKit kit = VlcInstallationKit.create();
 * final Optional<Path> libraryDirectory = kit.start();
 * }
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class VlcInstallationKit {

  private static final Logger LOGGER = LoggerFactory.getLogger(VlcInstallationKit.class);
  private static final VlcLoadState SHARED_LOAD_STATE = new VlcLoadState();

  private static final Platform LINUX_X86_64 = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);
  private static final Platform LINUX_ARM_64 = Platform.ofPlatform(Os.LINUX, Arch.ARM, Bits.BITS_64);
  private static final Set<Platform> DEBIAN_PLATFORMS = Set.of(LINUX_X86_64, LINUX_ARM_64);

  private final Installer installer;
  private final VlcDiscovery discovery;
  private final VlcLoadState loadState;

  @VisibleForTesting
  VlcInstallationKit(final Installer installer, final VlcDiscovery discovery, final VlcLoadState loadState) {
    this.installer = installer;
    this.discovery = discovery;
    this.loadState = loadState;
  }

  /**
   * Creates a kit that installs VLC into the cache folder of the library when no system installation exists. On
   * 64-bit x86 and ARM Linux, the kit installs the pinned Debian bookworm packages of VLC with
   * {@link DebianLibraryInstaller#createVlcInstaller()} instead of a download, and loads their libraries before VLC.
   *
   * @return the kit
   * @throws FileOperationException if the default cache folder cannot be created
   * @since 1.0.0
   */
  public static VlcInstallationKit create() {
    final Platform current = Platform.getCurrentPlatform();
    return createForPlatform(current);
  }

  /**
   * Creates a kit that uses the specified installer when no system installation exists.
   *
   * @param installer the installer used to download VLC
   * @return the kit
   * @throws NullPointerException if {@code installer} is null
   * @since 1.0.0
   */
  public static VlcInstallationKit create(final VlcInstaller installer) {
    Preconditions.checkNotNull(installer, "Installer must not be null!");

    final NativeVlcDiscovery discovery = new NativeVlcDiscovery();
    return new VlcInstallationKit(installer, discovery, SHARED_LOAD_STATE);
  }

  @VisibleForTesting
  static VlcInstallationKit createForPlatform(final Platform platform) {
    final boolean debian = DEBIAN_PLATFORMS.contains(platform);
    if (debian) {
      return createDebianKit();
    }

    final VlcInstaller installer = VlcInstaller.create();
    return create(installer);
  }

  private static VlcInstallationKit createDebianKit() {
    final DebianLibraryInstaller installer = DebianLibraryInstaller.createVlcInstaller();
    final NativeVlcDiscovery nativeDiscovery = new NativeVlcDiscovery();
    final PreloadingVlcDiscovery discovery = new PreloadingVlcDiscovery(installer::loadLibraries, nativeDiscovery);
    return new VlcInstallationKit(installer, discovery, SHARED_LOAD_STATE);
  }

  /**
   * Finds or installs VLC and loads its native libraries into vlcj. This method blocks while VLC is downloaded,
   * unless an existing installation can be reused. Successful load state is shared by factory-created kits, so after the first successful call
   * every call returns the same result immediately.
   *
   * @return the directory the native libraries were loaded from, or empty if vlcj had already loaded them without
   * reporting a location
   * @throws InstallationException              if installation discovery or extraction fails
   * @throws DownloadException                   if the download of VLC fails or does not match its hash
   * @throws FileOperationException              if a downloaded or extracted file cannot be moved or deleted
   * @throws UnsupportedOperatingSystemException if VLC cannot be found or installed on this system, or
   *                                             the discovered private libraries cannot be loaded
   * @since 1.0.0
   */
  public Optional<Path> start() {
    return this.loadState.loadOnce(this::discoverOrInstall);
  }

  private Optional<Path> discoverOrInstall() {
    final boolean found = this.discovery.discoverSystemInstallation();
    if (found) {
      final Path systemPath = this.discovery.getDiscoveredPath();
      logSystemInstallation(systemPath);
      return Optional.ofNullable(systemPath);
    }

    // an earlier private installation is used before anything asks the network whether VLC can be downloaded, so a
    // machine keeps working offline, and GitHub is not asked on every start
    final Optional<Path> existing = this.installer.findInstallation();
    final @Nullable Path existingDirectory = existing.orElse(null);
    if (existingDirectory != null) {
      return this.loadPrivateInstallation(existingDirectory);
    }

    final boolean supported = this.installer.isSupported();
    if (!supported) {
      final Platform platform = Platform.getCurrentPlatform();
      final String message = "VLC is not installed and platform %s has no VLC download!".formatted(platform);
      throw new UnsupportedOperatingSystemException(message);
    }

    final Path libraryDirectory = this.installer.download(FileMode.EXECUTABLE);
    return this.loadPrivateInstallation(libraryDirectory);
  }

  private Optional<Path> loadPrivateInstallation(final Path libraryDirectory) {
    final boolean loaded = this.discovery.loadInstalledLibraries(libraryDirectory);
    if (!loaded) {
      final String message = "VLC was installed to %s but its libraries could not be loaded!".formatted(libraryDirectory);
      throw new UnsupportedOperatingSystemException(message);
    }

    LOGGER.info("The VLC installation of the library at {} is used.", libraryDirectory);
    return Optional.of(libraryDirectory);
  }

  private static void logSystemInstallation(final @Nullable Path systemPath) {
    if (systemPath == null) {
      LOGGER.info("Using the VLC libraries vlcj loaded earlier in this JVM.");
      return;
    }

    LOGGER.info("The VLC installation of the system at {} is used.", systemPath);
  }

  @VisibleForTesting
  Installer getInstaller() {
    return this.installer;
  }

  @VisibleForTesting
  VlcLoadState getLoadState() {
    return this.loadState;
  }
}
