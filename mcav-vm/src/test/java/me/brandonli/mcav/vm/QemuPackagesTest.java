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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.debian.DebianProgramInstaller;
import org.junit.jupiter.api.Test;

final class QemuPackagesTest {

  private static final Path ROOT = Path.of("installation");
  private static final List<Path> LIBRARY_FOLDERS = List.of(Path.of("usr/lib/x86_64-linux-gnu"));

  private static final String X86_LIST = "debian-qemu-x86";
  private static final String X86_RESOURCE = "mcav/vm/installers/debian-qemu-x86.json";
  private static final List<String> TREES = List.of("usr/bin", "usr/share/qemu", "usr/share/seabios");

  private final List<FileMode> modes = new ArrayList<>();

  @Test
  void shouldNameTheListOfEachArchitecture() {
    final String x86 = QemuPackages.listNameOf(VmPlayer.Architecture.X86_64);
    final String arm = QemuPackages.listNameOf(VmPlayer.Architecture.ARM);
    final String aarch64 = QemuPackages.listNameOf(VmPlayer.Architecture.AARCH64);
    final String riscv64 = QemuPackages.listNameOf(VmPlayer.Architecture.RISCV64);
    assertEquals(X86_LIST, x86);
    assertEquals("debian-qemu-arm", arm);
    assertEquals("debian-qemu-arm", aarch64);
    assertEquals("debian-qemu-misc", riscv64);
  }

  @Test
  void shouldDelegateToTheInstaller() {
    final QemuPackages packages = new QemuPackages(() -> true, this::download, () -> LIBRARY_FOLDERS);

    final boolean supported = packages.isSupported();
    final Path root = packages.download();
    final List<Path> libraryFolders = packages.listLibraryFolders();

    final List<FileMode> expectedModes = List.of(FileMode.UNCHANGED);
    assertTrue(supported);
    assertEquals(ROOT, root);
    assertEquals(LIBRARY_FOLDERS, libraryFolders);
    assertEquals(expectedModes, this.modes);
  }

  @Test
  void shouldReportAPlatformWithoutPackages() {
    final QemuPackages packages = new QemuPackages(() -> false, this::download, List::of);

    final boolean supported = packages.isSupported();
    assertFalse(supported);
  }

  @Test
  void shouldCreateTheInstallerOfTheShippedList() {
    final QemuPackages packages = QemuPackages.create(VmPlayer.Architecture.X86_64);
    final DebianProgramInstaller installer = DebianProgramInstaller.create(X86_RESOURCE, X86_LIST, TREES);

    final boolean supported = packages.isSupported();
    final boolean expected = installer.isSupported();
    assertEquals(expected, supported);
  }

  private Path download(final FileMode mode) {
    this.modes.add(mode);
    return ROOT;
  }
}
