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
package me.brandonli.mcav.capability.installer.debian;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.Installer;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.http.DownloadException;
import me.brandonli.mcav.utils.os.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs the shared libraries of pinned Debian packages into the cache folder of MCAV and loads them, so a Linux
 * machine without a library such as VLC can use it without a package manager.
 *
 * <p>The packages come from the Debian bookworm archive and its security updates on {@code snapshot.debian.org}, the
 * newer version of each package winning, in the lists {@code mcav/common/installers/debian-vlc.json},
 * {@code mcav/common/installers/debian-gtk2.json} and {@code mcav/common/installers/debian-ffmpeg.json}, which list
 * each package after the packages it depends on, with its SHA-256. Every package is downloaded, checked against its
 * hash, and its {@code usr/lib} and {@code lib} trees are extracted by MCAV itself, without {@code dpkg} and without
 * administrator rights, into one folder of the cache. The libraries need the glibc 2.36 and GCC 12 runtime of Debian
 * bookworm or newer, which the lists leave out.
 *
 * <p>{@link #loadLibraries()} loads every library of the installation into the JVM, from the library folders and
 * their direct subfolders such as {@code pulseaudio}, the libraries of each package after those of the packages before
 * it, so that a library that later loads one of them by name, such as a VLC plugin, finds it already loaded. The
 * libraries keep their symbols local, except {@code libtheoradec}, which joins the global scope of the dynamic linker
 * because {@code libtheoraenc} uses its symbols without linking it. A library that does not load, for example because
 * the machine has an older glibc, is logged and skipped. A program that MCAV runs does not share the libraries of the
 * JVM; it finds them through the folders of {@link #listLibraryFolders()}.
 *
 * <p>This class is thread-safe.
 *
 * @see DebianProgramInstaller
 * @since 1.0.0
 */
public final class DebianLibraryInstaller implements Installer {

  private static final Logger LOGGER = LoggerFactory.getLogger(DebianLibraryInstaller.class);

  private static final String VLC_RESOURCE = "mcav/common/installers/debian-vlc.json";
  private static final String VLC_NAME = "debian-vlc";
  private static final String GTK2_RESOURCE = "mcav/common/installers/debian-gtk2.json";
  private static final String GTK2_NAME = "debian-gtk2";
  private static final String FFMPEG_RESOURCE = "mcav/common/installers/debian-ffmpeg.json";
  private static final String FFMPEG_NAME = "debian-ffmpeg";

  private final DebianInstallation installation;
  private final String name;
  private final Consumer<String> libraryLoader;

  @VisibleForTesting
  DebianLibraryInstaller(final Path folder, final String name, final Download[] downloads, final Platform platform) {
    this(folder, name, downloads, platform, new PromotingLibraryLoader());
  }

  @VisibleForTesting
  DebianLibraryInstaller(
    final Path folder,
    final String name,
    final Download[] downloads,
    final Platform platform,
    final Consumer<String> libraryLoader
  ) {
    final List<String> extraTrees = List.of();
    this.installation = new DebianInstallation(folder, name, downloads, platform, extraTrees);
    this.name = name;
    this.libraryLoader = libraryLoader;
  }

  /**
   * Creates an installer of {@code libvlc5}, {@code libvlccore9} and {@code vlc-plugin-base} with their dependencies,
   * which has packages for 64-bit x86 and ARM Linux.
   *
   * @return the installer
   * @throws FileOperationException if the package list or the cache folder cannot be read
   * @since 1.0.0
   */
  public static DebianLibraryInstaller createVlcInstaller() {
    return create(VLC_RESOURCE, VLC_NAME);
  }

  /**
   * Creates an installer of {@code libgtk2.0-0} with its dependencies, which has packages for 64-bit x86 and ARM
   * Linux. OpenCV needs GTK 2 to load its object detection module on Linux.
   *
   * @return the installer
   * @throws FileOperationException if the package list or the cache folder cannot be read
   * @since 1.0.0
   */
  public static DebianLibraryInstaller createGtk2Installer() {
    return create(GTK2_RESOURCE, GTK2_NAME);
  }

  /**
   * Creates an installer of {@code libasound2}, {@code libpulse0}, {@code libxcb1}, {@code libxcb-shm0} and
   * {@code libudev1} with their dependencies, which has packages for 64-bit x86 and ARM Linux. The libraries of FFmpeg
   * link these system libraries, which a Linux machine without sound or a desktop can lack.
   *
   * @return the installer
   * @throws FileOperationException if the package list or the cache folder cannot be read
   * @since 1.0.0
   */
  public static DebianLibraryInstaller createFfmpegInstaller() {
    return create(FFMPEG_RESOURCE, FFMPEG_NAME);
  }

  private static DebianLibraryInstaller create(final String resource, final String name) {
    final Download[] downloads = IoUtils.readDownloadsFromJsonResource(resource);
    final Path cache = IoUtils.getCachedFolder();
    final Path folder = cache.resolve(name);
    final Platform current = Platform.getCurrentPlatform();
    return new DebianLibraryInstaller(folder, name, downloads, current);
  }

  /**
   * Downloads and extracts the packages, or returns the existing installation. The packages are extracted into a
   * temporary folder next to the installation, which is moved into place once every package is extracted, so a failed
   * installation leaves nothing behind.
   *
   * @param mode ignored, because libraries are loaded and not run
   * @return the library folder of the installation, such as {@code usr/lib/aarch64-linux-gnu}
   * @throws InstallationException  if this platform has no packages, a package has no pinned hash, or a package cannot
   *                                be extracted or moved
   * @throws DownloadException      if a download fails or does not match its hash
   * @throws FileOperationException if a broken earlier installation or the temporary folder cannot be deleted
   * @throws NullPointerException   if {@code mode} is null
   * @since 1.0.0
   */
  @Override
  public synchronized Path download(final FileMode mode) {
    Preconditions.checkNotNull(mode, "Mode must not be null!");

    this.installation.install();

    return this.getPath();
  }

  /**
   * Loads every library of the installation into the JVM, the libraries of each package after those of the packages
   * it depends on. A library that does not load is retried after the others, and skipped if it never loads, with a
   * warning unless it lies in a subfolder of a library folder.
   *
   * @throws InstallationException if the packages are not installed, or the installation cannot be read
   * @since 1.0.0
   */
  public synchronized void loadLibraries() {
    List<Path> pending = this.installation.readLibraries();
    int remaining = -1;
    while (pending.size() != remaining) {
      remaining = pending.size();
      pending = this.loadEach(pending);
    }

    for (final Path library : pending) {
      this.logSkipped(library);
    }
  }

  // Loads every library it can and returns the others.
  private List<Path> loadEach(final List<Path> libraries) {
    final List<Path> failed = new ArrayList<>();
    for (final Path library : libraries) {
      final boolean loaded = this.load(library);
      if (!loaded) {
        failed.add(library);
      }
    }

    return failed;
  }

  private boolean load(final Path library) {
    try {
      final String path = library.toString();
      this.libraryLoader.accept(path);
      return true;
    } catch (final UnsatisfiedLinkError error) {
      LOGGER.debug("Library {} could not be loaded yet.", library, error);
      return false;
    }
  }

  // a subfolder library, such as a plugin, matters only to the libraries that need it
  private void logSkipped(final Path library) {
    final boolean inSubfolder = this.installation.isInSubfolder(library);
    if (inSubfolder) {
      LOGGER.debug("Library {} of {} could not be loaded and is skipped.", library, this.name);
      return;
    }

    LOGGER.warn("Library {} of {} could not be loaded and is skipped.", library, this.name);
  }

  /**
   * Returns the folders that hold the libraries of the installation, in the order in which the packages list them, to
   * put on the library path of a program, such as {@code LD_LIBRARY_PATH}.
   *
   * @return the library folders
   * @throws InstallationException if the packages are not installed, or the installation cannot be read
   * @since 1.0.0
   */
  public List<Path> listLibraryFolders() {
    return this.installation.listLibraryFolders();
  }

  /**
   * Returns whether this platform has packages.
   *
   * @return whether {@link #download(FileMode)} can install the packages here
   * @since 1.0.0
   */
  @Override
  public boolean isSupported() {
    return this.installation.isSupported();
  }

  /**
   * Returns the library folder of an earlier installation, without downloading anything.
   *
   * @return the library folder, or empty if the packages are not installed
   * @since 1.0.0
   */
  @Override
  public Optional<Path> findInstallation() {
    final boolean installed = this.installation.isInstalled();
    if (!installed) {
      return Optional.empty();
    }

    final Path libraryDirectory = this.getPath();
    return Optional.of(libraryDirectory);
  }

  /**
   * Returns the library folder of the installation, such as {@code usr/lib/aarch64-linux-gnu} inside the installation
   * folder, whether or not the packages are installed.
   *
   * @return the library folder
   * @since 1.0.0
   */
  @Override
  public Path getPath() {
    return this.installation.getLibraryFolder();
  }
}
