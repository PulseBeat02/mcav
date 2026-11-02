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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import me.brandonli.mcav.capability.installer.AbstractInstaller;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.vlc.installation.InstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.LinuxInstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.ManualInstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.OsxInstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.WindowsInstallationStrategy;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs a private copy of the VLC media player for the {@code vlc} capability.
 *
 * <p>VLC is shipped as a zip on Windows, a disk image on macOS, and an AppImage on Linux. The archive for the
 * current platform is downloaded into the installation folder, extracted by the matching
 * {@link InstallationStrategy} into the {@code vlc} directory, and deleted afterward. {@link #download(FileMode)}
 * returns the directory that contains the {@code libvlc} library, ready to be handed to vlcj.
 *
 * <p>The installation is only used when no VLC is installed on the system. See {@link VlcInstallationKit}, which
 * ties the discovery of a system installation and this installer together.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class VlcInstaller extends AbstractInstaller {

  /**
   * The VLC version the Windows and macOS downloads point to.
   *
   * @since 1.0.0
   */
  public static final String VERSION = "3.0.24";

  private static final Logger LOGGER = LoggerFactory.getLogger(VlcInstaller.class);

  private static final String NAME = "vlc";

  private static final String DOWNLOADS_RESOURCE = "mcav/common/installers/vlc.json";

  // earlier versions of the library installed VLC through JuNest on Linux and as a bare VLC.app on macOS
  private static final List<String> LEGACY_DIRECTORIES = List.of("vlc-junest", "VLC.app");

  private final AtomicBoolean legacyInstallationsRemoved;

  @VisibleForTesting
  VlcInstaller(final Path folder, final Supplier<Download[]> downloads) {
    super(folder, NAME, downloads);
    this.legacyInstallationsRemoved = new AtomicBoolean(false);
  }

  private VlcInstaller(final Path folder) {
    this(folder, () -> ReleasePackageManager.readVlcDownloadsFromJsonResource(DOWNLOADS_RESOURCE));
  }

  private VlcInstaller() {
    final Path defaultFolder = AbstractInstaller.getDefaultExecutableFolderPath();
    this(defaultFolder);
  }

  /**
   * Creates an installer that installs VLC into the specified folder.
   *
   * @param folder the folder to install into
   * @return the installer
   * @throws NullPointerException if {@code folder} is null
   * @since 1.0.0
   */
  public static VlcInstaller create(final Path folder) {
    Preconditions.checkNotNull(folder, "Folder must not be null!");

    return new VlcInstaller(folder);
  }

  /**
   * Creates an installer that installs VLC into the cache folder of the library, {@code ~/.mcav/cache}.
   *
   * @return the installer
   * @throws FileOperationException if the default cache folder cannot be created
   * @since 1.0.0
   */
  public static VlcInstaller create() {
    return new VlcInstaller();
  }

  /**
   * Returns the directory the VLC installation is extracted into.
   *
   * @return the installation directory
   * @since 1.0.0
   */
  public Path getInstallDirectory() {
    final Path folder = this.getFolder();
    return folder.resolve(NAME);
  }

  /**
   * Checks whether VLC can be used from this installer: either an earlier installation exists, or the bundled
   * downloads list one for the current platform. Neither check asks the network.
   *
   * @return true if VLC is installed already or can be installed on this platform
   * @since 1.0.0
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
    } catch (final InstallationException exception) {
      return false;
    }
  }

  // Looks for the libraries of an earlier installation inside the installation directory. A directory without the
  // libraries, such as one left behind by an interrupted installation, does not count. The first call also deletes the
  // installations that earlier versions of the library left behind.
  @Override
  protected Optional<Path> findExistingInstallation() {
    final Os operatingSystem = OsUtils.getOs();
    return this.findExistingInstallation(operatingSystem);
  }

  @VisibleForTesting
  Optional<Path> findExistingInstallation(final Os operatingSystem) {
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

  // Deletes the installations earlier versions of the library left in the installer folder: vlc-junest on Linux and
  // VLC.app on macOS. Only these exact directories are deleted. A symbolic link or a regular file with one of these
  // names is left alone, and links inside the directories are deleted without following them. A directory that cannot
  // be deleted is logged and left behind.
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
      LOGGER.info("The old VLC installation at {} was deleted.", legacy);
    } catch (final FileOperationException exception) {
      final String reason = exception.getMessage();
      LOGGER.warn("Deleting the old VLC installation at {} failed with \"{}\", so it stays in place.", legacy, reason);
    }
  }

  @VisibleForTesting
  static boolean hasStrategy(final Os operatingSystem) {
    return switch (operatingSystem) {
      case MAC, WINDOWS, LINUX -> true;
      case FREEBSD, OTHER -> false;
    };
  }

  @Override
  protected Path install(final Path downloaded) {
    final Os operatingSystem = OsUtils.getOs();
    final InstallationStrategy strategy = createStrategy(this, operatingSystem);
    return strategy.execute(downloaded);
  }

  @VisibleForTesting
  static InstallationStrategy createStrategy(final VlcInstaller installer, final Os operatingSystem) {
    return switch (operatingSystem) {
      case MAC -> new OsxInstallationStrategy(installer);
      case WINDOWS -> new WindowsInstallationStrategy(installer);
      case LINUX -> new LinuxInstallationStrategy(installer);
      case FREEBSD, OTHER -> {
        final String message = "Operating system %s does not support installing VLC automatically!".formatted(operatingSystem);
        throw new UnsupportedOperatingSystemException(message);
      }
    };
  }

  /**
   * Checks whether the download is an archive, which is always the case for VLC.
   *
   * @return true
   * @since 1.0.0
   */
  @Override
  public boolean isArchive() {
    return true;
  }

  // Returns the directory VLC is extracted into. The libraries themselves live somewhere inside it, depending on the
  // operating system; download(FileMode) returns their exact location.
  @Override
  protected Path getDefaultPath() {
    return this.getInstallDirectory();
  }
}
