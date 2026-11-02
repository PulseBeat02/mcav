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
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.vlc.VlcInstaller;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.runtime.CommandTask;
import me.brandonli.mcav.utils.runtime.ProcessException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The base class of the VLC installation strategies, with helpers for running external tools and for locating the
 * VLC libraries inside an extracted installation.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public abstract class ManualInstallationStrategy implements InstallationStrategy {

  private static final int MAXIMUM_SEARCH_DEPTH = 8;

  private static final Duration TOOL_TIMEOUT = Duration.ofMinutes(10);
  private static final ProcessRunner COMMAND_RUNNER = ManualInstallationStrategy::runCommand;

  private final VlcInstaller installer;
  private final ProcessRunner processRunner;

  /**
   * Creates a new strategy for the installer that runs external tools as child processes.
   *
   * @param installer the installer whose installation directory is used
   * @throws NullPointerException if {@code installer} is null
   * @since 1.0.0
   */
  protected ManualInstallationStrategy(final VlcInstaller installer) {
    this(installer, COMMAND_RUNNER);
  }

  /**
   * Creates a new strategy for the installer that runs external tools through the specified runner.
   *
   * @param installer     the installer whose installation directory is used
   * @param processRunner runs the external tools the installation needs
   * @throws NullPointerException if {@code installer} or {@code processRunner} is null
   * @since 1.0.0
   */
  protected ManualInstallationStrategy(final VlcInstaller installer, final ProcessRunner processRunner) {
    Preconditions.checkNotNull(installer, "Installer must not be null!");
    Preconditions.checkNotNull(processRunner, "Process runner must not be null!");

    this.installer = installer;
    this.processRunner = processRunner;
  }

  /**
   * Finds the first nonempty library matching one pattern, searching at most eight levels below the root.
   *
   * @param root    the directory to search
   * @param pattern the complete library file-name pattern
   * @return the parent directory of a matching library, or empty if none exists
   * @throws InstallationException if a candidate directory or library cannot be read
   * @throws NullPointerException  if {@code root} or {@code pattern} is null
   * @since 1.0.0
   */
  protected static Optional<Path> findLibraryDirectory(final Path root, final Pattern pattern) {
    return findLibraryDirectory(root, pattern, new Pattern[0]);
  }

  /**
   * Finds a directory containing nonempty regular files matching every required pattern. File symlinks are accepted
   * when their targets are regular and nonempty. The recursive search does not follow directory symlinks, and files
   * are searched at most eight levels below the root.
   *
   * @param root       the directory to search
   * @param pattern    the pattern for the main library file name
   * @param companions additional library patterns that must match files in the same directory
   * @return the first complete library directory, or empty if no complete directory exists
   * @throws InstallationException if a candidate directory or library cannot be read
   * @throws NullPointerException  if {@code root}, {@code pattern}, the companion array or a companion pattern is null
   * @since 1.0.0
   */
  protected static Optional<Path> findLibraryDirectory(final Path root, final Pattern pattern, final Pattern... companions) {
    Preconditions.checkNotNull(root, "Root must not be null!");
    Preconditions.checkNotNull(pattern, "Pattern must not be null!");
    Preconditions.checkNotNull(companions, "Companion patterns must not be null!");

    for (final Pattern companion : companions) {
      Preconditions.checkNotNull(companion, "Companion patterns must not contain null!");
    }

    if (!Files.isDirectory(root)) {
      return Optional.empty();
    }

    try (final Stream<Path> files = Files.walk(root, MAXIMUM_SEARCH_DEPTH)) {
      final Stream<Path> matching = files.filter(file -> matchesFileName(file, pattern));
      final Iterator<Path> candidates = matching.iterator();
      return findCompleteDirectory(candidates, companions);
    } catch (final IOException exception) {
      final String message = "Directory %s could not be searched for the VLC libraries!".formatted(root);
      throw new InstallationException(message, exception);
    }
  }

  private static Optional<Path> findCompleteDirectory(final Iterator<Path> candidates, final Pattern[] companions) throws IOException {
    while (candidates.hasNext()) {
      final Path library = candidates.next();
      final Path parentOrNull = library.getParent();
      final Path parent = Objects.requireNonNull(parentOrNull, "A file found below a directory has a parent!");
      if (Files.size(library) > 0 && containsCompanions(parent, companions)) {
        return Optional.of(parent);
      }
    }

    return Optional.empty();
  }

  private static boolean containsCompanions(final Path directory, final Pattern[] companions) throws IOException {
    for (final Pattern companion : companions) {
      if (!containsNonemptyLibrary(directory, companion)) {
        return false;
      }
    }

    return true;
  }

  private static boolean containsNonemptyLibrary(final Path directory, final Pattern pattern) throws IOException {
    try (final Stream<Path> files = Files.list(directory)) {
      final Stream<Path> matching = files.filter(file -> matchesFileName(file, pattern));
      final Iterator<Path> candidates = matching.iterator();
      return containsNonemptyFile(candidates);
    }
  }

  private static boolean containsNonemptyFile(final Iterator<Path> candidates) throws IOException {
    while (candidates.hasNext()) {
      final Path library = candidates.next();
      if (Files.size(library) > 0) {
        return true;
      }
    }

    return false;
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
   * killed by the default runner. An injected {@link ProcessRunner} defines its own timeout behavior.
   *
   * @param workingDirectory the working directory of the tool, or null for the working directory of the JVM
   * @param arguments        the tool followed by its arguments
   * @throws InstallationException if the tool cannot be run or exits with an error
   * @since 1.0.0
   */
  protected void runProcess(final @Nullable Path workingDirectory, final String... arguments) {
    this.processRunner.run(workingDirectory, arguments);
  }

  private static void runCommand(final @Nullable Path workingDirectory, final String... arguments) {
    final CommandTask task = new CommandTask(workingDirectory, arguments);
    try {
      task.runChecked(TOOL_TIMEOUT);
    } catch (final ProcessException exception) {
      final List<String> command = task.getCommand();
      final String message = "Installation tool %s failed!".formatted(command);
      throw new InstallationException(message, exception);
    }
  }

  /**
   * Deletes a file or a directory tree. Missing paths are ignored, and symbolic links are deleted without following
   * them, including links whose target no longer exists.
   *
   * @param path the file or directory to delete
   * @throws FileOperationException if a file cannot be deleted
   * @throws NullPointerException   if {@code path} is null
   * @see IoUtils#deleteRecursively(Path)
   * @since 1.0.0
   */
  public static void deleteRecursively(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null!");

    IoUtils.deleteRecursively(path);
  }

  /**
   * Deletes a single file if it exists.
   *
   * @param file the file to delete
   * @throws FileOperationException if the file cannot be deleted
   * @throws NullPointerException   if {@code file} is null
   * @since 1.0.0
   */
  public void deleteFile(final Path file) {
    Preconditions.checkNotNull(file, "File must not be null!");

    try {
      Files.deleteIfExists(file);
    } catch (final IOException exception) {
      final String message = "File %s could not be deleted!".formatted(file);
      throw new FileOperationException(message, exception);
    }
  }

  /**
   * Returns the installer this strategy installs for.
   *
   * @return the installer
   * @since 1.0.0
   */
  public VlcInstaller getInstaller() {
    return this.installer;
  }

  /**
   * Returns the directory VLC is installed into.
   *
   * @return the installation directory
   * @since 1.0.0
   */
  protected Path getInstallDirectory() {
    return this.installer.getInstallDirectory();
  }

  /**
   * Runs an external tool and fails when it exits with an error.
   *
   * <p>Implementations in this library are stateless and thread-safe.
   *
   * @since 1.0.0
   */
  @FunctionalInterface
  public interface ProcessRunner {
    /**
     * Runs a tool to completion.
     *
     * @param workingDirectory the directory to run the tool in, or {@code null} for the working directory of the JVM
     * @param arguments        the tool followed by its arguments
     * @throws InstallationException if the tool cannot be started or exits with an error
     * @since 1.0.0
     */
    void run(@Nullable Path workingDirectory, String... arguments);
  }
}
