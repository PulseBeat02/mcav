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
package me.brandonli.mcav.capability.installer.vlc.installation;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.runtime.CommandTask;
import me.brandonli.mcav.utils.runtime.ProcessException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The base class of the VLC installation strategies, with helpers for running external tools and for locating the
 * VLC libraries inside an extracted installation.
 */
public abstract class ManualInstallationStrategy implements InstallationStrategy {

  private static final int MAX_SEARCH_DEPTH = 8;
  // extracting the AppImage or copying VLC.app takes seconds; a tool that hangs, such as a stuck hdiutil, is killed
  private static final Duration TOOL_TIMEOUT = Duration.ofMinutes(10);
  private static final ProcessRunner COMMAND_RUNNER = ManualInstallationStrategy::runCommand;

  private final VLCInstaller installer;
  private final ProcessRunner processRunner;

  /**
   * Constructs a new strategy for the installer that runs external tools as child processes.
   *
   * @param installer the installer whose installation directory is used
   */
  protected ManualInstallationStrategy(final VLCInstaller installer) {
    this(installer, COMMAND_RUNNER);
  }

  /**
   * Constructs a new strategy for the installer that runs external tools through the specified runner.
   *
   * @param installer     the installer whose installation directory is used
   * @param processRunner runs the external tools the installation needs
   */
  protected ManualInstallationStrategy(final VLCInstaller installer, final ProcessRunner processRunner) {
    Preconditions.checkNotNull(installer, "Installer must not be null");
    Preconditions.checkNotNull(processRunner, "Process runner must not be null");
    this.installer = installer;
    this.processRunner = processRunner;
  }

  /**
   * Gets the installer this strategy installs for.
   *
   * @return the installer
   */
  public VLCInstaller getInstaller() {
    return this.installer;
  }

  /**
   * Gets the directory VLC is installed into.
   *
   * @return the installation directory
   */
  protected Path getInstallDirectory() {
    return this.installer.getInstallDirectory();
  }

  /**
   * Finds the directory that contains a regular file matching the pattern, searching the directory recursively. Files
   * at most eight levels below the directory are found, so a file in the seventh nested subdirectory is found but one
   * in the eighth is not.
   *
   * @param root    the directory to search
   * @param pattern the pattern the file name must match completely
   * @return the parent directory of the first matching file, or empty if the directory does not exist or no file
   * matches
   * @throws IOException if the directory cannot be read
   */
  protected static Optional<Path> findLibraryDirectory(final Path root, final Pattern pattern) throws IOException {
    Preconditions.checkNotNull(root, "Root must not be null");
    Preconditions.checkNotNull(pattern, "Pattern must not be null");
    final boolean exists = Files.isDirectory(root);
    if (!exists) {
      return Optional.empty();
    }
    final Optional<Path> match;
    try (final Stream<Path> files = Files.walk(root, MAX_SEARCH_DEPTH)) {
      final Stream<Path> matching = files.filter(file -> matchesFileName(file, pattern));
      match = matching.findFirst();
    }
    if (match.isEmpty()) {
      return Optional.empty();
    }
    final Path library = match.get();
    final Path parentOrNull = library.getParent();
    final Path parent = Objects.requireNonNull(parentOrNull, "A file found below a directory has a parent");
    return Optional.of(parent);
  }

  private static boolean matchesFileName(final Path file, final Pattern pattern) {
    final Path fileName = file.getFileName();
    final String name = Objects.toString(fileName, "");
    final Matcher matcher = pattern.matcher(name);
    final boolean matches = matcher.matches();
    return matches && Files.isRegularFile(file);
  }

  /**
   * Runs an external tool and fails if it does not exit successfully. A tool that runs longer than ten minutes is
   * killed.
   *
   * @param workingDirectory the working directory of the tool, or null for the working directory of the JVM
   * @param arguments        the tool followed by its arguments
   * @throws IOException if the tool cannot be run or exits with an error
   */
  protected void runProcess(final @Nullable Path workingDirectory, final String... arguments) throws IOException {
    this.processRunner.run(workingDirectory, arguments);
  }

  private static void runCommand(final @Nullable Path workingDirectory, final String... arguments) throws IOException {
    final CommandTask task = new CommandTask(workingDirectory, arguments);
    try {
      task.runChecked(TOOL_TIMEOUT);
    } catch (final ProcessException exception) {
      final String reason = exception.getMessage();
      throw new IOException(reason, exception);
    }
  }

  /**
   * Deletes a file or a directory tree. Missing paths are ignored, and symbolic links are deleted without following
   * them, including links whose target no longer exists.
   *
   * @param path the file or directory to delete
   * @throws IOException if a file cannot be deleted
   * @see IOUtils#deleteRecursively(Path)
   */
  public static void deleteRecursively(final Path path) throws IOException {
    Preconditions.checkNotNull(path, "Path must not be null");
    IOUtils.deleteRecursively(path);
  }

  /**
   * Deletes a single file if it exists.
   *
   * @param file the file to delete
   * @throws IOException if the file cannot be deleted
   */
  public void deleteFile(final Path file) throws IOException {
    Preconditions.checkNotNull(file, "File must not be null");
    Files.deleteIfExists(file);
  }

  /**
   * Runs an external tool and fails when it exits with an error.
   */
  @FunctionalInterface
  public interface ProcessRunner {
    /**
     * Runs a tool to completion.
     *
     * @param workingDirectory the directory to run the tool in, or {@code null} for the working directory of the JVM
     * @param arguments        the tool followed by its arguments
     * @throws IOException if the tool cannot be started or exits with an error
     */
    void run(@Nullable Path workingDirectory, String... arguments) throws IOException;
  }
}
