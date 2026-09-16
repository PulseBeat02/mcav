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
package me.brandonli.mcav.vm;

import com.google.common.annotations.VisibleForTesting;
import java.nio.file.Path;
import java.util.Optional;
import me.brandonli.mcav.module.MCAVModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the virtual machine backend. Install it with {@code MCAV.api().install(VMModule.class)}.
 *
 * <p>QEMU cannot be installed by the library without administrator rights, so it must be installed on the
 * machine: through the package manager on Linux, Homebrew on macOS, or the installer from
 * <a href="https://qemu.weka.io/">qemu.weka.io</a> on Windows. The {@code qemu-system-*} programs must be on the
 * {@code PATH} of the Java process, or in a folder {@link ExecutableFinder} also searches: {@code /usr/local/bin}
 * and {@code /usr/bin} on Linux, the Homebrew folders and {@code /etc/paths} on macOS, and
 * {@code C:\Program Files\qemu} on Windows. Services often run with a shorter {@code PATH} than a login shell, so a
 * QEMU installed elsewhere must be added to the {@code PATH} of the service. Starting the module only checks whether
 * QEMU is available; {@link VMPlayer#start(VMSettings, VMPlayer.Architecture, VMConfiguration)} fails with an
 * {@link ExecutableNotInPathException} otherwise.
 */
public final class VMModule implements MCAVModule {

  private static final Logger LOGGER = LoggerFactory.getLogger(VMModule.class);

  private final ExecutableFinder finder;
  private volatile boolean qemuInstalled;

  /**
   * Constructs the module. The module loader creates it for you.
   */
  public VMModule() {
    this(new ExecutableFinder());
  }

  /**
   * Constructs a module that looks for QEMU with the given finder.
   *
   * @param finder finds the QEMU program
   */
  @VisibleForTesting
  VMModule(final ExecutableFinder finder) {
    this.finder = finder;
  }

  @Override
  public void start() {
    final String command = VMPlayer.Architecture.X86_64.getCommand();
    final Optional<Path> qemu = this.finder.find(command);
    this.qemuInstalled = qemu.isPresent();
    if (qemu.isPresent()) {
      final Path path = qemu.get();
      LOGGER.info("Found QEMU at {}", path);
    } else {
      LOGGER.warn("{} is not on the PATH or in the usual install folders, virtual machines cannot be started", command);
    }
  }

  @Override
  public void stop() {
    // nothing to release
  }

  @Override
  public String getModuleName() {
    return "vm";
  }

  /**
   * Checks whether QEMU for x86-64 guests was found when the module started.
   *
   * @return true if QEMU is available
   */
  public boolean isQemuInstalled() {
    return this.qemuInstalled;
  }
}
