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
 * Represents a strategy for installing and setting up VLC media player binaries. Each strategy has its own unique
 * implementation per platform to properly load all binaries and libraries in place.
 *
 * @see LinuxInstallationStrategy
 * @see WinInstallationStrategy
 * @see OSXInstallationStrategy
 */
public interface InstallationStrategy {
  /**
   * Attempts to locate any pre-existing VLC binaries. This will check the directory to see if the binaries have been
   * installed.
   *
   * @return an Optional containing the path to the installed VLC binaries if found, otherwise empty
   */
  Optional<Path> getInstalledPath();

  /**
   * Attempts to download and install VLC binaries for the current platform. This method will also set up any paths.
   *
   * @return the path to the installed VLC binaries
   * @throws IOException if an error occurs during the installation process
   */
  Path execute() throws IOException;
}
