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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QemuInstallerTest {

  private static final Platform LINUX_X86_64 = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);
  private static final Platform LINUX_ARM64 = Platform.ofPlatform(Os.LINUX, Arch.ARM, Bits.BITS_64);
  private static final Platform WINDOWS_X86_64 = Platform.ofPlatform(Os.WINDOWS, Arch.X86, Bits.BITS_64);

  private static final String ROOT_FOLDER = "installation";
  private static final String LIBRARY_FOLDER = "usr/lib/x86_64-linux-gnu";
  private static final String PULSE_LIBRARY_FOLDER = "usr/lib/x86_64-linux-gnu/pulseaudio";

  private static final String X86_EXECUTABLE = "usr/bin/qemu-system-x86_64";

  private static final String QEMU_DATA_FOLDER = "usr/share/qemu";
  private static final String BIOS_DATA_FOLDER = "usr/share/seabios";
  private static final String NETWORK_DATA_FOLDER = "usr/lib/ipxe/qemu";

  private static final String X86_MODULE_FOLDER = "usr/lib/x86_64-linux-gnu/qemu";
  private static final String ARM_MODULE_FOLDER = "usr/lib/aarch64-linux-gnu/qemu";

  private static final String MODULE_FOLDER_VARIABLE = "QEMU_MODULE_DIR";

  @TempDir
  private Path directory;

  private Path root;
  private List<FileMode> downloads;

  @BeforeEach
  void setUp() {
    this.root = this.directory.resolve(ROOT_FOLDER);
    this.downloads = new ArrayList<>();
  }

  @Test
  void shouldRunTheInstalledProgram() {
    final QemuInstaller installer = this.createInstaller(() -> true, LINUX_X86_64);

    final QemuProgram program = installer.install(VmPlayer.Architecture.X86_64);

    final Path executable = program.getExecutable();
    final Path expected = this.root.resolve(X86_EXECUTABLE);
    final List<FileMode> expectedDownloads = List.of(FileMode.UNCHANGED);
    assertEquals(expected, executable);
    assertEquals(expectedDownloads, this.downloads);
  }

  @Test
  void shouldPassEveryDataFolder() {
    final QemuInstaller installer = this.createInstaller(() -> true, LINUX_X86_64);

    final QemuProgram program = installer.install(VmPlayer.Architecture.X86_64);

    final List<String> arguments = program.getArguments();
    final String qemuData = this.pathOf(QEMU_DATA_FOLDER);
    final String biosData = this.pathOf(BIOS_DATA_FOLDER);
    final String networkData = this.pathOf(NETWORK_DATA_FOLDER);
    final List<String> expected = List.of("-L", qemuData, "-L", biosData, "-L", networkData);
    assertEquals(expected, arguments);
  }

  @Test
  void shouldPutTheLibraryFoldersOnTheLibraryPath() {
    final QemuInstaller installer = this.createInstaller(() -> true, LINUX_X86_64);

    final QemuProgram program = installer.install(VmPlayer.Architecture.X86_64);

    final Map<String, String> environment = program.getEnvironment();
    final String libraryPath = environment.get("LD_LIBRARY_PATH");
    final String libraries = this.pathOf(LIBRARY_FOLDER);
    final String pulseLibraries = this.pathOf(PULSE_LIBRARY_FOLDER);
    final String expected = String.join(File.pathSeparator, libraries, pulseLibraries);
    assertEquals(expected, libraryPath);
  }

  @Test
  void shouldUseTheModulesOfTheX86Triplet() {
    final QemuInstaller installer = this.createInstaller(() -> true, LINUX_X86_64);

    final QemuProgram program = installer.install(VmPlayer.Architecture.X86_64);

    final Map<String, String> environment = program.getEnvironment();
    final String modules = environment.get(MODULE_FOLDER_VARIABLE);
    final String expected = this.pathOf(X86_MODULE_FOLDER);
    assertEquals(expected, modules);
  }

  @Test
  void shouldUseTheModulesOfTheArmTriplet() {
    final QemuInstaller installer = this.createInstaller(() -> true, LINUX_ARM64);

    final QemuProgram program = installer.install(VmPlayer.Architecture.AARCH64);

    final Map<String, String> environment = program.getEnvironment();
    final String modules = environment.get(MODULE_FOLDER_VARIABLE);
    final String expected = this.pathOf(ARM_MODULE_FOLDER);
    assertEquals(expected, modules);
  }

  @Test
  void shouldNameThePlatformWithoutPackages() {
    final QemuInstaller installer = this.createInstaller(() -> false, WINDOWS_X86_64);

    final ExecutableNotInPathException exception = assertThrows(ExecutableNotInPathException.class, () ->
      installer.install(VmPlayer.Architecture.X86_64)
    );

    final String message = exception.getMessage();
    assertEquals("Program qemu-system-x86_64 is not installed or not on the PATH, and MCAV cannot install it on WINDOWS X86!", message);
  }

  @Test
  void shouldNotDownloadWithoutPackages() {
    final QemuInstaller installer = this.createInstaller(() -> false, LINUX_X86_64);

    assertThrows(ExecutableNotInPathException.class, () -> installer.install(VmPlayer.Architecture.RISCV64));

    final boolean nothingDownloaded = this.downloads.isEmpty();
    assertTrue(nothingDownloaded);
  }

  @Test
  void shouldReportSupportOfTheArchitecture() {
    final QemuInstaller supportedInstaller = this.createInstaller(() -> true, LINUX_X86_64);
    final QemuInstaller unsupportedInstaller = this.createInstaller(() -> false, LINUX_X86_64);

    final boolean supported = supportedInstaller.isSupported(VmPlayer.Architecture.ARM);
    final boolean unsupported = unsupportedInstaller.isSupported(VmPlayer.Architecture.ARM);
    assertTrue(supported);
    assertFalse(unsupported);
  }

  private QemuInstaller createInstaller(final BooleanSupplier supported, final Platform platform) {
    final QemuPackages packages = new QemuPackages(supported, this::download, this::listLibraryFolders);
    return new QemuInstaller(_ -> packages, platform);
  }

  private Path download(final FileMode mode) {
    this.downloads.add(mode);
    return this.root;
  }

  private List<Path> listLibraryFolders() {
    final Path libraries = this.root.resolve(LIBRARY_FOLDER);
    final Path pulseLibraries = this.root.resolve(PULSE_LIBRARY_FOLDER);
    return List.of(libraries, pulseLibraries);
  }

  private String pathOf(final String folder) {
    final Path path = this.root.resolve(folder);
    return path.toString();
  }
}
