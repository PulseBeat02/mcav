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
 * QemuInstaller is a specialized installer for managing QEMU binaries. Doesn't even work yet.
 */
@Deprecated
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
   *
   * @param folder the folder where QEMU binaries will be installed
   * @return a new instance of QemuInstaller
   */
  public static QemuInstaller create(final Path folder) {
    return new QemuInstaller(folder);
  }

  /**
   * Creates a new QemuInstaller instance with the default folder.
   *
   * @return a new instance of QemuInstaller
   */
  public static QemuInstaller create() {
    return new QemuInstaller();
  }
}
