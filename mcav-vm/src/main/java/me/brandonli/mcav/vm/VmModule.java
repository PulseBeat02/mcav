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
 * Registers the virtual machine backend. The module needs no settings, so it only has to be installed:
 *
 * {@snippet :
 * final MCAVApi api = MCAV.api();
 * api.install(VmModule.class);
 * }
 *
 * <p>MCAV uses the QEMU of the machine when it finds one. The {@code qemu-system-*} programs must be on the
 * {@code PATH} of the Java process, or in a folder {@link ExecutableFinder} also searches: {@code /usr/local/bin}
 * and {@code /usr/bin} on Linux and FreeBSD, the Homebrew folders and {@code /etc/paths} on macOS, and
 * {@code C:\Program Files\qemu} on Windows. Services often run with a shorter {@code PATH} than a login shell, so a
 * QEMU installed elsewhere must be added to the {@code PATH} of the service. When the machine has none, MCAV installs
 * the Debian 12 packages of the QEMU of the guest into its cache folder at the first start, on x86-64 and ARM64 Linux
 * only and without administrator rights. On other platforms, install QEMU yourself: through the package manager,
 * Homebrew on macOS, or the installer from <a href="https://qemu.weka.io/">qemu.weka.io</a> on Windows. Starting the
 * module only checks whether QEMU is available; {@link VmPlayer#start(VmSettings, VmPlayer.Architecture, VmConfiguration)}
 * fails with an {@link ExecutableNotInPathException} when QEMU is neither found nor installable.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class VmModule implements MCAVModule {

  private static final Logger LOGGER = LoggerFactory.getLogger(VmModule.class);

  private static final String MODULE_NAME = "vm";

  private final ExecutableFinder finder;
  private final QemuInstaller installer;
  private final QemuProcessRecords records;

  private volatile boolean qemuInstalled;
  private volatile boolean qemuAvailable;

  /**
   * Creates the module; the module loader calls this for you.
   *
   * @since 1.0.0
   */
  public VmModule() {
    final ExecutableFinder finder = new ExecutableFinder();
    final QemuInstaller installer = new QemuInstaller();
    final QemuProcessRecords records = QemuProcessRecords.ofUser();
    this(finder, installer, records);
  }

  @VisibleForTesting
  VmModule(final ExecutableFinder finder, final QemuInstaller installer, final QemuProcessRecords records) {
    this.finder = finder;
    this.installer = installer;
    this.records = records;
  }

  /**
   * Checks for {@code qemu-system-x86_64} and records and logs whether it was found. This creates no virtual
   * machines and does not check executables for the other guest architectures. Missing QEMU is logged rather
   * than thrown here; each player resolves its selected executable at startup. When MCAV can install QEMU on this
   * platform, the log says that the first start installs it, and {@link #isQemuAvailable()} returns true.
   *
   * <p>First, it stops every QEMU process that a server killed before it could release its players left running,
   * which would still hold its memory and its VNC port; each one is logged.
   *
   * @since 1.0.0
   */
  @Override
  public void start() {
    this.records.reap();

    final String command = VmPlayer.Architecture.X86_64.getCommand();
    final Optional<Path> qemu = this.finder.find(command);
    final boolean installed = qemu.isPresent();
    this.qemuInstalled = installed;
    this.qemuAvailable = installed || this.installer.isSupported(VmPlayer.Architecture.X86_64);

    if (!installed) {
      this.logMissingQemu(command);
      return;
    }

    final Path path = qemu.orElseThrow();
    LOGGER.info("QEMU at {} is available for x86-64 guests.", path);
  }

  private void logMissingQemu(final String command) {
    if (this.qemuAvailable) {
      LOGGER.info(
        "QEMU program {} is not on the PATH, so MCAV installs it from Debian packages when the first virtual machine starts.",
        command
      );
      return;
    }

    LOGGER.warn("QEMU program {} is not on the PATH or in the usual install folders, so virtual machines cannot be started.", command);
  }

  /**
   * Stops the module without releasing any players or changing the recorded availability result.
   * Call {@link VmPlayer#release()} on every owned player before unloading the backend.
   *
   * @since 1.0.0
   */
  @Override
  public void stop() {}

  /**
   * Returns the module identifier.
   *
   * @return {@code vm}
   * @since 1.0.0
   */
  @Override
  public String getModuleName() {
    return MODULE_NAME;
  }

  /**
   * Returns whether QEMU for x86-64 guests was found when the module started.
   *
   * @return the result of the last module start, initially false; this does not recheck the executable
   * @since 1.0.0
   */
  public boolean isQemuInstalled() {
    return this.qemuInstalled;
  }

  /**
   * Returns whether virtual machines can start: QEMU for x86-64 guests was found when the module started, or MCAV can
   * install it on this platform when the first virtual machine starts.
   *
   * @return the result of the last module start, initially false; this does not recheck the executable
   * @since 1.0.0
   */
  public boolean isQemuAvailable() {
    return this.qemuAvailable;
  }
}
