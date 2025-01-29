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

import java.io.IOException;
import java.nio.file.Path;

/**
 * Represents an installer for a binary.
 */
public interface Installer {
  /**
   * Downloads the binary into the specified directory with the file name.
   *
   * @param chmod whether chmod 777 should be applied (if not windows)
   * @return the path of the download executable
   * @throws IOException if an issue occurred during file creation, downloading, or renaming.
   */
  Path download(final boolean chmod) throws IOException;

  /**
   * Returns whether the operating system is *most likely* supported.
   *
   * @return whether the current operating system is supported or not
   */
  boolean isSupported();
}
