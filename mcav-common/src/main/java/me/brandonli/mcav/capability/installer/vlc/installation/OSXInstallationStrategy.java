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
package me.brandonli.mcav.capability.installer.vlc.installation;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;

/**
 * Installs VLC on macOS by copying {@code VLC.app} out of the downloaded disk image.
 *
 * <p>The disk image is attached read-only at a private mount point, so it never shows up in Finder, the
 * application bundle is copied into the installation directory with {@code cp -R} to preserve its symbolic links,
 * and the image is detached and deleted afterward. The returned directory is {@code VLC.app/Contents/MacOS/lib},
 * which contains {@code libvlc.dylib}.
 */
public final class OSXInstallationStrategy extends ManualInstallationStrategy {

  private static final Pattern LIBRARY = Pattern.compile("libvlc\\.dylib");
  private static final String VLC_APP = "VLC.app";
  private static final String MOUNT_DIRECTORY = "vlc-mount";

  /**
   * Constructs a new strategy for the installer.
   *
   * @param installer the installer whose installation directory is used
   */
  public OSXInstallationStrategy(final VLCInstaller installer) {
    super(installer);
  }

  /**
   * Constructs a new strategy that runs {@code hdiutil} and {@code cp} through the specified runner.
   *
   * @param installer     the installer whose installation directory is used
   * @param processRunner runs the disk image tools
   */
  OSXInstallationStrategy(final VLCInstaller installer, final ProcessRunner processRunner) {
    super(installer, processRunner);
  }

  /**
   * Looks for {@code libvlc.dylib} inside the installation directory.
   *
   * @return the directory that contains the library, or empty if VLC has not been installed
   * @throws IOException if the installation directory cannot be searched
   */
  @Override
  public Optional<Path> getInstalledPath() throws IOException {
    final Path installDirectory = this.getInstallDirectory();
    return findLibraryDirectory(installDirectory, LIBRARY);
  }

  /**
   * Copies {@code VLC.app} out of the disk image into the installation directory and deletes the disk image.
   *
   * @param archive the downloaded disk image
   * @return the directory that contains {@code libvlc.dylib}
   * @throws IOException if the disk image cannot be mounted or copied, or does not contain the library
   */
  @Override
  public Path execute(final Path archive) throws IOException {
    Preconditions.checkNotNull(archive, "Archive must not be null");
    final Path installDirectory = this.getInstallDirectory();
    final Path parentOrNull = installDirectory.getParent();
    final Path parent = Objects.requireNonNull(parentOrNull, "The installation directory lies in the installer folder");
    final Path mountPoint = parent.resolve(MOUNT_DIRECTORY);
    deleteRecursively(installDirectory);
    Files.createDirectories(installDirectory);
    Files.createDirectories(mountPoint);
    this.copyFromDiskImage(archive, mountPoint, installDirectory);
    Files.deleteIfExists(archive);
    final Optional<Path> libraryDirectory = findLibraryDirectory(installDirectory, LIBRARY);
    if (libraryDirectory.isEmpty()) {
      throw new IOException("The VLC disk image does not contain libvlc.dylib");
    }
    return libraryDirectory.get();
  }

  /**
   * Mounts the disk image read-only, copies {@code VLC.app} out of it, and unmounts it again. When the copy fails,
   * the image is still detached, and a failure to detach is attached to the copy failure instead of hiding it.
   */
  private void copyFromDiskImage(final Path archive, final Path mountPoint, final Path installDirectory) throws IOException {
    final String rawArchive = archive.toString();
    final String rawMountPoint = mountPoint.toString();
    final String rawInstallDirectory = installDirectory.toString();
    this.runProcess(null, "hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", rawMountPoint, rawArchive);
    try {
      final Path mountedApp = mountPoint.resolve(VLC_APP);
      final String rawMountedApp = mountedApp.toString();
      this.runProcess(null, "cp", "-R", rawMountedApp, rawInstallDirectory);
    } catch (final IOException copyFailure) {
      this.detachAfterFailure(mountPoint, copyFailure);
      throw copyFailure;
    }
    this.detach(mountPoint);
  }

  private void detachAfterFailure(final Path mountPoint, final IOException copyFailure) {
    try {
      this.detach(mountPoint);
    } catch (final IOException detachFailure) {
      copyFailure.addSuppressed(detachFailure);
    }
  }

  /**
   * Detaches the disk image, forcing it when a regular detach fails, for example because a process still has a file
   * of the image open. The mount point is only deleted once the image is detached, because deleting it while mounted
   * would reach into the image.
   */
  private void detach(final Path mountPoint) throws IOException {
    final String rawMountPoint = mountPoint.toString();
    try {
      this.runProcess(null, "hdiutil", "detach", rawMountPoint);
    } catch (final IOException detachFailure) {
      this.forceDetach(rawMountPoint, detachFailure);
    }
    deleteRecursively(mountPoint);
  }

  private void forceDetach(final String rawMountPoint, final IOException detachFailure) throws IOException {
    try {
      this.runProcess(null, "hdiutil", "detach", "-force", rawMountPoint);
    } catch (final IOException forcedFailure) {
      detachFailure.addSuppressed(forcedFailure);
      throw detachFailure;
    }
  }
}
