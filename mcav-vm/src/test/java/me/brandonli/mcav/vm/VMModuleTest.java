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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import me.brandonli.mcav.utils.os.OS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link VMModule}.
 */
final class VMModuleTest {

  @TempDir
  private Path directory;

  private ExecutableFinder finderWithPath(final Path folder) {
    final String path = folder.toString();
    final Map<String, String> environment = Map.of("PATH", path);
    final Path macPaths = this.directory.resolve("no-paths-file");
    return new ExecutableFinder(OS.LINUX, environment, macPaths);
  }

  @Test
  void findsQemuWhenStarted() throws IOException {
    final Path folder = this.directory.resolve("bin");
    Files.createDirectories(folder);
    final Path qemu = folder.resolve("qemu-system-x86_64");
    Files.writeString(qemu, "qemu");
    final File file = qemu.toFile();
    final boolean madeExecutable = file.setExecutable(true);
    assertTrue(madeExecutable);

    final ExecutableFinder finder = this.finderWithPath(folder);
    final VMModule module = new VMModule(finder);
    final boolean beforeStart = module.isQemuInstalled();
    module.start();
    final boolean afterStart = module.isQemuInstalled();
    assertFalse(beforeStart);
    assertTrue(afterStart);
  }

  @Test
  void reportsAMissingQemu() throws IOException {
    final Path empty = this.directory.resolve("empty");
    Files.createDirectories(empty);
    final ExecutableFinder finder = this.finderWithPath(empty);
    final VMModule module = new VMModule(finder);
    module.start();
    final boolean installed = module.isQemuInstalled();
    assertFalse(installed);
  }

  @Test
  void looksForQemuOnThisMachineByDefault() {
    final VMModule module = new VMModule();
    module.start();
    final boolean installed = module.isQemuInstalled();
    final ExecutableFinder finder = new ExecutableFinder();
    final String command = VMPlayer.Architecture.X86_64.getCommand();
    final Optional<Path> qemu = finder.find(command);
    final boolean present = qemu.isPresent();
    assertEquals(present, installed);
    module.stop();
  }

  @Test
  void isNamedVm() {
    final VMModule module = new VMModule();
    final String name = module.getModuleName();
    assertEquals("vm", name);
  }

  @Test
  void canBeCreatedByTheModuleLoader() throws ReflectiveOperationException {
    final Constructor<VMModule> constructor = VMModule.class.getConstructor();
    final VMModule module = constructor.newInstance();
    final boolean installed = module.isQemuInstalled();
    assertFalse(installed, "nothing is looked up before the module starts");
  }
}
