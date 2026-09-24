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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link CommandTask}, using the Java launcher of the running JVM as a program that exists everywhere.
 */
final class CommandTaskTest {

  @TempDir
  private Path directory;

  private static String javaExecutable() {
    final String javaHome = System.getProperty("java.home");
    final String launcherName = File.separatorChar == '\\' ? "java.exe" : "java";
    final Path launcher = Path.of(javaHome, "bin", launcherName);
    return launcher.toString();
  }

  private String sleeperProgram() throws IOException {
    final Path program = this.directory.resolve("Sleeper.java");
    Files.writeString(program, "class Sleeper { public static void main(String[] args) throws Exception { Thread.sleep(30_000L); } }");
    return program.toString();
  }

  @Test
  void capturesStandardOutputAndTheExitCode() throws IOException {
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
  void capturesStandardError() throws IOException {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(java, "-version");
    task.runChecked();
    final String errorOutput = task.getErrorOutput();
    final boolean errorOutputBlank = errorOutput.isBlank();
    assertFalse(errorOutputBlank);
  }

  @Test
  void reportsFailingCommands() throws IOException {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(java, "-XX:+NoSuchOptionForMcav");
    final int exitCode = task.run();
    final CommandTask checked = new CommandTask(java, "-XX:+NoSuchOptionForMcav");
    final int recordedExitCode = task.getExitCode();
    assertNotEquals(0, exitCode);
    assertEquals(exitCode, recordedExitCode);
    assertThrows(ProcessException.class, checked::runChecked);
  }

  @Test
  void runsInTheWorkingDirectory() throws IOException {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(this.directory, java, "-XshowSettings:properties", "-version");
    task.run();
    final String errorOutput = task.getErrorOutput();
    final Path fileName = this.directory.getFileName();
    final String directoryName = fileName.toString();
    final boolean mentionsDirectory = errorOutput.contains(directoryName);
    assertTrue(mentionsDirectory, errorOutput);
  }

  @Test
  void canRunOnCreation() throws IOException {
    final String java = javaExecutable();
    final String[] command = { java, "-version" };
    final CommandTask ran = new CommandTask(command, true);
    final CommandTask waiting = new CommandTask(command, false);
    final int exitCode = ran.getExitCode();
    final Process process = waiting.getProcess();
    assertEquals(0, exitCode);
    assertNull(process);
    assertThrows(IllegalStateException.class, waiting::getExitCode);
  }

  @Test
  void runsOnlyOnce() throws IOException {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(java, "-version");
    task.run();
    final Process process = task.getProcess();
    final List<String> command = task.getCommand();
    assertNotNull(process);
    final List<String> expectedCommand = List.of(java, "-version");
    assertEquals(expectedCommand, command);
    assertThrows(IllegalStateException.class, task::run);
  }

  @Test
  void stopsWaitingWhenInterrupted() {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(java, "-version");
    final Thread current = Thread.currentThread();
    current.interrupt();
    final IOException exception = assertThrows(IOException.class, task::run);
    final boolean interrupted = Thread.interrupted();
    final Throwable cause = exception.getCause();
    assertTrue(interrupted, "the interrupt must be restored");
    assertInstanceOf(InterruptedException.class, cause);
  }

  @Test
  void reportsOutputThatCannotBeRead() throws IOException, InterruptedException {
    final String java = javaExecutable();
    final ProcessBuilder builder = new ProcessBuilder(java, "-version");
    final Process finished = builder.start();
    finished.waitFor();
    final CommandTask task = new CommandTask(java, "-version");
    final IOException readFailure = new IOException("broken pipe");
    final Future<String> failedOutput = CompletableFuture.failedFuture(readFailure);
    final Future<String> errorOutput = CompletableFuture.completedFuture("");
    final IOException exception = assertThrows(IOException.class, () -> task.awaitCompletion(finished, failedOutput, errorOutput, null));
    final Throwable cause = exception.getCause();
    assertEquals(readFailure, cause);
  }

  @Test
  void wrapsStreamFailuresWhileReading() throws IOException {
    try (
      final InputStream broken = new InputStream() {
        @Override
        public int read() throws IOException {
          throw new IOException("stream failed");
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
          throw new IOException("stream failed");
        }
      }
    ) {
      assertThrows(ProcessException.class, () -> CommandTask.readFully(broken));
    }
  }

  @Test
  void rejectsInvalidCommands() {
    assertThrows(IllegalArgumentException.class, CommandTask::new);
    assertThrows(NullPointerException.class, () -> new CommandTask((String[]) null));
  }

  @Test
  void killsAProgramThatRunsLongerThanTheTimeout() throws IOException, InterruptedException {
    final String java = javaExecutable();
    final String programPath = this.sleeperProgram();
    final CommandTask task = new CommandTask(java, programPath);
    final Duration timeout = Duration.ofMillis(500);
    final long start = System.nanoTime();
    final IOException exception = assertThrows(IOException.class, () -> task.run(timeout));
    final long elapsed = System.nanoTime() - start;
    final String message = exception.getMessage();
    final Process process = task.getProcess();
    assertNotNull(process);
    final boolean exited = process.waitFor(10, TimeUnit.SECONDS);
    final boolean reportsTheTimeout = message.contains("did not finish");
    assertTrue(reportsTheTimeout, message);
    assertTrue(exited, "the program is killed instead of being left running");
    assertTrue(elapsed < 15_000_000_000L, "run returns once the timeout passed, took " + elapsed + " ns");
    assertThrows(IllegalStateException.class, task::getExitCode);
  }

  @Test
  void runsProgramsThatFinishWithinTheTimeout() throws IOException {
    final String java = javaExecutable();
    final Duration timeout = Duration.ofMinutes(1);
    final CommandTask task = new CommandTask(java, "-version");
    final int exitCode = task.run(timeout);
    final String errorOutput = task.getErrorOutput();
    final CommandTask checked = new CommandTask(java, "-version");
    checked.runChecked(timeout);
    final int checkedExitCode = checked.getExitCode();
    final CommandTask failing = new CommandTask(java, "-XX:+NoSuchOptionForMcav");
    assertEquals(0, exitCode);
    final boolean errorOutputBlank = errorOutput.isBlank();
    assertFalse(errorOutputBlank);
    assertEquals(0, checkedExitCode);
    assertThrows(ProcessException.class, () -> failing.runChecked(timeout));
  }

  @Test
  void rejectsTimeoutsThatAreNotPositive() {
    final String java = javaExecutable();
    final CommandTask task = new CommandTask(java, "-version");
    final Duration negative = Duration.ofSeconds(-1);
    assertThrows(NullPointerException.class, () -> task.run(null));
    assertThrows(IllegalArgumentException.class, () -> task.run(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> task.run(negative));
    assertThrows(IllegalArgumentException.class, () -> task.runChecked(Duration.ZERO));
    final Process process = task.getProcess();
    assertNull(process, "an invalid timeout does not start the program");
  }

  @Test
  void killsTheProgramWhenInterrupted() throws IOException, InterruptedException {
    final String java = javaExecutable();
    final String programPath = this.sleeperProgram();
    final CommandTask task = new CommandTask(java, programPath);
    final Thread current = Thread.currentThread();
    final long start = System.nanoTime();
    current.interrupt();
    assertThrows(IOException.class, task::run);
    final long elapsed = System.nanoTime() - start;
    final boolean interrupted = Thread.interrupted();
    final Process process = task.getProcess();
    assertNotNull(process);
    final boolean exited = process.waitFor(10, TimeUnit.SECONDS);
    assertTrue(interrupted);
    assertTrue(exited, "the program is killed instead of being left running");
    assertTrue(elapsed < 15_000_000_000L, "run returns without waiting for the program, took " + elapsed + " ns");
  }
}
