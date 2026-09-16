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
package me.brandonli.mcav.capability.installer.vlc;

import java.nio.file.Path;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Finds the native VLC libraries and loads them into vlcj.
 */
interface VLCDiscovery {
  /**
   * Looks for a VLC installation on the system and loads it when one is found.
   *
   * @return true if the libraries were found and loaded, including when vlcj had loaded them before
   */
  boolean discoverSystemInstallation();

  /**
   * Gets the directory the last successful discovery loaded the libraries from.
   *
   * @return the directory, or {@code null} if vlcj had loaded the libraries earlier without reporting where from
   */
  @Nullable Path getDiscoveredPath();

  /**
   * Loads the libraries of a VLC installation whose location is known.
   *
   * @param libraryDirectory the directory that contains the libraries
   * @return true if the libraries were loaded
   */
  boolean loadInstalledLibraries(Path libraryDirectory);
}
