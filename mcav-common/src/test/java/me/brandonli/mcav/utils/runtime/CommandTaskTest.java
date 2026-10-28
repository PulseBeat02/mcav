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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CommandTaskTest {

  private static final String BINARY_DIRECTORY = "bin";
  private static final String WINDOWS_JAVA_EXECUTABLE = "java.exe";
  private static final String JAVA_EXECUTABLE = "java";
  private static final String SLEEPER_SOURCE_FILE = "Sleeper.java";
  private static final String SPAWNER_SOURCE_FILE = "Spawner.java";
  private static final String ENVIRONMENT_SOURCE_FILE = "Environment.java";

  private static final String VERSION_OPTION = "-version";
  private static final String UNKNOWN_OPTION = "-XX:+NoSuchOptionForMcav";

  private static final String VARIABLE = "MCAV_COMMAND_TASK_VARIABLE";

  private static final long EXIT_TIMEOUT_SECONDS = 10L;
  private static final long RETURN_DEADLINE_NANOSECONDS = 15_000_000_000L;

  @TempDir
  private Path directory;

  private static String javaExecutable() {
    final String javaHome = System.getProperty("java.home");
    final String launcherName = File.separatorChar == '\\' ? WINDOWS_JAVA_EXECUTABLE : JAVA_EXECUTABLE;
    final Path launcher = Path.of(javaHome, BINARY_DIRECTORY, launcherName);
    return launcher.toString();
  }

  private String sleeperProgram() throws IOException {
    final Path program = this.directory.resolve(SLEEPER_SOURCE_FILE);
    Files.writeString(program, "class Sleeper { public static void main(String[] args) throws Exception { Thread.sleep(30_000L); } }");
    return program.toString();
  }

  // A program that starts the sleeper, prints its pid and ends a second later, leaving the sleeper running.
  private String spawnerProgram() throws IOException {
    final Path program = this.directory.resolve(SPAWNER_SOURCE_FILE);
    Files.writeString(
      program,
      "class Spawner { public static void main(String[] args) throws Exception { System.out.println(new ProcessBuilder(args[0], args[1]).start().pid()); System.out.flush(); Thread.sleep(1_000L); } }"
    );
    return program.toString();
  }

  private String environmentProgram() throws IOException {
    final Path program = this.directory.resolve(ENVIRONMENT_SOURCE_FILE);
    Files.writeString(
      program,
      "class Environment { public static void main(String[] args) { System.out.print(System.getenv(args[0])); } }"
    );
    return program.toString();
  }

  @Test
  void shouldCaptureStandardOutputAndTheExitCode() throws IOException {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(java, "--version");
    final int exitCode = task.run();
    final String output = task.getOutput();
    final int storedExitCode = task.getExitCode();
    assertEquals(0, exitCode);
    assertEquals(0, storedExitCode);

    final boolean outputBlank = output.isBlank();
    assertFalse(outputBlank);
  }

  @Test
  void shouldCaptureStandardError() throws IOException {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(java, VERSION_OPTION);
    task.runChecked();
    final String errorOutput = task.getErrorOutput();
    final boolean errorOutputBlank = errorOutput.isBlank();
    assertFalse(errorOutputBlank);
  }

  @Test
  void shouldReportFailingCommands() throws IOException {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(java, UNKNOWN_OPTION);
    final int exitCode = task.run();
    final CommandTask checked = new CommandTask(java, UNKNOWN_OPTION);
    final int recordedExitCode = task.getExitCode();
    assertNotEquals(0, exitCode);
    assertEquals(exitCode, recordedExitCode);
    assertThrows(ProcessException.class, checked::runChecked);
  }

  @Test
  void shouldRunInTheWorkingDirectory() throws IOException {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(this.directory, java, "-XshowSettings:properties", VERSION_OPTION);
    task.run();
    final String errorOutput = task.getErrorOutput();
    final Path fileName = this.directory.getFileName();
    final String directoryName = fileName.toString();
    final boolean mentionsDirectory = errorOutput.contains(directoryName);
    assertTrue(mentionsDirectory, errorOutput);
  }

  @Test
  void shouldGiveTheProgramItsEnvironmentVariables() throws IOException {
    final String java = javaExecutable();
    final String programPath = this.environmentProgram();
    final Map<String, String> environment = Map.of(VARIABLE, "mcav");
    final CommandTask task = new CommandTask(environment, java, programPath, VARIABLE);
    task.runChecked();
    final String output = task.getOutput();
    final Map<String, String> taskEnvironment = task.getEnvironment();
    assertEquals("mcav", output);
    assertEquals(environment, taskEnvironment);
  }

  @Test
  void shouldRunOnCreation() throws IOException {
    final String java = javaExecutable();
    final String[] command = { java, VERSION_OPTION };
    final CommandTask ran = new CommandTask(command, RunMode.IMMEDIATELY);
    final CommandTask waiting = new CommandTask(command, RunMode.ON_DEMAND);
    final int exitCode = ran.getExitCode();
    final Optional<Process> process = waiting.getProcess();
    assertEquals(0, exitCode);
    assertTrue(process.isEmpty());
    assertThrows(IllegalStateException.class, waiting::getExitCode);
  }

  @Test
  void shouldRunOnlyOnce() throws IOException {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(java, VERSION_OPTION);
    task.run();
    final Optional<Process> process = task.getProcess();
    final List<String> command = task.getCommand();
    assertTrue(process.isPresent());

    final List<String> expectedCommand = List.of(java, VERSION_OPTION);
    assertEquals(expectedCommand, command);
    assertThrows(IllegalStateException.class, task::run);
  }

  @Test
  void shouldStopWaitingWhenInterrupted() {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(java, VERSION_OPTION);
    final Thread current = Thread.currentThread();
    current.interrupt();
    final ProcessException exception = assertThrows(ProcessException.class, task::run);
    final boolean interrupted = Thread.interrupted();
    final Throwable cause = exception.getCause();
    assertTrue(interrupted, "the interrupt must be restored");
    assertInstanceOf(InterruptedException.class, cause);
  }

  @Test
  void shouldReportOutputThatCannotBeRead() throws IOException, InterruptedException {
    final String java = javaExecutable();
    final ProcessBuilder builder = new ProcessBuilder(java, VERSION_OPTION);
    final Process finished = builder.start();
    finished.waitFor();
    final CommandTask task = new CommandTask(java, VERSION_OPTION);
    final IOException readFailure = new IOException("broken pipe!");
    final Future<String> failedOutput = CompletableFuture.failedFuture(readFailure);
    final Future<String> errorOutput = CompletableFuture.completedFuture("");
    final ProcessException exception = assertThrows(ProcessException.class, () ->
      task.awaitCompletion(finished, failedOutput, errorOutput, null)
    );
    final Throwable cause = exception.getCause();
    assertEquals(readFailure, cause);
  }

  @Test
  void shouldEndTheProgramsThatOutlivedOutputThatCannotBeRead() throws IOException, InterruptedException {
    final String java = javaExecutable();
    final String spawnerProgram = this.spawnerProgram();
    final String sleeperProgram = this.sleeperProgram();
    final Process spawner = new ProcessBuilder(java, spawnerProgram, java, sleeperProgram).start();
    final String line;
    try (final BufferedReader output = new BufferedReader(new InputStreamReader(spawner.getInputStream(), StandardCharsets.US_ASCII))) {
      line = output.readLine();
    }

    final ProcessHandle sleeper = ProcessHandle.of(Long.parseLong(line.strip())).orElseThrow();
    try {
      final CommandTask task = new CommandTask(java);
      final Future<String> failedOutput = CompletableFuture.failedFuture(new IOException("broken pipe!"));
      final Future<String> errorOutput = CompletableFuture.completedFuture("");
      assertThrows(ProcessException.class, () -> task.awaitCompletion(spawner, failedOutput, errorOutput, null));

      final boolean ended = sleeper.onExit().completeOnTimeout(null, EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).join() != null;
      assertTrue(ended, "the sleep the shell left running is ended with it");
    } finally {
      sleeper.destroyForcibly();
    }
  }

  @Test
  void shouldEndAProgramThatAppearedAfterTheLastLookAtTheChildren() throws IOException, InterruptedException {
    final String java = javaExecutable();
    final String sleeperProgram = this.sleeperProgram();
    final Process sleeper = new ProcessBuilder(java, sleeperProgram).start();
    try {
      final ProcessHandle sleeperHandle = sleeper.toHandle();
      final Process parent = new LateChildProcess(sleeperHandle);
      final CommandTask task = new CommandTask(java);
      final Future<String> failedOutput = CompletableFuture.failedFuture(new IOException("broken pipe!"));
      final Future<String> errorOutput = CompletableFuture.completedFuture("");

      assertThrows(ProcessException.class, () -> task.awaitCompletion(parent, failedOutput, errorOutput, null));

      final boolean ended = sleeper.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(ended, "the child that appeared after the last look is ended too");
    } finally {
      sleeper.destroyForcibly();
    }
  }

  @Test
  void shouldWrapStreamFailuresWhileReading() throws IOException {
    try (final InputStream broken = new FailingStream()) {
      assertThrows(ProcessException.class, () -> CommandTask.readFully(broken));
    }
  }

  @Test
  void shouldRejectInvalidCommands() {
    assertThrows(IllegalArgumentException.class, CommandTask::new);
    assertThrows(NullPointerException.class, () -> new CommandTask((String[]) null));

    final String java = javaExecutable();
    final Map<String, String> nullValue = new HashMap<>();
    nullValue.put(VARIABLE, null);
    assertThrows(NullPointerException.class, () -> new CommandTask((Map<String, String>) null, java));
    assertThrows(NullPointerException.class, () -> new CommandTask(nullValue, java));
  }

  @Test
  void shouldKillAProgramThatRunsLongerThanTheTimeout() throws IOException, InterruptedException {
    final String java = javaExecutable();
    final String programPath = this.sleeperProgram();
    final CommandTask task = new CommandTask(java, programPath);
    final Duration timeout = Duration.ofMillis(500);
    final long start = System.nanoTime();
    final ProcessException exception = assertThrows(ProcessException.class, () -> task.run(timeout));
    final long elapsed = System.nanoTime() - start;
    final String message = exception.getMessage();
    final Optional<Process> launched = task.getProcess();
    assertTrue(launched.isPresent());

    final Process process = launched.orElseThrow();
    final boolean exited = process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean reportsTheTimeout = message.contains("did not finish");
    assertTrue(reportsTheTimeout, message);
    assertTrue(exited, "the program is killed instead of being left running");

    final boolean returnedInTime = elapsed < RETURN_DEADLINE_NANOSECONDS;
    assertTrue(returnedInTime, "run returns once the timeout passed, took " + elapsed + " ns");
    assertThrows(IllegalStateException.class, task::getExitCode);
  }

  @Test
  void shouldRunProgramsThatFinishWithinTheTimeout() throws IOException {
    final String java = javaExecutable();
    final Duration timeout = Duration.ofMinutes(1);
    final CommandTask task = new CommandTask(java, VERSION_OPTION);
    final int exitCode = task.run(timeout);
    final String errorOutput = task.getErrorOutput();
    final CommandTask checked = new CommandTask(java, VERSION_OPTION);
    checked.runChecked(timeout);
    final int checkedExitCode = checked.getExitCode();
    final CommandTask failing = new CommandTask(java, UNKNOWN_OPTION);
    assertEquals(0, exitCode);

    final boolean errorOutputBlank = errorOutput.isBlank();
    assertFalse(errorOutputBlank);
    assertEquals(0, checkedExitCode);
    assertThrows(ProcessException.class, () -> failing.runChecked(timeout));
  }

  @Test
  void shouldRejectTimeoutsThatAreNotPositive() {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(java, VERSION_OPTION);
    final Duration negative = Duration.ofSeconds(-1);
    assertThrows(NullPointerException.class, () -> task.run(null));
    assertThrows(IllegalArgumentException.class, () -> task.run(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> task.run(negative));
    assertThrows(IllegalArgumentException.class, () -> task.runChecked(Duration.ZERO));

    final Optional<Process> process = task.getProcess();
    assertTrue(process.isEmpty(), "an invalid timeout does not start the program");
  }

  @Test
  void shouldKillTheProgramWhenInterrupted() throws IOException, InterruptedException {
    final String java = javaExecutable();
    final String programPath = this.sleeperProgram();
    final CommandTask task = new CommandTask(java, programPath);
    final Thread current = Thread.currentThread();
    final long start = System.nanoTime();
    current.interrupt();
    assertThrows(ProcessException.class, task::run);

    final long elapsed = System.nanoTime() - start;
    final boolean interrupted = Thread.interrupted();
    final Optional<Process> launched = task.getProcess();
    assertTrue(launched.isPresent());

    final Process process = launched.orElseThrow();
    final boolean exited = process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(interrupted);
    assertTrue(exited, "the program is killed instead of being left running");

    final boolean returnedInTime = elapsed < RETURN_DEADLINE_NANOSECONDS;
    assertTrue(returnedInTime, "run returns without waiting for the program, took " + elapsed + " ns");
  }

  private static final class LateChildProcess extends Process {

    private final ProcessHandle child;

    private boolean looked;

    LateChildProcess(final ProcessHandle child) {
      this.child = child;
    }

    @Override
    public Stream<ProcessHandle> descendants() {
      if (!this.looked) {
        this.looked = true;

        return Stream.empty();
      }

      return Stream.of(this.child);
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {}

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }
  }

  private static final class FailingStream extends InputStream {

    @Override
    public int read() throws IOException {
      throw new IOException("stream failed!");
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
      throw new IOException("stream failed!");
    }
  }
}
