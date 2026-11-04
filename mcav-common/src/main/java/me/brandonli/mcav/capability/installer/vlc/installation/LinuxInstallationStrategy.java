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

import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.vlc.VlcInstaller;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Installs VLC on Linux by extracting an AppImage.
 *
 * <p>The AppImage is extracted with its built-in {@code --appimage-extract} option, which needs no FUSE support
 * and writes a {@code squashfs-root} directory next to the AppImage. That directory is moved to the installation
 * directory, and the directory containing {@code libvlccore.so} inside it is returned.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class LinuxInstallationStrategy extends ManualInstallationStrategy {

  private static final Pattern CORE_LIBRARY = Pattern.compile("libvlccore\\.so(?:\\.\\d+)*");
  private static final Pattern LIBRARY = Pattern.compile("libvlc\\.so(?:\\.\\d+)*");

  private static final String EXTRACTED_DIRECTORY = "squashfs-root";
  private static final String EXTRACT_OPTION = "--appimage-extract";

  /**
   * Creates a new strategy for the installer.
   *
   * @param installer the installer whose installation directory is used
   * @throws NullPointerException if {@code installer} is null
   * @since 1.0.0
   */
  public LinuxInstallationStrategy(final VlcInstaller installer) {
    super(installer);
  }

  LinuxInstallationStrategy(final VlcInstaller installer, final ProcessRunner processRunner) {
    super(installer, processRunner);
  }

  /**
   * Looks for nonempty {@code libvlc.so} and {@code libvlccore.so} in the same installation directory.
   *
   * @return the directory that contains the library, or empty if VLC has not been installed
   * @throws InstallationException if the installation directory cannot be searched
   * @since 1.0.0
   */
  @Override
  public Optional<Path> getInstalledPath() {
    final Path installDirectory = this.getInstallDirectory();
    return this.findLibraryDirectory(installDirectory, LIBRARY, CORE_LIBRARY);
  }

  /**
   * Extracts the AppImage, moves the extracted tree to the installation directory, and deletes the AppImage.
   *
   * @param archive the downloaded AppImage
   * @return the directory that contains {@code libvlccore.so}
   * @throws InstallationException  if the AppImage cannot be extracted or does not contain the library
   * @throws FileOperationException if an earlier installation, the extracted tree or the AppImage cannot be moved or
   *                                deleted
   * @throws NullPointerException   if {@code archive} is null
   * @since 1.0.0
   */
  @Override
  public Path execute(final Path archive) {
    Preconditions.checkNotNull(archive, "Archive must not be null!");

    final Path absoluteArchive = archive.toAbsolutePath();
    final Path parentOrNull = absoluteArchive.getParent();
    final Path workingDirectory = requireNonNull(parentOrNull, "A downloaded file lies in a directory!");
    final Path extracted = workingDirectory.resolve(EXTRACTED_DIRECTORY);
    final Path installDirectory = this.getInstallDirectory();
    IoUtils.deleteRecursively(extracted);
    IoUtils.deleteRecursively(installDirectory);
    this.extractAppImage(absoluteArchive, workingDirectory, extracted);
    IoUtils.moveReplacing(extracted, installDirectory);
    this.deleteFile(absoluteArchive);

    final Optional<Path> libraryDirectory = this.findLibraryDirectory(installDirectory, LIBRARY, CORE_LIBRARY);
    final @Nullable Path directory = libraryDirectory.orElse(null);
    if (directory == null) {
      final String message = "The extracted VLC AppImage does not contain nonempty libvlc.so and libvlccore.so in the same directory!";
      throw new InstallationException(message);
    }

    return directory;
  }

  private void extractAppImage(final Path archive, final Path workingDirectory, final Path extracted) {
    IoUtils.markExecutable(archive);
    final String rawArchive = archive.toString();
    this.runProcess(workingDirectory, rawArchive, EXTRACT_OPTION);

    final boolean extractedExists = Files.isDirectory(extracted);
    if (!extractedExists) {
      final String message = "Directory %s was not produced by extracting the VLC AppImage!".formatted(extracted);
      throw new InstallationException(message);
    }
  }
}
