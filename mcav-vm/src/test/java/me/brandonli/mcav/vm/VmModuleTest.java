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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

final class VmModuleTest {

  private static final String RECORDS_FOLDER = "records";
  private static final String RECORD_SUFFIX = ".qemu";
  private static final String NO_PATHS_FILE = "no-paths-file";
  private static final String BIN_FOLDER = "bin";
  private static final String EMPTY_FOLDER = "empty";
  private static final String QEMU_EXECUTABLE = "qemu-system-x86_64";
  private static final String SLEEPER_SOURCE = "Sleep.java";
  private static final String INSTALLATION_FOLDER = "installation";

  private static final Platform LINUX_X86_64 = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);

  private static final boolean AUTO_FLUSH = true;

  @TempDir
  private Path directory;

  private QemuProcessRecords records() {
    final Path folder = this.directory.resolve(RECORDS_FOLDER);
    return new QemuProcessRecords(folder, ProcessHandle.current(), ProcessHandle::of, Duration.ofSeconds(10));
  }

  private ExecutableFinder finderWithPath(final Path folder) {
    final String path = folder.toString();
    final Map<String, String> environment = Map.of("PATH", path);
    final Path macPaths = this.directory.resolve(NO_PATHS_FILE);
    return new ExecutableFinder(Os.LINUX, environment, macPaths);
  }

  private QemuInstaller installer(final BooleanSupplier supported) {
    final Path installation = this.directory.resolve(INSTALLATION_FOLDER);
    final QemuPackages packages = new QemuPackages(supported, _ -> installation, List::of);
    return new QemuInstaller(_ -> packages, LINUX_X86_64);
  }

  @Test
  void shouldFindQemuWhenStarted() throws IOException {
    final Path folder = this.directory.resolve(BIN_FOLDER);
    Files.createDirectories(folder);
    final Path qemu = folder.resolve(QEMU_EXECUTABLE);
    Files.writeString(qemu, "qemu");
    final File file = qemu.toFile();
    final boolean madeExecutable = file.setExecutable(true);
    assertTrue(madeExecutable);

    final ExecutableFinder finder = this.finderWithPath(folder);
    final VmModule module = new VmModule(finder, this.installer(() -> false), this.records());
    final boolean beforeStart = module.isQemuInstalled();
    final boolean availableBeforeStart = module.isQemuAvailable();
    assertFalse(beforeStart);
    assertFalse(availableBeforeStart);

    module.start();

    final boolean qemuFoundAfterStart = module.isQemuInstalled();
    final boolean availableAfterStart = module.isQemuAvailable();
    assertTrue(qemuFoundAfterStart);
    assertTrue(availableAfterStart);
  }

  @Test
  void shouldStopTheQemuOfAKilledServerWhenStarted() throws IOException, InterruptedException {
    final String java = this.javaCommand();
    final Path sleeper = this.directory.resolve(SLEEPER_SOURCE);
    Files.writeString(sleeper, "class Sleep { public static void main(String[] a) throws Exception { Thread.sleep(300_000L); } }");
    final Process qemu = new ProcessBuilder(java, sleeper.toString()).start();
    try {
      final ProcessHandle handle = qemu.toHandle();
      final ProcessHandle.Info information = handle.info();
      final Optional<Instant> start = information.startInstant();
      final Instant started = start.orElseThrow();
      final Path folder = this.directory.resolve(RECORDS_FOLDER);
      final String recordName = "%s%s".formatted(qemu.pid(), RECORD_SUFFIX);
      final Path record = folder.resolve(recordName);
      Files.createDirectories(record.getParent());
      Files.writeString(record, "%s\n%s\n%s\n".formatted(started, Long.MAX_VALUE, started));
      final Path empty = this.directory.resolve(EMPTY_FOLDER);
      Files.createDirectories(empty);
      final VmModule module = new VmModule(this.finderWithPath(empty), this.installer(() -> false), this.records());

      module.start();

      final boolean stopped = qemu.waitFor(10, TimeUnit.SECONDS);
      final boolean recordExists = Files.exists(record);
      assertTrue(stopped, "the QEMU of the killed server is stopped");
      assertFalse(recordExists);
    } finally {
      qemu.destroyForcibly();
    }
  }

  private String javaCommand() {
    final ProcessHandle current = ProcessHandle.current();
    final ProcessHandle.Info information = current.info();
    final Optional<String> command = information.command();
    return command.orElseThrow();
  }

  @Test
  void shouldReportAMissingQemu() throws IOException {
    final Path empty = this.directory.resolve(EMPTY_FOLDER);
    Files.createDirectories(empty);
    final ExecutableFinder finder = this.finderWithPath(empty);
    final VmModule module = new VmModule(finder, this.installer(() -> false), this.records());

    module.start();

    final boolean installed = module.isQemuInstalled();
    final boolean available = module.isQemuAvailable();
    assertFalse(installed);
    assertFalse(available);
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldLogThatQemuWillBeInstalled() throws IOException {
    final Path empty = this.directory.resolve(EMPTY_FOLDER);
    Files.createDirectories(empty);
    final ExecutableFinder finder = this.finderWithPath(empty);
    final VmModule module = new VmModule(finder, this.installer(() -> true), this.records());

    final String log = this.captureStandardError(module::start);

    final boolean installed = module.isQemuInstalled();
    final boolean available = module.isQemuAvailable();
    assertFalse(installed);
    assertTrue(available);

    final boolean announced = log.contains(
      "QEMU program qemu-system-x86_64 is not on the PATH, so MCAV installs it from Debian packages when the first virtual machine starts."
    );
    assertTrue(announced, log);
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldWarnThatVirtualMachinesCannotStartWithoutQemu() throws IOException {
    final Path empty = this.directory.resolve(EMPTY_FOLDER);
    Files.createDirectories(empty);
    final ExecutableFinder finder = this.finderWithPath(empty);
    final VmModule module = new VmModule(finder, this.installer(() -> false), this.records());

    final String log = this.captureStandardError(module::start);

    final boolean warned = log.contains(
      "QEMU program qemu-system-x86_64 is not on the PATH or in the usual install folders, so virtual machines cannot be started."
    );
    assertTrue(warned, log);
  }

  @Test
  void shouldLookForQemuOnThisMachineByDefault() {
    final VmModule module = new VmModule();
    module.start();
    final boolean installed = module.isQemuInstalled();
    final ExecutableFinder finder = new ExecutableFinder();
    final String command = VmPlayer.Architecture.X86_64.getCommand();
    final Optional<Path> qemu = finder.find(command);
    final boolean present = qemu.isPresent();
    assertEquals(present, installed);

    module.stop();
  }

  @Test
  void shouldBeNamedVm() {
    final VmModule module = new VmModule();
    final String name = module.getModuleName();
    assertEquals("vm", name);
  }

  @Test
  void shouldBeCreatableByTheModuleLoader() throws ReflectiveOperationException {
    final Constructor<VmModule> constructor = VmModule.class.getConstructor();
    final VmModule module = constructor.newInstance();
    final boolean installed = module.isQemuInstalled();
    assertFalse(installed, "nothing is looked up before the module starts");
  }

  private String captureStandardError(final Runnable action) {
    final PrintStream original = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    final PrintStream capture = new PrintStream(captured, AUTO_FLUSH, StandardCharsets.UTF_8);
    System.setErr(capture);
    try {
      action.run();
    } finally {
      System.setErr(original);
    }

    return captured.toString(StandardCharsets.UTF_8);
  }
}
