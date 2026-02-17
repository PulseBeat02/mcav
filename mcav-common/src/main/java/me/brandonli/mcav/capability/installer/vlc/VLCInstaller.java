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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import me.brandonli.mcav.capability.installer.AbstractInstaller;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.vlc.installation.InstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.LinuxInstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.OSXInstallationStrategy;
import me.brandonli.mcav.capability.installer.vlc.installation.WinInstallationStrategy;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;

/**
 * VLCInstaller is a concrete implementation of the AbstractInstaller class for installing VLC media player.
 * <p>
 * This class provides methods to download and install VLC binaries for different operating systems and architectures.
 * It also provides methods to check for pre-existing installations and execute the installation process.
 *
 * @see AbstractInstaller
 */
public final class VLCInstaller extends AbstractInstaller {

  /**
   * The version of VLC that this installer supports.
   */
  public static final String VERSION = "3.0.23";

  private static final Download[] DOWNLOADS = ReleasePackageManager.readVLCDownloadsFromJsonResource("vlc.json");

  VLCInstaller(final Path folder) {
    super(folder, "vlc", DOWNLOADS);
  }

  VLCInstaller() {
    super("vlc", DOWNLOADS);
  }

  /**
   * Constructs a new VLCInstaller with the specified directory for the executable.
   *
   * @param executable directory
   * @return new VLCInstaller
   */
  public static VLCInstaller create(final Path executable) {
    return new VLCInstaller(executable);
  }

  /**
   * Constructs a new VLCInstaller with the default directory for the executable.
   *
   * <p>For Windows, it is C:/Program Files/static-emc Otherwise, it is [user home
   * directory]/static-emc
   *
   * @return new VLCInstaller
   */
  public static VLCInstaller create() {
    return new VLCInstaller();
  }

  /**
   * Downloads the VLC binaries for the current operating system and architecture. This method will check if the
   * current platform is supported.
   *
   * @param chmod whether chmod 777 should be applied (if not windows)
   * @return the path to the installed VLC binaries
   * @throws IOException if an I/O error occurs during the download
   */
  @Override
  public Path download(final boolean chmod) throws IOException {
    final InstallationStrategy strategy = this.getStrategy();
    final Optional<Path> optional = strategy.getInstalledPath();
    if (optional.isPresent()) {
      return optional.get();
    }

    super.download(chmod);

    return strategy.execute();
  }

  private InstallationStrategy getStrategy() {
    final OS os = OSUtils.getOS();
    if (os == OS.MAC) {
      return new OSXInstallationStrategy(this);
    } else if (os == OS.WINDOWS) {
      return new WinInstallationStrategy(this);
    } else if (os == OS.LINUX) {
      return new LinuxInstallationStrategy(this);
    } else {
      throw new UnsupportedOperatingSystemException("Operating System not Supported!");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isFolder() {
    return true;
  }
}
