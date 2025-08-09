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
package me.brandonli.mcav.dependency;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.natives.NativeLoadingException;
import me.brandonli.mcav.utils.natives.NativeUtils;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.Platform;
import me.brandonli.mcav.utils.runtime.CommandTask;
import org.bytedeco.javacpp.Loader;

/**
 * A class that installs Nix on the system by downloading and executing a script. Installs proper dependencies needed
 * for JavaCV for Linux systems.
 */
public final class PackageInstaller {

  private static final String APT_SCRIPT_NAME = "pget";
  private static final String PATCH_ELF_SCRIPT_NAME = "patch-elf";

  private static final Set<String> APT_LIBRARIES = Set.of("libgtk2.0-0", "libopencv-dev", "ffmpeg", "vlc", "qemu-user-static");

  /**
   * Default constructor for NixInstaller.
   */
  public PackageInstaller() {
    // no-op
  }

  /**
   * Installs Nix on the system by downloading and executing a script.
   */
  public void install() {
    final Platform platform = Platform.getCurrentPlatform();
    final OS os = platform.getOS();
    if (os != OS.LINUX || !NativeUtils.isDebianBased()) {
      return;
    }

    try {
      this.installDependencies();
      // this.loadLibraries();
    } catch (final IOException e) {
      throw new NativeLoadingException(e.getMessage(), e);
    }
  }

  private String loadLibraries() throws IOException {
    final Path cache = IOUtils.getCachedFolder();
    final Path script = cache.resolve(PATCH_ELF_SCRIPT_NAME);
    try (final InputStream stream = IOUtils.getResourceAsInputStream(PATCH_ELF_SCRIPT_NAME)) {
      Files.copy(stream, script, StandardCopyOption.REPLACE_EXISTING);
    }
    this.changePermissions(script);

    final Path absolute = script.toAbsolutePath();
    final String raw = absolute.toString();
    final String[] args = new String[] { raw };
    final CommandTask task = new CommandTask(args, true);
    final Process process = task.getProcess();
    try {
      process.waitFor();
    } catch (final InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new NativeLoadingException(e.getMessage(), e);
    }

    final String homePath = System.getProperty("user.home");
    final Path home = Path.of(homePath);
    final Path apt = home.resolve(".apt");
    final Path usr = apt.resolve("usr");
    final Path lib = usr.resolve("lib");
    final String arch = com.sun.jna.Platform.ARCH;
    final String name =
      switch (arch) {
        case "aarch64" -> "aarch64-linux-gnu";
        case "armv7l" -> "arm-linux-gnueabihf";
        case "x86_64" -> "x86_64-linux-gnu";
        case "i386" -> "i386-linux-gnu";
        default -> arch + "-linux-gnu";
      };
    final Path target = lib.resolve(name);
    final Path absoluteTarget = target.toAbsolutePath();
    // liblapack
    // libblas
    final List<String> libraries = List.of("libopencv_highgui.so.4.6.0");

    for (final String library : libraries) {
      final Path libPath = absoluteTarget.resolve(library);
      final String toRaw = libPath.toString();
      this.changePermissions(libPath);
      Loader.loadGlobal(toRaw);
    }

    return absoluteTarget.toString();
  }

  private void installDependencies() throws IOException {
    final Path cache = IOUtils.getCachedFolder();
    final Path script = cache.resolve(APT_SCRIPT_NAME);
    try (final InputStream stream = IOUtils.getResourceAsInputStream(APT_SCRIPT_NAME)) {
      Files.copy(stream, script, StandardCopyOption.REPLACE_EXISTING);
    }
    this.changePermissions(script);

    for (final String library : APT_LIBRARIES) {
      this.installDependency(script, library);
    }
  }

  private void changePermissions(final Path file) throws IOException {
    final Path absolute = file.toAbsolutePath();
    final String raw = absolute.toString();
    final String[] args = new String[] { "chmod", "777", raw };
    final CommandTask task = new CommandTask(args, true);
    final Process process = task.getProcess();
    try {
      process.waitFor();
    } catch (final InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new NativeLoadingException(e.getMessage(), e);
    }
  }

  private void installDependency(final Path script, final String name) throws IOException {
    final Path absolute = script.toAbsolutePath();
    final String raw = absolute.toString();
    final String[] args = new String[] { raw, name };
    final CommandTask task = new CommandTask(args, true);
    final Process process = task.getProcess();
    try {
      process.waitFor();
    } catch (final InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new NativeLoadingException(e.getMessage(), e);
    }
  }
}
