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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.runtime.CommandTask;

/**
 * {@inheritDoc}
 * <p>
 * OSXInstallationStrategy is a concrete implementation of the InstallationStrategy interface for macOS platforms.
 */
public final class OSXInstallationStrategy extends ManualInstallationStrategy {

  private static final String VLC_APP = "VLC.app";

  /**
   * Constructs a new OSXInstallationStrategy with the specified VLCInstaller.
   *
   * @param installer the VLCInstaller to use for this strategy
   */
  public OSXInstallationStrategy(final VLCInstaller installer) {
    super(installer);
  }

  /**
   * {@inheritDoc}
   * <p>
   * This method checks for the existence of VLC binaries in the expected directories. It first checks if the "VLC.app"
   * directory exists, and if not, it checks the "Contents/MacOS/lib" directory.
   */
  @Override
  public Optional<Path> getInstalledPath() {
    final VLCInstaller installer = this.getInstaller();
    final Path path = installer.getPath();
    final Path parent = requireNonNull(path.getParent());
    final Path app = parent.resolve(VLC_APP);
    final Path contents = app.resolve("Contents");
    final Path macos = contents.resolve("MacOS");
    final Path lib = macos.resolve("lib");
    return Files.exists(lib) ? Optional.of(lib) : Optional.empty();
  }

  /**
   * {@inheritDoc}
   * <p>
   * This method installs VLC binaries by mounting the DMG file, copying the VLC application, setting permissions, and
   * unmounting the disk image.
   *
   * @throws IOException if an I/O error occurs during installation
   */
  @Override
  public Path execute() throws IOException {
    final VLCInstaller installer = this.getInstaller();
    final Path disk = Path.of("/Volumes/VLC media player");
    final String raw = disk.toString();

    final Path appFolder = installer.getPath();
    final String appFolderRaw = appFolder.toString();

    final Path parent = requireNonNull(appFolder.getParent());
    final Path app = parent.resolve(VLC_APP);
    final String appRaw = app.toString();

    final Path dmg = installer.getPath();
    final String dmgRaw = dmg.toString();

    final Path src = disk.resolve(VLC_APP);
    final String srcRaw = src + "/";
    IOUtils.createDirectoryIfNotExists(app);

    this.runNativeProcess("hdiutil", "attach", dmgRaw);
    this.runNativeProcess("mkdir", "-p", appRaw);
    this.runNativeProcess("cp", "-R", srcRaw, appRaw);
    this.runNativeProcess("chmod", "-R", "755", appRaw);
    this.runNativeProcess("diskutil", "unmount", raw);
    this.runNativeProcess("rm", "-rf", appFolderRaw);
    installer.writePathToConfig(app);

    final Path contents = app.resolve("Contents");
    final Path macos = contents.resolve("MacOS");
    return macos.resolve("lib");
  }

  private void runNativeProcess(final String... arguments) throws IOException {
    new CommandTask(arguments, true);
  }
}
