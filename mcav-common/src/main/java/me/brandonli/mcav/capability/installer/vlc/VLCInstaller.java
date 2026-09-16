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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import me.brandonli.mcav.capability.installer.AbstractInstaller;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.vlc.installation.InstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.LinuxInstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.ManualInstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.OSXInstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.WinInstallationStrategy;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs a private copy of the VLC media player for the {@code vlc} capability.
 *
 * <p>VLC is shipped as a zip on Windows, a disk image on macOS, and an AppImage on Linux. The archive for the
 * current platform is downloaded into the installation folder, extracted by the matching
 * {@link InstallationStrategy} into the {@code vlc} directory, and deleted afterward. {@link #download(boolean)}
 * returns the directory that contains the {@code libvlc} library, ready to be handed to vlcj.
 *
 * <p>The installation is only used when no VLC is installed on the system. See {@link VLCInstallationKit}, which
 * ties the discovery of a system installation and this installer together.
 */
public final class VLCInstaller extends AbstractInstaller {

  /**
   * The VLC version the Windows and macOS downloads point to.
   */
  public static final String VERSION = "3.0.23";

  private static final Logger LOGGER = LoggerFactory.getLogger(VLCInstaller.class);
  private static final String NAME = "vlc";
  private static final String DOWNLOADS_RESOURCE = "vlc.json";
  // earlier versions of the library installed VLC through JuNest on Linux and as a bare VLC.app on macOS
  private static final List<String> LEGACY_DIRECTORIES = List.of("vlc-junest", "VLC.app");

  private final AtomicBoolean legacyInstallationsRemoved;

  /**
   * Constructs a new installer with custom downloads.
   *
   * @param folder    the folder to install into
   * @param downloads supplies the downloads of VLC
   */
  @VisibleForTesting
  VLCInstaller(final Path folder, final Supplier<Download[]> downloads) {
    super(folder, NAME, downloads);
    this.legacyInstallationsRemoved = new AtomicBoolean(false);
  }

  VLCInstaller(final Path folder) {
    this(folder, () -> ReleasePackageManager.readVLCDownloadsFromJsonResource(DOWNLOADS_RESOURCE));
  }

  VLCInstaller() {
    final Path defaultFolder = AbstractInstaller.getDefaultExecutableFolderPath();
    this(defaultFolder);
  }

  /**
   * Creates an installer that installs VLC into the specified folder.
   *
   * @param folder the folder to install into
   * @return the installer
   */
  public static VLCInstaller create(final Path folder) {
    Preconditions.checkNotNull(folder, "Folder must not be null");
    return new VLCInstaller(folder);
  }

  /**
   * Creates an installer that installs VLC into the cache folder of the library, {@code ~/.mcav/cache}.
   *
   * @return the installer
   */
  public static VLCInstaller create() {
    return new VLCInstaller();
  }

  /**
   * Gets the directory the VLC installation is extracted into.
   *
   * @return the installation directory
   */
  public Path getInstallDirectory() {
    final Path folder = this.getFolder();
    return folder.resolve(NAME);
  }

  /**
   * Checks whether the download is an archive, which is always the case for VLC.
   *
   * @return true
   */
  @Override
  public boolean isArchive() {
    return true;
  }

  /**
   * Gets the directory VLC is extracted into. The libraries themselves live somewhere inside it, depending on the
   * operating system; {@link #download(boolean)} returns their exact location.
   *
   * @return the installation directory
   */
  @Override
  protected Path getDefaultPath() {
    return this.getInstallDirectory();
  }

  /**
   * Checks whether VLC can be used from this installer: either an earlier installation exists, which needs no
   * network, or a download exists for the current platform, which may ask GitHub for the Linux download links.
   *
   * @return true if VLC is installed already or can be installed on this platform
   */
  @Override
  public boolean isSupported() {
    final boolean installed = this.hasInstallation();
    if (installed) {
      return true;
    }
    return super.isSupported();
  }

  private boolean hasInstallation() {
    try {
      final Optional<Path> installation = this.findExistingInstallation();
      return installation.isPresent();
    } catch (final IOException exception) {
      return false;
    }
  }

  /**
   * Looks for the libraries of an earlier installation inside the installation directory. A directory without the
   * libraries, such as one left behind by an interrupted installation, does not count. The first call also deletes
   * the installations that earlier versions of the library left behind.
   *
   * @return the directory that contains the libraries, or empty if VLC has to be installed
   * @throws IOException if the installation directory cannot be searched
   */
  @Override
  protected Optional<Path> findExistingInstallation() throws IOException {
    final OS operatingSystem = OSUtils.getOS();
    return this.findExistingInstallation(operatingSystem);
  }

  /**
   * Looks for an earlier installation made for an operating system.
   *
   * @param operatingSystem the operating system
   * @return the directory that contains the libraries, or empty if VLC has to be installed or cannot be installed
   * on the system at all
   * @throws IOException if the installation directory cannot be searched
   */
  @VisibleForTesting
  Optional<Path> findExistingInstallation(final OS operatingSystem) throws IOException {
    this.removeLegacyInstallationsOnce();
    final boolean installable = hasStrategy(operatingSystem);
    if (!installable) {
      return Optional.empty();
    }
    final InstallationStrategy strategy = createStrategy(this, operatingSystem);
    return strategy.getInstalledPath();
  }

  private void removeLegacyInstallationsOnce() {
    final boolean first = this.legacyInstallationsRemoved.compareAndSet(false, true);
    if (!first) {
      return;
    }
    final Path folder = this.getFolder();
    removeLegacyInstallations(folder);
  }

  /**
   * Deletes the installations earlier versions of the library left in the installer folder: {@code vlc-junest} on
   * Linux and {@code VLC.app} on macOS. Only these exact directories are deleted. A symbolic link or a regular file
   * with one of these names is left alone, and links inside the directories are deleted without following them. A
   * directory that cannot be deleted is logged and left behind.
   *
   * @param folder the installer folder
   */
  @VisibleForTesting
  static void removeLegacyInstallations(final Path folder) {
    for (final String name : LEGACY_DIRECTORIES) {
      final Path legacy = folder.resolve(name);
      final boolean directory = Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS);
      if (directory) {
        deleteLegacyInstallation(legacy);
      }
    }
  }

  private static void deleteLegacyInstallation(final Path legacy) {
    try {
      ManualInstallationStrategy.deleteRecursively(legacy);
      LOGGER.info("Deleted the old VLC installation at {}", legacy);
    } catch (final IOException exception) {
      final String reason = exception.getMessage();
      LOGGER.warn("Could not delete the old VLC installation at {}: {}", legacy, reason);
    }
  }

  /**
   * Checks whether VLC can be installed automatically on an operating system.
   *
   * @param operatingSystem the operating system
   * @return true if an installation strategy exists for it
   */
  @VisibleForTesting
  static boolean hasStrategy(final OS operatingSystem) {
    return switch (operatingSystem) {
      case MAC, WINDOWS, LINUX -> true;
      case FREEBSD, OTHER -> false;
    };
  }

  /**
   * Extracts the downloaded archive with the strategy of the current operating system.
   *
   * @param downloaded the downloaded archive
   * @return the directory that contains the libraries
   * @throws IOException if the archive cannot be extracted
   */
  @Override
  protected Path install(final Path downloaded) throws IOException {
    final OS operatingSystem = OSUtils.getOS();
    final InstallationStrategy strategy = createStrategy(this, operatingSystem);
    return strategy.execute(downloaded);
  }

  /**
   * Creates the strategy that installs VLC on an operating system.
   *
   * @param installer       the installer the strategy installs for
   * @param operatingSystem the operating system
   * @return the strategy
   * @throws UnsupportedOperatingSystemException if VLC cannot be installed automatically on the system
   */
  @VisibleForTesting
  static InstallationStrategy createStrategy(final VLCInstaller installer, final OS operatingSystem) {
    return switch (operatingSystem) {
      case MAC -> new OSXInstallationStrategy(installer);
      case WINDOWS -> new WinInstallationStrategy(installer);
      case LINUX -> new LinuxInstallationStrategy(installer);
      case FREEBSD, OTHER -> throw new UnsupportedOperatingSystemException("VLC cannot be installed automatically on " + operatingSystem);
    };
  }
}
