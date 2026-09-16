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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.ZipEntryIntegrityException;

/**
 * Installs VLC on Windows by extracting the portable zip.
 *
 * <p>The zip contains a single {@code vlc-<version>} directory with {@code libvlc.dll} at its root. The zip is
 * extracted into a temporary directory, that inner directory is moved to the installation directory, and the
 * temporary directory and the zip are deleted. The temporary directory is also deleted when the extraction fails.
 */
public final class WinInstallationStrategy extends ManualInstallationStrategy {

  private static final Pattern LIBRARY = Pattern.compile("libvlc\\.dll");
  private static final String TEMP_DIRECTORY = "vlc-extract";

  /**
   * Constructs a new strategy for the installer.
   *
   * @param installer the installer whose installation directory is used
   */
  public WinInstallationStrategy(final VLCInstaller installer) {
    super(installer);
  }

  /**
   * Looks for {@code libvlc.dll} inside the installation directory.
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
   * Extracts the zip, moves the directory that contains {@code libvlc.dll} to the installation directory, and
   * deletes the zip.
   *
   * @param archive the downloaded zip
   * @return the installation directory, which contains {@code libvlc.dll}
   * @throws IOException if the zip cannot be extracted or does not contain the library
   */
  @Override
  public Path execute(final Path archive) throws IOException {
    Preconditions.checkNotNull(archive, "Archive must not be null");
    final Path installDirectory = this.getInstallDirectory();
    final Path parentOrNull = installDirectory.getParent();
    final Path parent = Objects.requireNonNull(parentOrNull, "The installation directory lies in the installer folder");
    final Path temporary = parent.resolve(TEMP_DIRECTORY);
    deleteRecursively(temporary);
    deleteRecursively(installDirectory);
    try {
      extractInto(archive, temporary, installDirectory);
    } finally {
      deleteRecursively(temporary);
    }
    Files.deleteIfExists(archive);
    return installDirectory;
  }

  private static void extractInto(final Path archive, final Path temporary, final Path installDirectory) throws IOException {
    unzip(archive, temporary);
    final Optional<Path> extractedLibraryDirectory = findLibraryDirectory(temporary, LIBRARY);
    if (extractedLibraryDirectory.isEmpty()) {
      throw new IOException("The VLC zip does not contain libvlc.dll");
    }
    final Path extractedRoot = extractedLibraryDirectory.get();
    Files.move(extractedRoot, installDirectory);
  }

  /**
   * Extracts the zip. {@link IOUtils#unzip(Path, Path)} reports failures unchecked, as an {@link UncheckedIOException}
   * for an archive that cannot be read and as a {@link ZipEntryIntegrityException} for an archive that is unsafe, but an
   * installation must fail with an {@link IOException} like every other installation failure.
   */
  private static void unzip(final Path archive, final Path temporary) throws IOException {
    try {
      IOUtils.unzip(archive, temporary);
    } catch (final UncheckedIOException | ZipEntryIntegrityException exception) {
      final String reason = exception.getMessage();
      throw new IOException("The VLC zip cannot be extracted: " + reason, exception);
    }
  }
}
