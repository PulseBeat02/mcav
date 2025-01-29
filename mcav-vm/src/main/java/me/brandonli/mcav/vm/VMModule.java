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

import me.brandonli.mcav.module.MCAVModule;

/**
 * The entry point for the VM module.
 */
public final class VMModule implements MCAVModule {

  private boolean isQemuInstalled;

  VMModule() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    // this.installQemu();
    final ExecutableFinder finder = new ExecutableFinder();
    this.isQemuInstalled = finder.find("qemu-system-x86_64") != null;
  }

  //  private void installQemu() {
  //    try {
  //      final QemuInstaller installer = QemuInstaller.create();
  //      if (!installer.isSupported()) {
  //        this.capabilities.remove(Capability.QEMU);
  //        LOGGER.info("QEMU is not enabled, skipping installation.");
  //        return;
  //      }
  //      LOGGER.info("Installing QEMU...");
  //      final long start = System.currentTimeMillis();
  //      installer.download(true);
  //      final long end = System.currentTimeMillis();
  //      LOGGER.info("QEMU installation took {} ms", end - start);
  //    } catch (final IOException e) {
  //      this.capabilities.remove(Capability.QEMU);
  //      final String msg = e.getMessage();
  //      if (msg != null) {
  //        LOGGER.error(msg);
  //      }
  //      LOGGER.info("Failed to install QEMU, skipping installation.");
  //    }
  //  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void stop() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getModuleName() {
    return "vm";
  }

  /**
   * Checks if QEMU is installed.
   *
   * @return true if QEMU is installed, false otherwise
   */
  public boolean isQemuInstalled() {
    return this.isQemuInstalled;
  }
}
