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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Platform;

final class QemuInstaller {

  private static final String PROGRAM_FOLDER = "usr/bin";
  private static final List<String> DATA_FOLDERS = List.of("usr/share/qemu", "usr/share/seabios", "usr/lib/ipxe/qemu");
  private static final String DATA_OPTION = "-L";

  private static final String LIBRARY_FOLDER = "usr/lib";
  private static final String MODULE_FOLDER = "qemu";
  private static final String X86_TRIPLET = "x86_64-linux-gnu";
  private static final String ARM_TRIPLET = "aarch64-linux-gnu";

  private static final String LIBRARY_PATH_VARIABLE = "LD_LIBRARY_PATH";
  private static final String MODULE_FOLDER_VARIABLE = "QEMU_MODULE_DIR";

  private final Function<VmPlayer.Architecture, QemuPackages> packagesFactory;
  private final Platform platform;

  QemuInstaller() {
    final Platform current = Platform.getCurrentPlatform();
    this(QemuPackages::create, current);
  }

  QemuInstaller(final Function<VmPlayer.Architecture, QemuPackages> packagesFactory, final Platform platform) {
    this.packagesFactory = packagesFactory;
    this.platform = platform;
  }

  boolean isSupported(final VmPlayer.Architecture architecture) {
    final QemuPackages packages = this.packagesFactory.apply(architecture);
    return packages.isSupported();
  }

  QemuProgram install(final VmPlayer.Architecture architecture) {
    final QemuPackages packages = this.packagesFactory.apply(architecture);
    final String command = architecture.getCommand();
    final boolean supported = packages.isSupported();
    if (!supported) {
      throw new ExecutableNotInPathException(command, this.platform);
    }

    final Path root = packages.download();
    final List<Path> libraryFolders = packages.listLibraryFolders();
    final Path programFolder = root.resolve(PROGRAM_FOLDER);
    final Path executable = programFolder.resolve(command);
    final List<String> arguments = this.listDataArguments(root);
    final Map<String, String> environment = this.createEnvironment(root, libraryFolders);
    return QemuProgram.of(executable, arguments, environment);
  }

  private List<String> listDataArguments(final Path root) {
    final List<String> arguments = new ArrayList<>();
    for (final String folder : DATA_FOLDERS) {
      final Path data = root.resolve(folder);
      arguments.add(DATA_OPTION);
      arguments.add(data.toString());
    }

    return arguments;
  }

  private Map<String, String> createEnvironment(final Path root, final List<Path> libraryFolders) {
    final List<String> folders = libraryFolders.stream().map(Path::toString).toList();
    final String libraryPath = String.join(File.pathSeparator, folders);
    final String triplet = this.platform.getArch() == Arch.ARM ? ARM_TRIPLET : X86_TRIPLET;
    final Path libraryFolder = root.resolve(LIBRARY_FOLDER);
    final Path tripletFolder = libraryFolder.resolve(triplet);
    final Path modules = tripletFolder.resolve(MODULE_FOLDER);
    return Map.of(LIBRARY_PATH_VARIABLE, libraryPath, MODULE_FOLDER_VARIABLE, modules.toString());
  }
}
