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

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.debian.DebianProgramInstaller;

final class QemuPackages {

  private static final String RESOURCE_FOLDER = "mcav/vm/installers/";
  private static final String RESOURCE_SUFFIX = ".json";

  private static final List<String> TREES = List.of("usr/bin", "usr/share/qemu", "usr/share/seabios");

  private static final String X86_PACKAGE_LIST = "debian-qemu-x86";
  private static final String ARM_PACKAGE_LIST = "debian-qemu-arm";
  private static final String MISC_PACKAGE_LIST = "debian-qemu-misc";

  private final BooleanSupplier supported;
  private final Function<FileMode, Path> downloader;
  private final Supplier<List<Path>> libraryFolders;

  QemuPackages(final BooleanSupplier supported, final Function<FileMode, Path> downloader, final Supplier<List<Path>> libraryFolders) {
    this.supported = supported;
    this.downloader = downloader;
    this.libraryFolders = libraryFolders;
  }

  static QemuPackages create(final VmPlayer.Architecture architecture) {
    final String name = listNameOf(architecture);
    final String resource = "%s%s%s".formatted(RESOURCE_FOLDER, name, RESOURCE_SUFFIX);
    final DebianProgramInstaller installer = DebianProgramInstaller.create(resource, name, TREES);
    return new QemuPackages(installer::isSupported, installer::download, installer::listLibraryFolders);
  }

  static String listNameOf(final VmPlayer.Architecture architecture) {
    return switch (architecture) {
      case X86_64 -> X86_PACKAGE_LIST;
      case ARM, AARCH64 -> ARM_PACKAGE_LIST;
      case RISCV64 -> MISC_PACKAGE_LIST;
    };
  }

  boolean isSupported() {
    return this.supported.getAsBoolean();
  }

  Path download() {
    return this.downloader.apply(FileMode.UNCHANGED);
  }

  List<Path> listLibraryFolders() {
    return this.libraryFolders.get();
  }
}
