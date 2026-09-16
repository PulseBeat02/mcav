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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Extracts a downloaded VLC archive into a usable installation. Every operating system ships VLC in a different
 * archive format, so each has its own strategy.
 *
 * @see LinuxInstallationStrategy
 * @see WinInstallationStrategy
 * @see OSXInstallationStrategy
 */
public interface InstallationStrategy {
  /**
   * Looks for an installation created by a previous run.
   *
   * @return the directory that contains the VLC libraries, or empty if VLC has not been installed
   * @throws IOException if the installation directory cannot be searched
   */
  Optional<Path> getInstalledPath() throws IOException;

  /**
   * Extracts the downloaded archive into the installation directory and deletes the archive.
   *
   * @param archive the downloaded archive
   * @return the directory that contains the VLC libraries
   * @throws IOException if the archive cannot be extracted
   */
  Path execute(final Path archive) throws IOException;
}
