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
package me.brandonli.mcav.capability.installer;

import java.nio.file.Path;
import java.util.Optional;
import me.brandonli.mcav.utils.http.DownloadException;

/**
 * Downloads and installs an external program the library depends on, such as VLC or yt-dlp.
 *
 * <p>Installers are idempotent: once a program is installed, calling {@link #download(FileMode)} again returns the
 * existing installation immediately. Installations live in the cache folder of the library and are remembered in
 * a small configuration file, so they survive restarts.
 *
 * <p>Implementations in this library serialize the downloads of each installer, see {@link AbstractInstaller}.
 *
 * @since 1.0.0
 */
public interface Installer {
  /**
   * Installs the program, or returns the existing installation if it was installed before. This method blocks
   * while the program is downloaded, which can take a while for large programs such as VLC.
   *
   * @param mode {@link FileMode#EXECUTABLE} for programs that are run directly, which has no effect on Windows
   * @return the installation path chosen by the implementation: an executable file or an extracted directory
   * @throws InstallationException if the program cannot be installed on this platform, or its files cannot be written
   * @throws DownloadException     if the download fails or its hash does not match
   * @since 1.0.0
   */
  Path download(final FileMode mode);

  /**
   * Returns whether a download exists for the current platform. Unsupported platforms can still use the program if
   * it is installed on the system by other means.
   *
   * @return true if the program can be installed on this platform
   * @since 1.0.0
   */
  boolean isSupported();

  /**
   * Looks for an installation made by an earlier run, without downloading anything or contacting any server. Use it
   * to prefer an existing installation over {@link #isSupported()}, which may have to ask a web service for the
   * download links. The default implementation finds nothing.
   *
   * @return the path of the existing installation, or empty if the program has not been installed
   * @throws InstallationException if the installation folder cannot be searched
   * @since 1.0.0
   */
  default Optional<Path> findInstallation() {
    return Optional.empty();
  }

  /**
   * Returns the path the program is installed to, whether or not it has been installed yet.
   *
   * @return the installation path
   * @since 1.0.0
   */
  Path getPath();
}
