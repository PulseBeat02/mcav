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
package me.brandonli.mcav.utils.natives;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.Platform;

/**
 * Utility class for copying dummy libraries to the java.library.path for libgtk.
 */
public final class DummyLibraryUtils {

  private static final List<String> LIBRARY_FILES = Arrays.asList(
    "libgtk-x11-2.0.so",
    "libgtk-x11-2.0.so.0",
    "libgtk-x11-2.0.so.0.2400.33"
  );

  private static final Table<Arch, Bits, String> LIBRARY_MAP = ImmutableTable.<Arch, Bits, String>builder()
    .put(Arch.ARM, Bits.BITS_64, "aarch64")
    .put(Arch.ARM, Bits.BITS_64, "amd64")
    .put(Arch.X86, Bits.BITS_32, "x86")
    .put(Arch.X86, Bits.BITS_64, "amd64")
    .build();

  private DummyLibraryUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Copies shared libraries for the current architecture to the java.library.path
   */
  public static void copyLibraries() {
    final Platform current = Platform.getCurrentPlatform();
    final OS os = current.getOS();
    if (os != OS.LINUX) {
      return;
    }

    final Arch cpu = current.getArch();
    final Bits bits = current.getBits();
    final String arch = requireNonNull(LIBRARY_MAP.get(cpu, bits));
    final String libraryPath = System.getProperty("java.library.path");
    final String[] paths = libraryPath.split(File.pathSeparator);
    final Path targetDir = Paths.get(paths[0]);

    try {
      copyLibrariesForArchitecture(arch, targetDir);
    } catch (final IOException e) {
      throw new NativeLoadingException(e.getMessage(), e);
    }
  }

  /**
   * Copies libraries for a specific architecture to the target directory
   *
   * @param arch        The architecture to copy libraries for
   * @param targetDir   The target directory to copy libraries to
   * @throws IOException If an I/O error occurs during copying
   * @throws NativeLoadingException If the library cannot be set as executable
   */
  private static void copyLibrariesForArchitecture(final String arch, final Path targetDir) throws IOException {
    Files.createDirectories(targetDir);

    for (final String libraryFile : LIBRARY_FILES) {
      final String resourcePath = String.format("/dummy/%s/%s", arch, libraryFile);
      final Path targetFile = targetDir.resolve(libraryFile);
      if (Files.exists(targetFile)) {
        continue;
      }

      try (final InputStream is = IOUtils.getResourceAsInputStream(resourcePath)) {
        Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
        final File file = targetFile.toFile();
        if (!file.setExecutable(true, false)) {
          throw new NativeLoadingException("Could not set executable permission!");
        }
      }
    }
  }
}
