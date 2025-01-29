/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.utils.IOUtils;

/**
 * {@inheritDoc}
 *
 * <p>
 * WinInstallationStrategy is a concrete implementation of the InstallationStrategy interface for Windows platforms.
 */
public final class WinInstallationStrategy extends ManualInstallationStrategy {

  private static final String VLC_TEMP = "temp-vlc";
  private static final String VLC_APP = "vlc";

  /**
   * Constructs a new WinInstallationStrategy with the specified VLCInstaller.
   *
   * @param installer the VLCInstaller to use for this strategy
   */
  public WinInstallationStrategy(final VLCInstaller installer) {
    super(installer);
  }

  /**
   * {@inheritDoc}
   *
   * <p>
   * This method checks for the existence of VLC binaries in the expected directories. It first checks if the "vlc" directory
   * exists, and if not, it returns something empty.
   */
  @Override
  public Optional<Path> getInstalledPath() {
    final VLCInstaller installer = this.getInstaller();
    final Path path = installer.getPath();
    final Path parent = requireNonNull(path.getParent());
    final Path vlc = parent.resolve(VLC_APP);
    return Files.exists(vlc) ? Optional.of(vlc) : Optional.empty();
  }

  /**
   * {@inheritDoc}
   *
   * <p>
   * This method installs VLC binaries by extracting the zip file, deleting the original zip file, and moving the extracted
   * files to the target directory.
   *
   * @throws IOException if an I/O error occurs during installation
   */
  @Override
  public Path execute() throws IOException {
    final VLCInstaller installer = this.getInstaller();
    final Path zip = installer.getPath();
    final Path parent = requireNonNull(zip.getParent());
    final Path temp = parent.resolve(VLC_TEMP);
    final Path path = parent.resolve(VLC_APP);
    IOUtils.unzip(zip, temp);

    this.deleteFile(zip);
    this.moveFiles(temp, path);
    this.deleteFile(temp);

    return path;
  }

  private void moveFiles(final Path temp, final Path dest) throws IOException {
    final String name = "vlc-" + VLCInstaller.VERSION;
    final Path resolve = temp.resolve(name);
    Files.move(resolve, dest);
  }
}
