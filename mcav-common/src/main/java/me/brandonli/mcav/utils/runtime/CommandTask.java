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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * {@snippet :
 * final String executable = "ffmpeg";
 * final String versionOption = "-version";
 * final CommandTask task = new CommandTask(executable, versionOption);
 * final int exitCode = task.run();
 * final String output = task.getOutput();
 * }
 *
 * <p>{@link #run(Duration)} uses one deadline for the program and both captured streams. At the deadline it requests
 * termination of the program and the descendants observed while it ran. Descendant discovery is best effort:
 * an operating system can reparent a very short-lived program's children before they can be observed. After a successful launch a task cannot run again, including after a wait failure. A failed launch may be
 * retried. Instances are not thread-safe.
 *
 * @since 1.0.0
 */
public final class CommandTask {

  private static final Logger LOGGER = LoggerFactory.getLogger(CommandTask.class);
  private static final int NOT_RUN = Integer.MIN_VALUE;
  private static final long PROCESS_POLL_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(20L);

  private final List<String> command;
  private final @Nullable Path workingDirectory;
  private final Map<String, String> environment;
  private final Set<ProcessHandle> descendants;

  private @Nullable Process process;
  private String output;
  private String errorOutput;
  private int exitCode;

  /**
   * Creates a task that runs the command in the working directory of the JVM.
   *
   * @param command the program followed by its arguments
   * @throws NullPointerException     if the command array or any argument is null
   * @throws IllegalArgumentException if the command array is empty
   * @since 1.0.0
   */
  public CommandTask(final String... command) {
    this(null, Map.of(), command);
  }

  /**
   * Creates a task that runs the command in the specified working directory.
   *
   * @param workingDirectory the working directory of the program, or null for the working directory of the JVM
   * @param command          the program followed by its arguments
   * @throws NullPointerException     if the command array or any argument is null
   * @throws IllegalArgumentException if the command array is empty
   * @since 1.0.0
   */
  public CommandTask(final @Nullable Path workingDirectory, final String... command) {
    this(workingDirectory, Map.of(), command);
  }

  /**
   * Creates a task that runs the command in the working directory of the JVM, with environment variables besides
   * those it inherits from the JVM. A variable replaces an inherited variable of the same name.
   *
   * @param environment the environment variables to set
   * @param command     the program followed by its arguments
   * @throws NullPointerException     if the environment, a variable name or value, the command array or any argument
   *                                  is null
   * @throws IllegalArgumentException if the command array is empty
   * @since 1.0.0
   */
  public CommandTask(final Map<String, String> environment, final String... command) {
    this(null, environment, command);
  }

  private CommandTask(final @Nullable Path workingDirectory, final Map<String, String> environment, final String[] command) {
    Preconditions.checkNotNull(environment, "Environment must not be null!");
    Preconditions.checkNotNull(command, "Command must not be null!");
    Preconditions.checkArgument(command.length > 0, "Command must not be empty!");

    this.command = List.of(command);
    this.workingDirectory = workingDirectory;
    this.environment = Map.copyOf(environment);
    this.descendants = new HashSet<>();
    this.output = "";
    this.errorOutput = "";
    this.exitCode = NOT_RUN;
  }

  /**
   * Creates a task that runs either before the constructor returns or when it is asked to.
   *
   * @param command the program followed by its arguments
   * @param mode    when to run the command
   * @throws ProcessException         if the program cannot be started or its output cannot be read
   * @throws NullPointerException     if the command array or any argument is null
   * @throws IllegalArgumentException if the command array is empty
   * @since 1.0.0
   */
  public CommandTask(final String[] command, final RunMode mode) {
    this(null, Map.of(), command);

    if (mode == RunMode.IMMEDIATELY) {
      this.run();
    }
  }

  /**
   * Runs the program and waits until it exits, however long that takes. Prefer {@link #run(Duration)} for programs
   * that may hang, such as programs that talk to a network.
   *
   * @return the exit code of the program
   * @throws ProcessException     if the program cannot be started or its output cannot be read
   * @throws IllegalStateException if the task was already run
   * @since 1.0.0
   */
  public int run() {
    return this.runWithTimeout(null);
  }

  /**
   * Runs the program and waits at most one timeout for its exit and both captured output streams. On timeout,
   * termination of the program and its observed descendants is requested and the method fails.
   *
   * @param timeout how long to wait for the program, which must be positive
   * @return the exit code of the program
   * @throws ProcessException         if the program cannot be started, its output cannot be read, or it does not exit
   *                                  in time
   * @throws IllegalStateException    if the task was already run
   * @throws NullPointerException     if {@code timeout} is null
   * @throws IllegalArgumentException if the timeout is zero or negative
   * @since 1.0.0
   */
  public int run(final Duration timeout) {
    checkTimeout(timeout);

    return this.runWithTimeout(timeout);
  }

  private static void checkTimeout(final Duration timeout) {
    Preconditions.checkNotNull(timeout, "Timeout must not be null!");

    final boolean positive = !timeout.isNegative() && !timeout.isZero();
    Preconditions.checkArgument(positive, "Timeout %s must be positive!", timeout);
  }

  private int runWithTimeout(final @Nullable Duration timeout) {
    Preconditions.checkState(this.process == null, "Command has already been run!");

    final long startNanoseconds = System.nanoTime();
    final Process started = this.startProcess();
    this.process = started;
    final InputStream standardOutput = started.getInputStream();
    final InputStream standardError = started.getErrorStream();
    // both streams are drained on their own threads, so a process that fills one pipe never blocks on it; a shared
    // pool could run the readers one after another and deadlock exactly like that
    final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    try {
      final Future<String> outputFuture = readers.submit(() -> readFully(standardOutput));
      final Future<String> errorFuture = readers.submit(() -> readFully(standardError));
      this.awaitCompletion(started, outputFuture, errorFuture, timeout, startNanoseconds);
    } finally {
      // close() waits indefinitely for blocked pipe readers. Cancellation must not extend the caller's deadline.
      readers.shutdownNow();
    }

    LOGGER.debug("Command {} exited with code {}.", this.command, this.exitCode);
    return this.exitCode;
  }

  /**
   * Runs the program, waits until it exits, and fails if the exit code is not zero.
   *
   * @throws ProcessException     if the program cannot be started, its output cannot be read, or it exits with a
   *                               non-zero exit code
   * @throws IllegalStateException if a process was already launched by this task
   * @since 1.0.0
   */
  public void runChecked() {
    final int code = this.run();
    this.checkExitCode(code);
  }

  /**
   * Runs the program, applies one timeout to its exit and output capture, and fails if the exit code is not zero.
   * When the timeout passes, termination of the program and its observed descendants is requested.
   *
   * @param timeout how long to wait for the program, which must be positive
   * @throws ProcessException         if the program cannot be started, its output cannot be read, it does not exit in
   *                                  time, or it exits with a non-zero exit code
   * @throws NullPointerException     if {@code timeout} is null
   * @throws IllegalArgumentException if the timeout is zero or negative
   * @throws IllegalStateException    if a process was already launched by this task
   * @since 1.0.0
   */
  public void runChecked(final Duration timeout) {
    final int code = this.run(timeout);
    this.checkExitCode(code);
  }

  private void checkExitCode(final int code) {
    if (code == 0) {
      return;
    }

    final String strippedError = this.errorOutput.strip();
    final String message = "Command %s exited with code %s, printing \"%s\" to its error stream!".formatted(
      this.command,
      code,
      strippedError
    );
    throw new ProcessException(message);
  }

  private Process startProcess() {
    final ProcessBuilder builder = new ProcessBuilder(this.command);
    final Path directory = this.workingDirectory;
    if (directory != null) {
      final File directoryFile = directory.toFile();
      builder.directory(directoryFile);
    }

    final Map<String, String> variables = builder.environment();
    variables.putAll(this.environment);

    LOGGER.debug("Command {} is running.", this.command);
    try {
      return builder.start();
    } catch (final IOException exception) {
      final String message = "Command %s could not be started!".formatted(this.command);
      throw new ProcessException(message, exception);
    }
  }

  @VisibleForTesting
  void awaitCompletion(
    final Process started,
    final Future<String> outputFuture,
    final Future<String> errorFuture,
    final @Nullable Duration timeout
  ) {
    final long startNanoseconds = System.nanoTime();
    this.awaitCompletion(started, outputFuture, errorFuture, timeout, startNanoseconds);
  }

  private void awaitCompletion(
    final Process started,
    final Future<String> outputFuture,
    final Future<String> errorFuture,
    final @Nullable Duration timeout,
    final long startNanoseconds
  ) {
    try {
      this.exitCode = this.waitForExit(started, timeout, startNanoseconds);
      this.output = awaitOutput(outputFuture, timeout, startNanoseconds);
      this.errorOutput = awaitOutput(errorFuture, timeout, startNanoseconds);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      this.destroyProcessTree(started);
      final String message = "Waiting for command %s was interrupted!".formatted(this.command);
      throw new ProcessException(message, exception);
    } catch (final TimeoutException exception) {
      this.destroyProcessTree(started);
      final String message = "Command %s did not finish within %s; termination was requested!".formatted(this.command, timeout);
      throw new ProcessException(message, exception);
    } catch (final ExecutionException exception) {
      this.destroyProcessTree(started);
      final Throwable reason = exception.getCause();
      final Throwable cause = Objects.requireNonNullElse(reason, exception);
      final String message = "Output of command %s could not be read!".formatted(this.command);
      throw new ProcessException(message, cause);
    } finally {
      outputFuture.cancel(true);
      errorFuture.cancel(true);
    }
  }

  private static String awaitOutput(final Future<String> output, final @Nullable Duration timeout, final long startNanoseconds)
    throws ExecutionException, InterruptedException, TimeoutException {
    if (timeout == null) {
      return output.get();
    }

    final long remaining = remainingNanoseconds(timeout, startNanoseconds);
    return output.get(remaining, TimeUnit.NANOSECONDS);
  }

  private static long remainingNanoseconds(final Duration timeout, final long startNanoseconds) throws TimeoutException {
    final long budget = TimeUnit.NANOSECONDS.convert(timeout);
    final long elapsed = System.nanoTime() - startNanoseconds;
    final long remaining = budget - elapsed;
    if (remaining <= 0) {
      throw new TimeoutException("The command and output deadline expired!");
    }

    return remaining;
  }

  private int waitForExit(final Process started, final @Nullable Duration timeout, final long startNanoseconds)
    throws InterruptedException, TimeoutException {
    while (true) {
      // Keep handles while the parent exists: after it exits, inherited-pipe children may already be reparented.
      this.rememberDescendants(started);
      final long remaining = timeout == null ? PROCESS_POLL_NANOSECONDS : remainingNanoseconds(timeout, startNanoseconds);
      final long waitNanoseconds = Math.min(remaining, PROCESS_POLL_NANOSECONDS);
      final boolean exited = started.waitFor(waitNanoseconds, TimeUnit.NANOSECONDS);
      if (exited) {
        return started.exitValue();
      }
    }
  }

  private void rememberDescendants(final Process started) {
    try (final Stream<ProcessHandle> children = started.descendants()) {
      children.forEach(this.descendants::add);
    }
  }

  private void destroyProcessTree(final Process started) {
    this.rememberDescendants(started);
    this.descendants.forEach(ProcessHandle::destroyForcibly);
    started.destroyForcibly();
  }

  @VisibleForTesting
  static String readFully(final InputStream stream) {
    try (stream) {
      final byte[] bytes = stream.readAllBytes();
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (final IOException exception) {
      final String message = "Process output could not be read!";
      throw new ProcessException(message, exception);
    }
  }

  /**
   * Returns the exit code of the program.
   *
   * @return the exit code
   * @throws IllegalStateException if no exit code was recorded, including after a launch or wait failure
   * @since 1.0.0
   */
  public int getExitCode() {
    Preconditions.checkState(this.exitCode != NOT_RUN, "Command has not been run!");

    return this.exitCode;
  }

  /**
   * Returns the standard output of the program.
   *
   * @return the standard output, or an empty string if the task has not been run
   * @since 1.0.0
   */
  public String getOutput() {
    return this.output;
  }

  /**
   * Returns the standard error of the program.
   *
   * @return the standard error, or an empty string if the task has not been run
   * @since 1.0.0
   */
  public String getErrorOutput() {
    return this.errorOutput;
  }

  /**
   * Returns the program and its arguments.
   *
   * @return the command as an unmodifiable list
   * @since 1.0.0
   */
  public List<String> getCommand() {
    return this.command;
  }

  /**
   * Returns the environment variables that the program gets besides those it inherits from the JVM.
   *
   * @return the environment variables as an unmodifiable map
   * @since 1.0.0
   */
  public Map<String, String> getEnvironment() {
    return this.environment;
  }

  /**
   * Returns the process of the program.
   *
   * @return the process after a successful launch, even if waiting failed, or empty if none was launched
   * @since 1.0.0
   */
  public Optional<Process> getProcess() {
    return Optional.ofNullable(this.process);
  }
}
