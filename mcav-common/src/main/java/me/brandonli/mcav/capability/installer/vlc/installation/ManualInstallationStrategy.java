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
import java.nio.file.Files;
import java.nio.file.Path;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;

/**
 * Abstract class for manual installation strategies.
 */
public abstract class ManualInstallationStrategy implements InstallationStrategy {

  private final VLCInstaller installer;

  /**
   * Constructor for the ManualInstallationStrategy.
   *
   * @param installer the VLCInstaller instance to use
   */
  public ManualInstallationStrategy(final VLCInstaller installer) {
    this.installer = installer;
  }

  /**
   * Deletes the specified file if it exists.
   *
   * @param dmg the path to the file to be deleted
   * @throws IOException if an I/O error occurs
   */
  public void deleteFile(final Path dmg) throws IOException {
    Files.deleteIfExists(dmg);
  }

  /**
   * Gets the VLCInstaller instance.
   *
   * @return the VLCInstaller instance
   */
  public VLCInstaller getInstaller() {
    return this.installer;
  }
}
