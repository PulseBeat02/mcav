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
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.Installer;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.http.DownloadException;
import me.brandonli.mcav.utils.os.Platform;

/**
 * Installs the programs of pinned Debian packages, with the shared libraries they need, into the cache folder of MCAV,
 * so a Linux machine without a program can run it without a package manager.
 *
 * <p>The packages come from a class path resource in the format of {@code mcav/common/installers/debian-vlc.json}: a
 * JSON array of downloads, each with its platform, URL and SHA-256, which lists each package after the packages it
 * depends on. Every package is downloaded, checked against its hash, and its {@code usr/lib} and {@code lib} trees and
 * the trees that the caller names, such as {@code usr/bin}, are extracted by MCAV itself, without {@code dpkg} and
 * without administrator rights, into one folder of the cache, which falls back to {@code java.io.tmpdir} when the home
 * folder cannot be written. A file is executable when its entry in the package is, and where the file system has POSIX
 * permissions, no file or folder that MCAV extracts is ever writable by anyone but its owner. A symbolic link must stay
 * inside the installation along a normalized relative path; any other link fails the installation. Leave glibc out of
 * a list, as the lists of MCAV do, because a program always runs on the glibc of the machine: it needs one at least as
 * new as the one of its Debian release, such as 2.36 for bookworm.
 *
 * <p>Nothing is loaded into the JVM: run a program as a process, with the folders of {@link #listLibraryFolders()} on
 * its library path, such as {@code LD_LIBRARY_PATH}.
 *
 * {@snippet :
 * final String resource = "com/example/debian-xz.json";
 * final String name = "debian-xz";
 * final List<String> trees = List.of("usr/bin");
 * final DebianProgramInstaller installer = DebianProgramInstaller.create(resource, name, trees);
 * final Path root = installer.download(FileMode.UNCHANGED);
 * final Path program = root.resolve("usr/bin/xz");
 * final List<Path> libraryFolders = installer.listLibraryFolders();
 * }
 *
 * <p>This class is thread-safe.
 *
 * @see DebianLibraryInstaller
 * @since 1.0.0
 */
public final class DebianProgramInstaller implements Installer {

  private static final Pattern FOLDER_NAME = Pattern.compile("[\\w+-][\\w.+-]*");
  private static final Pattern TREE = Pattern.compile("[\\w+-][\\w.+-]*(/[\\w+-][\\w.+-]*)*");

  private final DebianInstallation installation;

  @VisibleForTesting
  DebianProgramInstaller(
    final Path folder,
    final String name,
    final Download[] downloads,
    final Platform platform,
    final List<String> trees
  ) {
    this.installation = new DebianInstallation(folder, name, downloads, platform, trees);
  }

  /**
   * Creates an installer of the packages that a package list names for this platform, in a folder of the cache.
   *
   * @param resource the class path resource of the package list, such as {@code com/example/debian-xz.json}
   * @param name     the folder of the installation inside the cache, such as {@code debian-xz}, which only this list
   *                 may use
   * @param trees    the trees to extract besides {@code usr/lib} and {@code lib}, as relative paths such as
   *                 {@code usr/bin}
   * @return the installer
   * @throws NullPointerException     if an argument or a tree is null
   * @throws IllegalArgumentException if {@code name} is not a folder name, or a tree is not a relative path of folder
   *                                  names
   * @throws FileOperationException   if the package list or the cache folder cannot be read
   * @since 1.0.0
   */
  public static DebianProgramInstaller create(final String resource, final String name, final List<String> trees) {
    Preconditions.checkNotNull(resource, "Resource must not be null!");
    Preconditions.checkNotNull(name, "Name must not be null!");
    Preconditions.checkNotNull(trees, "Trees must not be null!");
    final boolean folderName = FOLDER_NAME.matcher(name).matches();
    Preconditions.checkArgument(folderName, "Name %s must be a folder name!", name);
    for (final String tree : trees) {
      Preconditions.checkNotNull(tree, "Tree must not be null!");
      final boolean relativeTree = TREE.matcher(tree).matches();
      Preconditions.checkArgument(relativeTree, "Tree %s must be a relative path of folder names!", tree);
    }

    final Download[] downloads = IoUtils.readDownloadsFromJsonResource(resource);
    final Path cache = IoUtils.getCachedFolder();
    final Path folder = cache.resolve(name);
    final Platform current = Platform.getCurrentPlatform();
    return new DebianProgramInstaller(folder, name, downloads, current, trees);
  }

  /**
   * Downloads and extracts the packages, or returns the existing installation. The packages are extracted into a
   * temporary folder next to the installation, which is moved into place once every package is extracted, so a failed
   * installation leaves nothing behind.
   *
   * @param mode ignored, because each file keeps the executable bit of its entry in the package
   * @return the installation folder, which holds the extracted trees, such as {@code usr/bin}
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

    return this.installation.getFolder();
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
   * Returns the installation folder of an earlier installation, without downloading anything.
   *
   * @return the installation folder, or empty if the packages are not installed
   * @since 1.0.0
   */
  @Override
  public Optional<Path> findInstallation() {
    final boolean installed = this.installation.isInstalled();
    if (!installed) {
      return Optional.empty();
    }

    final Path folder = this.installation.getFolder();
    return Optional.of(folder);
  }

  /**
   * Returns the installation folder, whether or not the packages are installed.
   *
   * @return the installation folder
   * @since 1.0.0
   */
  @Override
  public Path getPath() {
    return this.installation.getFolder();
  }
}
