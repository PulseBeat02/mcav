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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Finds programs the way a shell does, and in the folders package managers install them to when those are missing
 * from the {@code PATH}, as they often are for services.
 *
 * <p>A name with a folder, such as {@code /opt/qemu/qemu-system-x86_64}, is looked up in that folder. A bare name is
 * looked up in this order:
 * <ol>
 *   <li>the folders of the {@code PATH} environment variable;</li>
 *   <li>on macOS, the folders listed in {@code /etc/paths} and the files of {@code /etc/paths.d}, which a login shell
 *   adds to the {@code PATH}, then {@code /opt/homebrew/bin} and {@code /usr/local/bin}, where Homebrew installs;</li>
 *   <li>on Linux and FreeBSD, {@code /usr/local/bin} and {@code /usr/bin};</li>
 *   <li>on Windows, {@code C:\Program Files\qemu}, where the QEMU installer puts QEMU without adding it to the
 *   {@code PATH}.</li>
 * </ol>
 *
 * <p>On Windows only files with an executable extension ({@code .com}, {@code .exe}, {@code .bat}, or {@code .cmd})
 * are found, and the working folder is never searched, so a file planted there cannot be run in place of the real
 * program. Found programs are returned as absolute paths.
 */
public final class ExecutableFinder {

  private static final List<String> WINDOWS_EXTENSIONS = List.of(".com", ".exe", ".bat", ".cmd");
  private static final Path SYSTEM_ROOT = Path.of("/");
  private static final List<String> MAC_FOLDERS = List.of("opt/homebrew/bin", "usr/local/bin");
  private static final List<String> UNIX_FOLDERS = List.of("usr/local/bin", "usr/bin");
  private static final String DEFAULT_PROGRAM_FILES = "C:\\Program Files";
  private static final File[] NO_FILES = new File[0];

  private final OS os;
  private final Map<String, String> environment;
  private final Path root;

  /**
   * Constructs a finder for the current operating system.
   */
  public ExecutableFinder() {
    final OS currentOs = OSUtils.getOS();
    final Map<String, String> currentEnvironment = System.getenv();
    this.os = currentOs;
    this.environment = Map.copyOf(currentEnvironment);
    this.root = SYSTEM_ROOT;
  }

  /**
   * Constructs a finder for an operating system and environment, so tests can simulate other machines.
   *
   * @param os          the operating system
   * @param environment the environment variables, which provide the {@code PATH} and, on Windows, the
   *                    {@code ProgramFiles} folder
   * @param root        the root folder the fixed Unix folders such as {@code /etc/paths} are resolved against
   */
  @VisibleForTesting
  ExecutableFinder(final OS os, final Map<String, String> environment, final Path root) {
    this.os = os;
    this.environment = Map.copyOf(environment);
    this.root = root;
  }

  /**
   * Finds a program.
   *
   * @param name the program name, such as {@code qemu-system-x86_64}, or a path to it
   * @return the absolute path of the program, or empty if it is not installed where this finder looks
   */
  public Optional<Path> find(final String name) {
    Preconditions.checkNotNull(name, "Name must not be null");
    Preconditions.checkArgument(!name.isBlank(), "Name must not be blank");

    final Path direct = Path.of(name);
    final Path parent = direct.getParent();
    if (parent != null) {
      final Path fileNameOrNull = direct.getFileName();
      final Path fileName = Objects.requireNonNull(fileNameOrNull, "A path with a parent has a file name");
      final String bareName = fileName.toString();
      return this.findIn(parent, bareName);
    }

    final List<Path> directories = this.getSearchDirectories();
    for (final Path directory : directories) {
      final Optional<Path> found = this.findIn(directory, name);
      if (found.isPresent()) {
        return found;
      }
    }
    return Optional.empty();
  }

  private Optional<Path> findIn(final Path directory, final String name) {
    final List<String> candidates = this.getCandidateNames(name);
    for (final String candidateName : candidates) {
      final Path candidate = directory.resolve(candidateName);
      final boolean executable = isExecutable(candidate);
      if (executable) {
        final Path absolute = candidate.toAbsolutePath();
        return Optional.of(absolute);
      }
    }
    return Optional.empty();
  }

