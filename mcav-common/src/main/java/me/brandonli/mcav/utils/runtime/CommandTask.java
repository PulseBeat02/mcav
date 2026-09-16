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
package me.brandonli.mcav.utils.runtime;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs an external program and captures its output.
 *
 * <p>The standard output and the standard error of the program are drained on separate threads while the program
 * runs, so a program that prints a lot of text can never block. Both streams are available as strings after the
 * program finished. Nothing is printed to the console of the JVM.
 *
 * <pre><code>
 *   final CommandTask task = new CommandTask("ffmpeg", "-version");
 *   final int exitCode = task.run();
 *   final String output = task.getOutput();
 * </code></pre>
 *
 * <p>{@link #run(Duration)} limits how long the program may run and kills it, together with the processes it started,
 * when it takes longer. A task can be run only once. Instances are not thread-safe.
 */
public final class CommandTask {

  private static final Logger LOGGER = LoggerFactory.getLogger(CommandTask.class);
  private static final int NOT_RUN = Integer.MIN_VALUE;

  private final List<String> command;
  private final @Nullable Path workingDirectory;

  private @Nullable Process process;
  private String output;
  private String errorOutput;
  private int exitCode;

  /**
   * Creates a task that runs the command in the working directory of the JVM.
   *
   * @param command the program followed by its arguments
   */
  public CommandTask(final String... command) {
    this(null, command);
  }

  /**
   * Creates a task that runs the command in the specified working directory.
   *
   * @param workingDirectory the working directory of the program, or null for the working directory of the JVM
   * @param command          the program followed by its arguments
   */
  public CommandTask(final @Nullable Path workingDirectory, final String... command) {
    Preconditions.checkNotNull(command, "Command must not be null");
    Preconditions.checkArgument(command.length > 0, "Command must not be empty");
    this.command = List.of(command);
    this.workingDirectory = workingDirectory;
    this.output = "";
    this.errorOutput = "";
    this.exitCode = NOT_RUN;
  }

  /**
   * Creates a task and optionally runs it immediately.
   *
   * @param command       the program followed by its arguments
   * @param runOnCreation true to run the command before the constructor returns
   * @throws IOException if the program cannot be started
   */
  public CommandTask(final String[] command, final boolean runOnCreation) throws IOException {
    this(null, command);
    if (runOnCreation) {
      this.run();
    }
  }

  /**
   * Runs the program and waits until it exits, however long that takes. Prefer {@link #run(Duration)} for programs
   * that may hang, such as programs that talk to a network.
   *
   * @return the exit code of the program
   * @throws IOException           if the program cannot be started or its output cannot be read
   * @throws IllegalStateException if the task was already run
   */
  public int run() throws IOException {
    return this.runWithTimeout(null);
  }

  /**
   * Runs the program and waits at most the timeout for it to exit. A program that is still running when the timeout
   * passes is killed together with the processes it started, and the method fails.
   *
   * @param timeout how long to wait for the program, which must be positive
   * @return the exit code of the program
   * @throws IOException           if the program cannot be started, its output cannot be read, or it does not exit
   *                               in time
   * @throws IllegalStateException if the task was already run
   */
  public int run(final Duration timeout) throws IOException {
    checkTimeout(timeout);
    return this.runWithTimeout(timeout);
  }

  private static void checkTimeout(final Duration timeout) {
    Preconditions.checkNotNull(timeout, "Timeout must not be null");
    final boolean positive = !timeout.isNegative() && !timeout.isZero();
    Preconditions.checkArgument(positive, "Timeout must be positive but was %s", timeout);
  }

  private int runWithTimeout(final @Nullable Duration timeout) throws IOException {
    Preconditions.checkState(this.process == null, "Command has already been run");
    final Process started = this.startProcess();
    this.process = started;
    final InputStream standardOutput = started.getInputStream();
    final InputStream standardError = started.getErrorStream();
    // both streams are drained on their own threads, so a process that fills one pipe never blocks on it; a shared
    // pool could run the readers one after another and deadlock exactly like that
    try (final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
      final Future<String> outputFuture = readers.submit(() -> readFully(standardOutput));
      final Future<String> errorFuture = readers.submit(() -> readFully(standardError));
      this.awaitCompletion(started, outputFuture, errorFuture, timeout);
    }
    LOGGER.debug("Command {} exited with code {}", this.command, this.exitCode);
    return this.exitCode;
  }

  /**
   * Runs the program, waits until it exits, and fails if the exit code is not zero.
   *
   * @throws IOException      if the program cannot be started or its output cannot be read
   * @throws ProcessException if the program exits with a non-zero exit code
   */
  public void runChecked() throws IOException {
    final int code = this.run();
    this.checkExitCode(code);
  }

  /**
   * Runs the program, waits at most the timeout for it to exit, and fails if the exit code is not zero. A program
   * that is still running when the timeout passes is killed together with the processes it started.
   *
   * @param timeout how long to wait for the program, which must be positive
   * @throws IOException      if the program cannot be started, its output cannot be read, or it does not exit in
   *                          time
   * @throws ProcessException if the program exits with a non-zero exit code
   */
  public void runChecked(final Duration timeout) throws IOException {
    final int code = this.run(timeout);
    this.checkExitCode(code);
  }

  private void checkExitCode(final int code) {
    if (code == 0) {
      return;
    }
    final String strippedError = this.errorOutput.strip();
    final String message = "Command %s exited with code %d: %s".formatted(this.command, code, strippedError);
    throw new ProcessException(message);
  }

  private Process startProcess() throws IOException {
    final ProcessBuilder builder = new ProcessBuilder(this.command);
    final Path directory = this.workingDirectory;
    if (directory != null) {
      final File directoryFile = directory.toFile();
      builder.directory(directoryFile);
    }
    LOGGER.debug("Running command {}", this.command);
    return builder.start();
  }

  @VisibleForTesting
  void awaitCompletion(
    final Process started,
    final Future<String> outputFuture,
    final Future<String> errorFuture,
    final @Nullable Duration timeout
  ) throws IOException {
    try {
      this.exitCode = this.waitForExit(started, timeout);
      this.output = outputFuture.get();
      this.errorOutput = errorFuture.get();
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      destroyProcessTree(started);
      throw new IOException("Interrupted while waiting for command " + this.command, exception);
    } catch (final ExecutionException exception) {
      final Throwable cause = exception.getCause();
      throw new IOException("Failed to read the output of command " + this.command, cause);
    }
  }

  private int waitForExit(final Process started, final @Nullable Duration timeout) throws InterruptedException, IOException {
    if (timeout == null) {
      return started.waitFor();
    }
    final boolean exited = started.waitFor(timeout);
    if (exited) {
      return started.exitValue();
    }
    // the pipes only close once every process holding them is gone, so the children are killed as well
    destroyProcessTree(started);
    throw new IOException("Command %s did not finish within %s and was killed".formatted(this.command, timeout));
  }

  private static void destroyProcessTree(final Process started) {
    final Stream<ProcessHandle> descendants = started.descendants();
    descendants.forEach(ProcessHandle::destroyForcibly);
    started.destroyForcibly();
  }

  @VisibleForTesting
  static String readFully(final InputStream stream) {
    try (stream) {
      final byte[] bytes = stream.readAllBytes();
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new ProcessException(message, exception);
    }
  }

  /**
   * Gets the standard output of the program.
   *
   * @return the standard output, or an empty string if the task has not been run
   */
  public String getOutput() {
    return this.output;
  }

  /**
   * Gets the standard error of the program.
   *
   * @return the standard error, or an empty string if the task has not been run
   */
  public String getErrorOutput() {
    return this.errorOutput;
  }

  /**
   * Gets the exit code of the program.
   *
   * @return the exit code
   * @throws IllegalStateException if the task has not been run
   */
  public int getExitCode() {
    Preconditions.checkState(this.exitCode != NOT_RUN, "Command has not been run");
    return this.exitCode;
  }

  /**
   * Gets the program and its arguments.
   *
   * @return the command as an unmodifiable list
   */
  public List<String> getCommand() {
    return this.command;
  }

  /**
   * Gets the process of the program.
   *
   * @return the process, or null if the task has not been run
   */
  public @Nullable Process getProcess() {
    return this.process;
  }
}
