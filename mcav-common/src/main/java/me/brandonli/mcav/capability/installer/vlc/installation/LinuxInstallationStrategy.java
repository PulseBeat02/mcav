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
import me.brandonli.mcav.utils.IOUtils;

/**
 * Installs VLC on Linux by extracting an AppImage.
 *
 * <p>The AppImage is extracted with its built-in {@code --appimage-extract} option, which needs no FUSE support
 * and writes a {@code squashfs-root} directory next to the AppImage. That directory is moved to the installation
 * directory, and the directory containing {@code libvlccore.so} inside it is returned.
 */
public final class LinuxInstallationStrategy extends ManualInstallationStrategy {

  private static final Pattern CORE_LIBRARY = Pattern.compile("libvlccore\\.so(?:\\.\\d+)*");
  private static final Pattern LIBRARY = Pattern.compile("libvlc\\.so(?:\\.\\d+)*");
  private static final String EXTRACTED_DIRECTORY = "squashfs-root";
  private static final String EXTRACT_OPTION = "--appimage-extract";

  /**
   * Constructs a new strategy for the installer.
   *
   * @param installer the installer whose installation directory is used
   */
  public LinuxInstallationStrategy(final VLCInstaller installer) {
    super(installer);
  }

  /**
   * Constructs a new strategy that runs the AppImage through the specified runner.
   *
   * @param installer     the installer whose installation directory is used
   * @param processRunner runs the AppImage extraction
   */
  LinuxInstallationStrategy(final VLCInstaller installer, final ProcessRunner processRunner) {
    super(installer, processRunner);
  }

  /**
   * Looks for nonempty {@code libvlc.so} and {@code libvlccore.so} in the same installation directory.
   *
   * @return the directory that contains the library, or empty if VLC has not been installed
   * @throws IOException if the installation directory cannot be searched
   */
  @Override
  public Optional<Path> getInstalledPath() throws IOException {
    final Path installDirectory = this.getInstallDirectory();
    return findLibraryDirectory(installDirectory, LIBRARY, CORE_LIBRARY);
  }

  /**
   * Extracts the AppImage, moves the extracted tree to the installation directory, and deletes the AppImage.
   *
   * @param archive the downloaded AppImage
   * @return the directory that contains {@code libvlccore.so}
   * @throws IOException if the AppImage cannot be extracted or does not contain the library
   */
  @Override
  public Path execute(final Path archive) throws IOException {
    Preconditions.checkNotNull(archive, "Archive must not be null");
    final Path absoluteArchive = archive.toAbsolutePath();
    final Path parentOrNull = absoluteArchive.getParent();
    final Path workingDirectory = Objects.requireNonNull(parentOrNull, "A downloaded file lies in a directory");
    final Path extracted = workingDirectory.resolve(EXTRACTED_DIRECTORY);
    final Path installDirectory = this.getInstallDirectory();
    deleteRecursively(extracted);
    deleteRecursively(installDirectory);
    this.extractAppImage(absoluteArchive, workingDirectory, extracted);
    Files.move(extracted, installDirectory);
    Files.deleteIfExists(absoluteArchive);
    final Optional<Path> libraryDirectory = findLibraryDirectory(installDirectory, LIBRARY, CORE_LIBRARY);
    if (libraryDirectory.isEmpty()) {
      throw new IOException("The extracted VLC AppImage does not contain nonempty libvlc.so and libvlccore.so in the same directory");
    }
    return libraryDirectory.get();
  }

  private void extractAppImage(final Path archive, final Path workingDirectory, final Path extracted) throws IOException {
    IOUtils.markExecutable(archive);
    final String rawArchive = archive.toString();
    this.runProcess(workingDirectory, rawArchive, EXTRACT_OPTION);
    final boolean extractedExists = Files.isDirectory(extracted);
    if (!extractedExists) {
      throw new IOException("Extracting the VLC AppImage did not produce " + extracted);
    }
  }
}