  /**
   * Gets the file names a program may have: on Windows the name with each executable extension, or the name itself
   * if it already has one; elsewhere the name itself.
   */
  private List<String> getCandidateNames(final String name) {
    if (this.os != OS.WINDOWS) {
      return List.of(name);
    }

    final String lower = name.toLowerCase(Locale.ROOT);
    final List<String> names = new ArrayList<>();
    for (final String extension : WINDOWS_EXTENSIONS) {
      final boolean hasExtension = lower.endsWith(extension);
      if (hasExtension) {
        return List.of(name);
      }
      names.add(name + extension);
    }
    return names;
  }

  private List<Path> getSearchDirectories() {
    final String path = this.getVariable("PATH");
    final String separator = Pattern.quote(File.pathSeparator);
    final String[] segments = path.split(separator);
    final List<String> entries = List.of(segments);
    final List<Path> directories = toDirectories(entries);

    final List<Path> fallbacks =
      switch (this.os) {
        case MAC -> this.getMacDirectories();
        case LINUX, FREEBSD -> this.resolveUnder(UNIX_FOLDERS);
        case WINDOWS -> this.getWindowsDirectories();
        // an unknown operating system has no well-known install folders, so only PATH is searched
        case OTHER -> List.of();
      };
    directories.addAll(fallbacks);
    return directories;
  }

  private List<Path> getMacDirectories() {
    final Path pathsFile = this.root.resolve("etc/paths");
    final List<Path> listed = readPathsFile(pathsFile);
    final List<Path> directories = new ArrayList<>(listed);

    final List<Path> fromPathsDirectory = this.readPathsDirectory();
    directories.addAll(fromPathsDirectory);

    final List<Path> homebrew = this.resolveUnder(MAC_FOLDERS);
    directories.addAll(homebrew);
    return directories;
  }

  /**
   * Reads the folders listed in the files of {@code /etc/paths.d}, file by file in the order of their names, as a
   * login shell on macOS does.
   *
   * @return the listed folders
   */
  private List<Path> readPathsDirectory() {
    final Path pathsDirectory = this.root.resolve("etc/paths.d");
    final File pathsFolder = pathsDirectory.toFile();
    final File[] children = pathsFolder.listFiles(File::isFile);
    final File[] files = Objects.requireNonNullElse(children, NO_FILES);
    final Comparator<File> byName = Comparator.comparing(File::getName);
    Arrays.sort(files, byName);

    final List<Path> directories = new ArrayList<>();
    for (final File file : files) {
      final Path filePath = file.toPath();
      final List<Path> fromFile = readPathsFile(filePath);
      directories.addAll(fromFile);
    }
    return directories;
  }

  private List<Path> getWindowsDirectories() {
    final String programFiles = this.getVariable("ProgramFiles");
    final String folder = programFiles.isBlank() ? DEFAULT_PROGRAM_FILES : programFiles;
    final Path qemu = Path.of(folder, "qemu");
    return List.of(qemu);
  }

  private List<Path> resolveUnder(final List<String> folders) {
    final List<Path> directories = new ArrayList<>();
    for (final String folder : folders) {
      final Path directory = this.root.resolve(folder);
      directories.add(directory);
    }
    return directories;
  }

  private String getVariable(final String name) {
    final String exact = this.environment.get(name);
    if (exact != null) {
      return exact;
    }

    // Windows environment variables are case-insensitive and may be spelled "Path"
    for (final Map.Entry<String, String> entry : this.environment.entrySet()) {
      final String key = entry.getKey();
      final boolean matches = name.equalsIgnoreCase(key);
      if (matches) {
        return entry.getValue();
      }
    }
    return "";
  }

  private static List<Path> readPathsFile(final Path file) {
    final boolean exists = Files.isRegularFile(file);
    if (!exists) {
      return List.of();
    }
    try {
      final List<String> lines = Files.readAllLines(file);
      return toDirectories(lines);
    } catch (final IOException exception) {
      return List.of();
    }
  }

  private static List<Path> toDirectories(final List<String> entries) {
    final List<Path> directories = new ArrayList<>();
    for (final String entry : entries) {
      final Path directory = toDirectory(entry);
      if (directory != null) {
        directories.add(directory);
      }
    }
    return directories;
  }

  private static @Nullable Path toDirectory(final String entry) {
    final String trimmed = entry.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    try {
      return Path.of(trimmed);
    } catch (final InvalidPathException exception) {
      // a broken entry is skipped
      return null;
    }
  }

  private static boolean isExecutable(final Path path) {
    return Files.isRegularFile(path) && Files.isExecutable(path);
  }
}
