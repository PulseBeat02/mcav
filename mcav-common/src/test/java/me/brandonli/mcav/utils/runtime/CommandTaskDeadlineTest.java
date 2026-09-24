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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import me.brandonli.mcav.media.Polling;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests the single deadline covering process exit and inherited output pipes. */
final class CommandTaskDeadlineTest {

  @TempDir
  private Path directory;

  private static final class StringFuture extends CompletableFuture<String> {}

  private static Process finishedProcess() throws InterruptedException {
    final Process process = mock(Process.class);
    when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
    when(process.descendants()).thenAnswer(_ -> Stream.empty());
    return process;
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void anExitedParentDoesNotAllowAnOutputStreamToWaitForever(final boolean standardError) throws Exception {
    final Process finished = finishedProcess();
    final CompletableFuture<String> pending = new CompletableFuture<>();
    final CompletableFuture<String> complete = CompletableFuture.completedFuture("");
    final Future<String> output = standardError ? complete : pending;
    final Future<String> errors = standardError ? pending : complete;
    final CommandTask task = new CommandTask("already-finished");
    final Duration timeout = Duration.ofMillis(80);
    final long before = System.nanoTime();
    final IOException thrown = assertThrows(IOException.class, () -> task.awaitCompletion(finished, output, errors, timeout));
    final long elapsed = System.nanoTime() - before;
    final Throwable cause = thrown.getCause();
    final boolean cancelled = pending.isCancelled();
    assertInstanceOf(TimeoutException.class, cause);
    assertTrue(cancelled);
    assertTrue(elapsed < 1_000_000_000L, "the output wait uses the command deadline");
  }

  @Test
  void completedStreamsPreserveSeparateOutputWithinTheDeadline() throws Exception {
    final Process finished = finishedProcess();
    final Future<String> output = CompletableFuture.completedFuture("standard output");
    final Future<String> errors = CompletableFuture.completedFuture("standard error");
    final CommandTask task = new CommandTask("already-finished");
    final Duration timeout = Duration.ofSeconds(2);
    task.awaitCompletion(finished, output, errors, timeout);
    final String capturedOutput = task.getOutput();
    final String capturedErrors = task.getErrorOutput();
    assertEquals("standard output", capturedOutput);
    assertEquals("standard error", capturedErrors);
  }

  @Test
  void bothOutputStreamsShareTheSameRemainingBudget() throws Exception {
    final Process finished = finishedProcess();
    final Future<String> output = mock(StringFuture.class);
    final Future<String> errors = mock(StringFuture.class);
    final AtomicLong firstBudget = new AtomicLong();
    final AtomicLong secondBudget = new AtomicLong();
    when(output.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
      final long nanos = invocation.getArgument(0);
      firstBudget.set(nanos);
      Thread.sleep(30L);
      return "output";
    });
    when(errors.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
      final long nanos = invocation.getArgument(0);
      secondBudget.set(nanos);
      throw new TimeoutException("still reading error output");
    });
    final CommandTask task = new CommandTask("already-finished");
    final Duration timeout = Duration.ofSeconds(2);
    assertThrows(IOException.class, () -> task.awaitCompletion(finished, output, errors, timeout));
    final long first = firstBudget.get();
    final long second = secondBudget.get();
    assertTrue(first > 0L);
    assertTrue(second > 0L);
    assertTrue(second < first, "reading stderr must not start a fresh timeout");
  }

  private static String javaExecutable() {
    final String javaHome = System.getProperty("java.home");
    final String executable = File.separatorChar == '\\' ? "java.exe" : "java";
    final Path path = Path.of(javaHome, "bin", executable);
    return path.toString();
  }

  @Test
  @Timeout(15)
  void inheritedChildPipesCannotExtendTheTimeoutOrTheReaderExecutorClose() throws Exception {
    final Path program = this.directory.resolve("InheritedPipes.java");
    final Path childPid = this.directory.resolve("child.pid");
    final String source =
      """
      import java.nio.file.*;
      class InheritedPipes {
        public static void main(String[] args) throws Exception {
          if (args.length == 1) { Thread.sleep(30_000L); return; }
          Process child = new ProcessBuilder(args[0], args[1], "child").inheritIO().start();
          Files.writeString(Path.of(args[2]), Long.toString(child.pid()));
          Thread.sleep(500L);
        }
      }
      """;
    Files.writeString(program, source);
    final String java = javaExecutable();
    final String programPath = program.toString();
    final String pidPath = childPid.toString();
    final CommandTask task = new CommandTask(java, programPath, java, programPath, pidPath);
    final Duration timeout = Duration.ofSeconds(5);
    final long before = System.nanoTime();
    try {
      final IOException thrown = assertThrows(IOException.class, () -> task.run(timeout));
      final long elapsed = System.nanoTime() - before;
      final Throwable cause = thrown.getCause();
      final boolean pidWritten = Files.exists(childPid);
      assertInstanceOf(TimeoutException.class, cause);
      assertTrue(pidWritten, "the parent must reach the inherited-pipe fixture before timing out");
      assertTrue(elapsed < 8_000_000_000L, "reader executor teardown must not wait for the 30-second child");
      final String pidText = Files.readString(childPid);
      final long pid = Long.parseLong(pidText);
      final ProcessHandle child = ProcessHandle.of(pid).orElse(null);
      if (child != null) {
        final Duration cleanupTimeout = Duration.ofSeconds(3);
        Polling.awaitCondition("the remembered child was terminated", cleanupTimeout, () -> !child.isAlive());
      }
      final Process parent = task.getProcess();
      assertNotNull(parent);
      final boolean parentAlive = parent.isAlive();
      assertFalse(parentAlive);
    } finally {
      if (Files.exists(childPid)) {
        final String pidText = Files.readString(childPid);
        final long pid = Long.parseLong(pidText);
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
      }
      final Process parent = task.getProcess();
      if (parent != null) {
        parent.destroyForcibly();
      }
    }
  }
}
