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
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.vlc.VlcInstaller;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.ZipEntryIntegrityException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Installs VLC on Windows by extracting the portable zip.
 *
 * <p>The zip contains a single {@code vlc-<version>} directory with {@code libvlc.dll} at its root. The zip is
 * extracted into a temporary directory, that inner directory is moved to the installation directory, and the
 * temporary directory and the zip are deleted. The temporary directory is also deleted when the extraction fails.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class WindowsInstallationStrategy extends ManualInstallationStrategy {

  private static final Pattern LIBRARY = Pattern.compile("libvlc\\.dll");
  private static final Pattern CORE_LIBRARY = Pattern.compile("libvlccore\\.dll");
  private static final String TEMPORARY_DIRECTORY = "vlc-extract";

  /**
   * Creates a new strategy for the installer.
   *
   * @param installer the installer whose installation directory is used
   * @throws NullPointerException if {@code installer} is null
   * @since 1.0.0
   */
  public WindowsInstallationStrategy(final VlcInstaller installer) {
    super(installer);
  }

  /**
   * Looks for nonempty {@code libvlc.dll} and {@code libvlccore.dll} in the same installation directory.
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
   * Extracts the zip, moves the directory that contains {@code libvlc.dll} to the installation directory, and
   * deletes the zip.
   *
   * @param archive the downloaded zip
   * @return the installation directory, which contains {@code libvlc.dll}
   * @throws InstallationException  if the zip cannot be extracted or does not contain the library
   * @throws FileOperationException if an earlier installation, the extracted tree or the zip cannot be moved or
   *                                deleted
   * @throws NullPointerException   if {@code archive} is null
   * @since 1.0.0
   */
  @Override
  public Path execute(final Path archive) {
    Preconditions.checkNotNull(archive, "Archive must not be null!");

    final Path installDirectory = this.getInstallDirectory();
    final Path parentOrNull = installDirectory.getParent();
    final Path parent = Objects.requireNonNull(parentOrNull, "The installation directory lies in the installer folder!");
    final Path temporary = parent.resolve(TEMPORARY_DIRECTORY);
    deleteRecursively(temporary);
    deleteRecursively(installDirectory);
    try {
      extractInto(archive, temporary, installDirectory);
    } finally {
      deleteRecursively(temporary);
    }

    this.deleteFile(archive);
    return installDirectory;
  }

  private static void extractInto(final Path archive, final Path temporary, final Path installDirectory) {
    unzip(archive, temporary);
    final Optional<Path> extractedLibraryDirectory = findLibraryDirectory(temporary, LIBRARY, CORE_LIBRARY);
    final @Nullable Path extractedRoot = extractedLibraryDirectory.orElse(null);
    if (extractedRoot == null) {
      final String message = "The VLC zip does not contain nonempty libvlc.dll and libvlccore.dll in the same directory!";
      throw new InstallationException(message);
    }

    IoUtils.moveReplacing(extractedRoot, installDirectory);
  }

  // Extracts the zip. IoUtils.unzip(Path, Path) reports failures unchecked, as a FileOperationException for an archive
  // that cannot be read and as a ZipEntryIntegrityException for an archive that is unsafe, but an installation must
  // fail with an InstallationException like every other installation failure.
  private static void unzip(final Path archive, final Path temporary) {
    try {
      IoUtils.unzip(archive, temporary);
    } catch (final FileOperationException | ZipEntryIntegrityException exception) {
      final String reason = exception.getMessage();
      final String message = "Failed with \"%s\" while extracting the VLC zip!".formatted(reason);
      throw new InstallationException(message, exception);
    }
  }
}
