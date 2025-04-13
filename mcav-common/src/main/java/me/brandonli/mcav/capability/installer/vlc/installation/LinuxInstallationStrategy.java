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
import me.brandonli.mcav.utils.runtime.CommandTask;

/**
 * {@inheritDoc}
 * <p>
 * LinuxInstallationStrategy is a concrete implementation of the InstallationStrategy interface for Linux platforms.
 */
public final class LinuxInstallationStrategy extends ManualInstallationStrategy {

  /**
   * Constructs a new LinuxInstallationStrategy with the specified VLCInstaller.
   *
   * @param installer the VLCInstaller to use for this strategy
   */
  public LinuxInstallationStrategy(final VLCInstaller installer) {
    super(installer);
  }

  /**
   * {@inheritDoc}
   * <p>
   * This method checks for the existence of VLC binaries in the expected directories. It first checks if the ".junest"
   * directory exists, and if not, it checks the "usr/lib/i386-linux-gnu" directory. Otherwise, it checks the "usr/lib"
   * directory.
   */
  @Override
  public Optional<Path> getInstalledPath() {
    final VLCInstaller installer = this.getInstaller();
    final Path path = installer.getPath();
    final Path junest = path.resolve(".junest");
    if (Files.notExists(junest)) {
      final Path usr = path.resolve("usr");
      final Path lib = usr.resolve("lib");
      final Path folder = lib.resolve("i386-linux-gnu");
      return Files.exists(folder) ? Optional.of(folder) : Optional.empty();
    } else {
      final Path usr = junest.resolve("usr");
      final Path folder = usr.resolve("lib");
      return Files.exists(folder) ? Optional.of(folder) : Optional.empty();
    }
  }

  /**
   * {@inheritDoc}
   * <p>
   * This method executes the installation process for VLC binaries on Linux platforms. It extracts the AppImage, copies
   * the necessary files, and loads the required libraries.
   */
  @Override
  public Path execute() throws IOException {
    final VLCInstaller installer = this.getInstaller();
    final Path appImage = installer.getPath();
    final Path parent = requireNonNull(appImage.getParent());
    final Path folder = parent.resolve("vlc-junest");
    Files.createDirectories(folder);

    final String rawAppImage = appImage.toString();
    final String rawFolder = folder.toString();
    this.runNativeProcess("chmod", "+x", rawAppImage);
    this.runNativeProcess(rawAppImage, "--appimage-extract");
    this.runNativeProcess("cp", "-a", "squashfs-root/.", rawFolder);
    this.runNativeProcess("rm", "-rf", "squashfs-root");
    this.runNativeProcess("rm", "-rf", rawAppImage);

    final Path junest = folder.resolve(".junest");
    if (Files.notExists(junest)) {
      final Path usr = folder.resolve("usr");
      final Path lib = usr.resolve("lib");
      final Path i386 = lib.resolve("i386-linux-gnu");
      final Path core = i386.resolve("libvlccore.so.9.0.1");
      final String raw = core.toString();
      System.load(raw);
      return i386;
    } else {
      final Path usr = junest.resolve("usr");
      final Path lib = usr.resolve("lib");
      final Path core = lib.resolve("libvlccore.so.9.0.1");
      final String raw = core.toString();
      System.load(raw);
      return usr.resolve("lib");
    }
  }

  private void runNativeProcess(final String... arguments) throws IOException {
    final CommandTask task = new CommandTask(arguments, true);
    final Process process = task.getProcess();
    try {
      process.waitFor();
    } catch (final InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new AssertionError(e);
    }
  }
}
