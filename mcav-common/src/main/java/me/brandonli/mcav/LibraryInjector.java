/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.UncheckedIOException;
import me.brandonli.mcav.utils.os.*;

final class LibraryInjector {

  private static final Map<Platform, String> ARCH_SUFFIXES = Map.of(
    Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_32),
    "i386",
    Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_64),
    "x86_64",
    Platform.ofPlatform(OS.LINUX, Arch.ARM, Bits.BITS_32),
    "arm",
    Platform.ofPlatform(OS.LINUX, Arch.ARM, Bits.BITS_64),
    "arm64"
  );

  private static final List<String> DUMMY_LIBRARIES = List.of("libgtk-x11-2.0.so.0");

  void load() {
    final OS os = OSUtils.getOS();
    if (os != OS.LINUX) {
      return;
    }
    final Platform current = Platform.getCurrentPlatform();
    final String archSuffix = requireNonNull(ARCH_SUFFIXES.get(current));
    final String sourceLibName = String.format("libdummy_%s.so", archSuffix);

    final Path cacheDir = IOUtils.getCachedFolder();
    final ProcessBuilder pb = new ProcessBuilder();
    final Map<String, String> env = pb.environment();
    final String cachePath = cacheDir.toString();
    final String originalPath = env.getOrDefault("LD_LIBRARY_PATH", "");
    final String appended = String.format("%s:%s", cachePath, originalPath);
    env.put("LD_LIBRARY_PATH", appended);

    for (final String libBase : DUMMY_LIBRARIES) {
      final Path file = this.copyAndRenameLibrary(sourceLibName, libBase);
      final String absolutePath = file.toString();
      System.load(absolutePath);
    }
  }

  private Path copyAndRenameLibrary(final String sourceLibName, final String targetLibName) {
    final Path cache = IOUtils.getCachedFolder();
    final Path targetFile = cache.resolve(targetLibName);
    final Path absolute = targetFile.toAbsolutePath();
    if (Files.notExists(absolute)) {
      try (final InputStream stream = IOUtils.getResourceAsInputStream(sourceLibName)) {
        Files.copy(stream, absolute);
      } catch (final IOException e) {
        throw new UncheckedIOException(e.getMessage(), e);
      }
    }
    return absolute;
  }
}
