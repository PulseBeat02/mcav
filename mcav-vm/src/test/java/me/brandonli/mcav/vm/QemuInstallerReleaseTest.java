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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// installs the pinned x86-64 QEMU packages from the real archive and starts the program
final class QemuInstallerReleaseTest {

  private static final String USER_HOME = "user.home";

  private static final Platform X86_64 = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);

  private static final List<String> MACHINE_ARGUMENTS = List.of(
    "-machine",
    "pc",
    "-accel",
    "tcg",
    "-nodefaults",
    "-display",
    "none",
    "-monitor",
    "stdio"
  );
  private static final String MONITOR_COMMANDS = "info status\nquit\n";
  private static final String RUNNING_STATUS = "VM status: running";

  private static final String OUTPUT_FILE = "qemu-output.txt";

  private static final long QUIT_TIMEOUT_SECONDS = 60L;

  @TempDir
  private Path home;

  @BeforeAll
  static void requireNetworkTests() {
    final boolean enabled = Boolean.getBoolean("mcav.networkTests");
    Assumptions.assumeTrue(enabled, "Network tests run only with -Pmcav.networkTests=true!");
  }

  @Test
  void shouldStartTheInstalledQemu() throws IOException, InterruptedException {
    final Platform current = Platform.getCurrentPlatform();
    final boolean onX86Linux = current.equals(X86_64);
    Assumptions.assumeTrue(onX86Linux, "Only a 64-bit x86 Linux machine runs the x86-64 packages!");

    final QemuInstaller installer = new QemuInstaller(this::createPackagesInTemporaryCache, current);
    final QemuProgram program = installer.install(VmPlayer.Architecture.X86_64);
    final Path output = this.home.resolve(OUTPUT_FILE);
    final Process qemu = this.startMachine(program, output);
    try {
      this.sendMonitorCommands(qemu);
      final boolean exited = qemu.waitFor(QUIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(exited, "QEMU must quit when its monitor tells it to!");
    } finally {
      qemu.destroyForcibly();
    }

    final int status = qemu.exitValue();
    final String printed = Files.readString(output);
    final boolean running = printed.contains(RUNNING_STATUS);
    assertEquals(0, status, printed);
    assertTrue(running, printed);
  }

  // the cache folder of the installer is fixed when QemuPackages.create builds it, so the home is restored at once
  private QemuPackages createPackagesInTemporaryCache(final VmPlayer.Architecture architecture) {
    final String previousHome = System.getProperty(USER_HOME);
    final String rawHome = this.home.toString();
    System.setProperty(USER_HOME, rawHome);
    try {
      return QemuPackages.create(architecture);
    } finally {
      System.setProperty(USER_HOME, previousHome);
    }
  }

  private Process startMachine(final QemuProgram program, final Path output) throws IOException {
    final Path executable = program.getExecutable();
    final List<String> dataArguments = program.getArguments();
    final List<String> command = new ArrayList<>();
    command.add(executable.toString());
    command.addAll(dataArguments);
    command.addAll(MACHINE_ARGUMENTS);
    final ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    builder.redirectOutput(output.toFile());
    final Map<String, String> environment = builder.environment();
    final Map<String, String> programEnvironment = program.getEnvironment();
    environment.putAll(programEnvironment);
    return builder.start();
  }

  private void sendMonitorCommands(final Process qemu) throws IOException {
    final byte[] commands = MONITOR_COMMANDS.getBytes(StandardCharsets.UTF_8);
    try (final OutputStream input = qemu.getOutputStream()) {
      input.write(commands);
    }
  }
}
