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
package me.brandonli.mcav.dependency;

import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.natives.NativeLoadingException;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.Platform;
import me.brandonli.mcav.utils.runtime.CommandTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * A class that installs Nix on the system by downloading and executing a script. Installs proper dependencies needed
 * for JavaCV for Linux systems.
 */
public final class NixInstaller {

  private static final String SCRIPT_NAME = "install.sh";

  /**
   * Default constructor for NixInstaller.
   */
  public NixInstaller() {
    // no-op
  }

  /**
   * Installs Nix on the system by downloading and executing a script.
   */
  public void install() {
    final Platform platform = Platform.getCurrentPlatform();
    final OS os = platform.getOS();
    if (os != OS.LINUX) {
      return;
    }

    try {
      this.installBinaries();
      this.loadLibraries();
    } catch (final IOException e) {
      throw new NativeLoadingException(e.getMessage(), e);
    }
  }

  private static final List<String> LOAD_ORDER = List.of(

          "libva",
          "libva-x11",

          "libva-drm",
          "libavutil",
          "libswscale",
          "libswresample",
          "libpostproc",
          "libavcodec",
          "libavformat",
          "libavfilter",
          "libavdevice",

          "libvlccore",
          "libvlc",

          "libgomp",
          "libOpenCL",
          "libopenblas_nolapack",
          "libgfortran",
          "libopenblas",
          "libopencv_core",
          "libopencv_imgproc",
          "libjpeg",
          "libwebpmux",
          "libwebpdemux",
          "libopencv_imgcodecs",
          "libopencv_videoio",
          "libopencv_highgui"
  );

  private void loadLibraries() {
    final String home = System.getProperty("user.home");
    final Path path = Path.of(home, ".nix-portable", "nix", "store");
    final Path absolute = path.toAbsolutePath();

    final Map<String, Path> libraryMap = new HashMap<>();

    try (final Stream<Path> files = Files.walk(absolute, 1)
            .filter(Files::isDirectory)) {
      files.forEach(dir -> this.collectLibrariesFromDirectory(dir, libraryMap));
    } catch (final IOException e) {
      throw new NativeLoadingException(e.getMessage(), e);
    }

    for (final String libName : LOAD_ORDER) {
      libraryMap.entrySet().stream()
              .filter(entry -> entry.getKey().contains(libName))
              .forEach(entry -> this.loadLibrary(entry.getValue()));
    }

    libraryMap.values().forEach(this::loadLibrary);
  }

  private void collectLibrariesFromDirectory(final Path directory,
                                             final Map<String, Path> libraryMap) {
    final Path lib = directory.resolve("lib");
    if (Files.exists(lib) && Files.isDirectory(lib)) {
      try (final Stream<Path> files = Files.walk(lib)
              .filter(Files::isRegularFile)
              .filter(this::isSharedLibrary)) {
        files.forEach(library -> {
          final String name = requireNonNull(library.getFileName()).toString();
          libraryMap.put(name, library);
        });
      } catch (final IOException e) {
        throw new NativeLoadingException(e.getMessage(), e);
      }
    }
  }

  private void loadLibrary(final Path library) {
    final String libraryPath = library.toString();
    try {
      System.load(libraryPath);
      System.out.println("Loaded: " + library.getFileName());
    } catch (final UnsatisfiedLinkError e) {
      System.err.println("Failed to load " + library.getFileName() +
              ": " + e.getMessage());
    }
  }

  private boolean isSharedLibrary(final Path library) {
    final Path fileName = requireNonNull(library.getFileName());
    final String raw = fileName.toString();
    return raw.endsWith(".so") && this.isTargetLibrary(raw);
  }

  private boolean isTargetLibrary(final String filename) {
    return LOAD_ORDER.stream()
            .anyMatch(prefix -> filename.startsWith(prefix + ".so"));
  }

  private void installBinaries() throws IOException {
    final Path cache = IOUtils.getCachedFolder();
    final Path script = cache.resolve(SCRIPT_NAME);
    try (final InputStream stream = IOUtils.getResourceAsInputStream(SCRIPT_NAME)) {
      Files.copy(stream, script, StandardCopyOption.REPLACE_EXISTING);
    }

    final Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
    Files.setPosixFilePermissions(script, perms);

    final Path absolute = script.toAbsolutePath();
    final String raw = absolute.toString();
    final String[] args = new String[] { "/bin/sh", raw };
    final CommandTask task = new CommandTask(args, true);
    final Process process = task.getProcess();
    try {
      process.waitFor();
    } catch (final InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new NativeLoadingException(e.getMessage(), e);
    }
  }
}
