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
package me.brandonli.mcav.utils.ffmpeg;

import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.brandonli.mcav.MCAVException;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.debian.DebianLibraryInstaller;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Fetches the system libraries that FFmpeg's device library links when Linux lacks them. An ffmpeg process does not
// share what the JVM loaded, so it gets their folders on its library path.
final class FfmpegLibraries {

  private static final Logger LOGGER = LoggerFactory.getLogger(FfmpegLibraries.class);

  private static final List<String> SYSTEM_LIBRARIES = List.of(
    "libasound.so.2",
    "libpulse.so.0",
    "libxcb.so.1",
    "libxcb-shm.so.0",
    "libudev.so.1"
  );

  private static final String LIBRARY_PATH_VARIABLE = "LD_LIBRARY_PATH";

  private final Os os;
  private final List<Path> folders;

  @VisibleForTesting
  FfmpegLibraries(final Os os, final List<Path> folders) {
    this.os = os;
    this.folders = List.copyOf(folders);
  }

  static FfmpegLibraries installOnce() {
    return LibrariesHolder.LIBRARIES;
  }

  @VisibleForTesting
  static FfmpegLibraries install(final Os os, final Predicate<String> systemLibraries, final Supplier<DebianLibraryInstaller> installers) {
    try {
      final DebianLibraryInstaller installer = installers.get();
      final List<Path> folders = installMissing(installer, systemLibraries);
      return new FfmpegLibraries(os, folders);
    } catch (final MCAVException exception) {
      LOGGER.warn("The system libraries of FFmpeg could not be installed, so FFmpeg may fail to load.", exception);
      return new FfmpegLibraries(os, List.of());
    }
  }

  private static List<Path> installMissing(final DebianLibraryInstaller installer, final Predicate<String> systemLibraries) {
    final boolean supported = installer.isSupported();
    if (!supported) {
      return List.of();
    }

    final boolean complete = SYSTEM_LIBRARIES.stream().allMatch(systemLibraries);
    if (complete) {
      return List.of();
    }

    installer.download(FileMode.EXECUTABLE);
    installer.loadLibraries();

    return installer.listLibraryFolders();
  }

  Map<String, String> getEnvironment(final Path executable) {
    final @Nullable String inherited = System.getenv(LIBRARY_PATH_VARIABLE);
    return this.createEnvironment(executable, inherited);
  }

  @VisibleForTesting
  Map<String, String> createEnvironment(final Path executable, final @Nullable String inherited) {
    if (this.os != Os.LINUX) {
      return Map.of();
    }

    // FFmpeg's libraries have no run path, so they find the libva and libdrm beside them only on the library path
    final Path executableFolder = this.getParent(executable);
    final Stream<Path> libraryFolders = Stream.concat(Stream.of(executableFolder), this.folders.stream());
    final Stream<String> folderPaths = libraryFolders.map(Path::toString);
    final Stream<String> inheritedPaths = Stream.ofNullable(inherited).filter(Predicate.not(String::isEmpty));
    final Stream<String> paths = Stream.concat(folderPaths, inheritedPaths);
    final String libraryPath = paths.collect(Collectors.joining(File.pathSeparator));
    return Map.of(LIBRARY_PATH_VARIABLE, libraryPath);
  }

  private Path getParent(final Path executable) {
    final @Nullable Path parent = executable.getParent();
    return Objects.requireNonNull(parent, "An extracted executable always has a parent!");
  }

  private static final class LibrariesHolder {

    private static final FfmpegLibraries LIBRARIES = install(
      OsUtils.getOs(),
      OsUtils::isLibraryAvailable,
      DebianLibraryInstaller::createFfmpegInstaller
    );

    private LibrariesHolder() {
      throw new UnsupportedOperationException("Holder class cannot be instantiated!");
    }
  }
}
