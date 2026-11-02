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
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.vlc.VlcInstaller;
import me.brandonli.mcav.utils.FileOperationException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Installs VLC on macOS by copying {@code VLC.app} out of the downloaded disk image.
 *
 * <p>The disk image is attached read-only at a private mount point, so it never shows up in Finder, the
 * application bundle is copied into the installation directory with {@code cp -R} to preserve its symbolic links,
 * and the image is detached and deleted afterward. The returned directory is {@code VLC.app/Contents/MacOS/lib},
 * which contains {@code libvlc.dylib}.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class OsxInstallationStrategy extends ManualInstallationStrategy {

  private static final Pattern LIBRARY = Pattern.compile("libvlc\\.dylib");
  private static final Pattern CORE_LIBRARY = Pattern.compile("libvlccore\\.dylib");
  private static final String VLC_BUNDLE = "VLC.app";
  private static final String MOUNT_DIRECTORY = "vlc-mount";

  private static final String DISK_IMAGE_TOOL = "hdiutil";
  private static final String DETACH_COMMAND = "detach";

  /**
   * Creates a new strategy for the installer.
   *
   * @param installer the installer whose installation directory is used
   * @throws NullPointerException if {@code installer} is null
   * @since 1.0.0
   */
  public OsxInstallationStrategy(final VlcInstaller installer) {
    super(installer);
  }

  OsxInstallationStrategy(final VlcInstaller installer, final ProcessRunner processRunner) {
    super(installer, processRunner);
  }

  /**
   * Looks for nonempty {@code libvlc.dylib} and {@code libvlccore.dylib} in the same installation directory.
   *
   * @return the directory that contains the library, or empty if VLC has not been installed
   * @throws InstallationException if the installation directory cannot be searched
   * @since 1.0.0
   */
  @Override
  public Optional<Path> getInstalledPath() {
    final Path installDirectory = this.getInstallDirectory();
    return findLibraryDirectory(installDirectory, LIBRARY, CORE_LIBRARY);
  }

  /**
   * Copies {@code VLC.app} out of the disk image into the installation directory and deletes the disk image.
   *
   * @param archive the downloaded disk image
   * @return the directory that contains {@code libvlc.dylib}
   * @throws InstallationException  if the disk image cannot be mounted or copied, or does not contain the library
   * @throws FileOperationException if an earlier installation or the disk image cannot be deleted, or the installation
   *                                directory or the mount point cannot be created
   * @throws NullPointerException   if {@code archive} is null
   * @since 1.0.0
   */
  @Override
  public Path execute(final Path archive) {
    Preconditions.checkNotNull(archive, "Archive must not be null!");

    final Path installDirectory = this.getInstallDirectory();
    final Path parentOrNull = installDirectory.getParent();
    final Path parent = Objects.requireNonNull(parentOrNull, "The installation directory lies in the installer folder!");
    final Path mountPoint = parent.resolve(MOUNT_DIRECTORY);
    deleteRecursively(installDirectory);
    createDirectories(installDirectory, mountPoint);
    this.copyFromDiskImage(archive, mountPoint, installDirectory);
    this.deleteFile(archive);
    final Optional<Path> libraryDirectory = findLibraryDirectory(installDirectory, LIBRARY, CORE_LIBRARY);
    final @Nullable Path directory = libraryDirectory.orElse(null);
    if (directory == null) {
      final String message = "The VLC disk image does not contain nonempty libvlc.dylib and libvlccore.dylib in the same directory!";
      throw new InstallationException(message);
    }

    return directory;
  }

  private static void createDirectories(final Path installDirectory, final Path mountPoint) {
    try {
      Files.createDirectories(installDirectory);
      Files.createDirectories(mountPoint);
    } catch (final IOException exception) {
      final String message = "Directories %s and %s could not be created!".formatted(installDirectory, mountPoint);
      throw new FileOperationException(message, exception);
    }
  }

  // Mounts the disk image read-only, copies VLC.app out of it, and unmounts it again. When the copy fails, the image is
  // still detached, and a failure to detach is attached to the copy failure instead of hiding it.
  private void copyFromDiskImage(final Path archive, final Path mountPoint, final Path installDirectory) {
    final String rawArchive = archive.toString();
    final String rawMountPoint = mountPoint.toString();
    final String rawInstallDirectory = installDirectory.toString();
    this.runProcess(null, DISK_IMAGE_TOOL, "attach", "-nobrowse", "-readonly", "-mountpoint", rawMountPoint, rawArchive);
    try {
      final Path mountedBundle = mountPoint.resolve(VLC_BUNDLE);
      final String rawMountedBundle = mountedBundle.toString();
      this.runProcess(null, "cp", "-R", rawMountedBundle, rawInstallDirectory);
    } catch (final InstallationException exception) {
      this.detachAfterFailure(mountPoint, exception);
      throw exception;
    }

    this.detach(mountPoint);
  }

  private void detachAfterFailure(final Path mountPoint, final InstallationException copyFailure) {
    try {
      this.detach(mountPoint);
    } catch (final InstallationException | FileOperationException exception) {
      copyFailure.addSuppressed(exception);
    }
  }

  // Detaches the disk image, forcing it when a regular detach fails, for example because a process still has a file of
  // the image open. The mount point is only deleted once the image is detached, because deleting it while mounted would
  // reach into the image.
  private void detach(final Path mountPoint) {
    final String rawMountPoint = mountPoint.toString();
    try {
      this.runProcess(null, DISK_IMAGE_TOOL, DETACH_COMMAND, rawMountPoint);
    } catch (final InstallationException exception) {
      this.forceDetach(rawMountPoint, exception);
    }

    deleteRecursively(mountPoint);
  }

  private void forceDetach(final String rawMountPoint, final InstallationException detachFailure) {
    try {
      this.runProcess(null, DISK_IMAGE_TOOL, DETACH_COMMAND, "-force", rawMountPoint);
    } catch (final InstallationException exception) {
      detachFailure.addSuppressed(exception);
      throw detachFailure;
    }
  }
}
