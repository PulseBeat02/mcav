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
package me.brandonli.mcav.vm;

import java.nio.file.Path;
import me.brandonli.mcav.capability.installer.AbstractInstaller;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.utils.IOUtils;

/**
 * The QemuInstaller class is responsible for managing the installation of the QEMU static binaries
 * for different architectures and operating systems. It extends the AbstractInstaller class and
 * provides specific configurations for downloading QEMU binaries.
 * <p>
 * This class is immutable and thread-safe. It defines pre-configured download links, along with
 * their respective checksums, for QEMU binaries targeting different architectures and platforms.
 * <p>
 * The following combinations are supported:
 * - Linux OS with x86 architecture (32-bit and 64-bit).
 * - Linux OS with ARM architecture (32-bit and 64-bit).
 */
public final class QemuInstaller extends AbstractInstaller {

  private static final Download[] DOWNLOADS = IOUtils.readDownloadsFromJsonResource("qemu.json");

  QemuInstaller(final Path folder) {
    super(folder, "qemu", DOWNLOADS);
  }

  QemuInstaller() {
    super("qemu", DOWNLOADS);
  }

  /**
   * Creates a new QemuInstaller instance with the specified folder.
   * <p>
   * This method is a factory method that creates a new instance of the QemuInstaller class. It
   * initializes the installer with the specified folder and sets up the download configurations for
   * QEMU binaries.
   *
   * @param folder the directory in which the QEMU binaries will be managed
   * @return a new instance of QemuInstaller
   */
  public static QemuInstaller create(final Path folder) {
    return new QemuInstaller(folder);
  }

  /**
   * Creates a new QemuInstaller instance with the default folder.
   * <p>
   * This method is a factory method that creates a new instance of the QemuInstaller class. It
   * initializes the installer with the default folder and sets up the download configurations for
   * QEMU binaries.
   *
   * @return a new instance of QemuInstaller
   */
  public static QemuInstaller create() {
    return new QemuInstaller();
  }
}
