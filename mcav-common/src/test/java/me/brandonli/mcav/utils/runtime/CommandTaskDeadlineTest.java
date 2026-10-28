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
import java.util.Optional;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.invocation.InvocationOnMock;

final class CommandTaskDeadlineTest {

  private static final String FINISHED_COMMAND = "already-finished";
  private static final Duration OUTPUT_TIMEOUT = Duration.ofSeconds(2);

  private static final String CLASS_PATH_OPTION = "-cp";

  private static final String BIN_DIRECTORY_NAME = "bin";
  private static final String JAVA_NAME = "java";
  private static final String WINDOWS_JAVA_NAME = "java.exe";
  private static final String CHILD_PID_NAME = "child.pid";

  @TempDir
  private Path directory;

  private static Process finishedProcess() throws InterruptedException {
    final Process process = mock(Process.class);
    when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
    when(process.descendants()).thenAnswer(_ -> Stream.empty());
    return process;
  }

  @ParameterizedTest
  @EnumSource(WaitingStream.class)
  void shouldNotLetAnOutputStreamWaitForeverAfterItsParentExited(final WaitingStream waitingStream) throws Exception {
    final boolean standardError = waitingStream == WaitingStream.ERROR;
    final Process finished = finishedProcess();
    final CompletableFuture<String> pending = new CompletableFuture<>();
    final CompletableFuture<String> complete = CompletableFuture.completedFuture("");
    final Future<String> output = standardError ? complete : pending;
    final Future<String> errors = standardError ? pending : complete;
    final CommandTask task = new CommandTask(FINISHED_COMMAND);
    final Duration timeout = Duration.ofMillis(80);
    final long before = System.nanoTime();
    final ProcessException thrown = assertThrows(ProcessException.class, () -> task.awaitCompletion(finished, output, errors, timeout));
    final long elapsed = System.nanoTime() - before;
    final Throwable cause = thrown.getCause();
    final boolean cancelled = pending.isCancelled();
    assertInstanceOf(TimeoutException.class, cause);
    assertTrue(cancelled);

    final boolean withinDeadline = elapsed < 1_000_000_000L;
    assertTrue(withinDeadline, "the output wait uses the command deadline");
  }

  @Test
  void shouldPreserveTheSeparateOutputOfCompletedStreamsWithinTheDeadline() throws Exception {
    final Process finished = finishedProcess();
    final Future<String> output = CompletableFuture.completedFuture("standard output");
    final Future<String> errors = CompletableFuture.completedFuture("standard error");
    final CommandTask task = new CommandTask(FINISHED_COMMAND);
    final Duration timeout = OUTPUT_TIMEOUT;
    task.awaitCompletion(finished, output, errors, timeout);
    final String capturedOutput = task.getOutput();
    final String capturedErrors = task.getErrorOutput();
    assertEquals("standard output", capturedOutput);
    assertEquals("standard error", capturedErrors);
  }

  @Test
  void shouldShareTheSameRemainingBudgetBetweenBothOutputStreams() throws Exception {
    final Process finished = finishedProcess();
    final Future<String> output = mock(StringFuture.class);
    final Future<String> errors = mock(StringFuture.class);
    final AtomicLong firstBudget = new AtomicLong();
    final AtomicLong secondBudget = new AtomicLong();
    when(output.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> this.recordBudgetAndAnswerLate(invocation, firstBudget));
    when(errors.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> this.recordBudgetAndTimeOut(invocation, secondBudget));
    final CommandTask task = new CommandTask(FINISHED_COMMAND);
    final Duration timeout = OUTPUT_TIMEOUT;
    assertThrows(ProcessException.class, () -> task.awaitCompletion(finished, output, errors, timeout));

    final long first = firstBudget.get();
    final long second = secondBudget.get();
    final boolean firstPositive = first > 0L;
    assertTrue(firstPositive);

    final boolean secondPositive = second > 0L;
    assertTrue(secondPositive);

    final boolean budgetShrank = second < first;
    assertTrue(budgetShrank, "reading stderr must not start a fresh timeout");
  }

  private String recordBudgetAndAnswerLate(final InvocationOnMock invocation, final AtomicLong budget) throws InterruptedException {
    final long nanoseconds = invocation.getArgument(0);
    budget.set(nanoseconds);
    Thread.sleep(30L);

    return "output";
  }

  private String recordBudgetAndTimeOut(final InvocationOnMock invocation, final AtomicLong budget) throws TimeoutException {
    final long nanoseconds = invocation.getArgument(0);
    budget.set(nanoseconds);

    throw new TimeoutException("still reading error output!");
  }

  private static String javaExecutable() {
    final String javaHome = System.getProperty("java.home");
    final String executable = File.separatorChar == '\\' ? WINDOWS_JAVA_NAME : JAVA_NAME;
    final Path path = Path.of(javaHome, BIN_DIRECTORY_NAME, executable);
    return path.toString();
  }

  @Test
  @Timeout(15)
  void shouldNotLetInheritedChildPipesExtendTheTimeoutOrTheReaderExecutorClose() throws Exception {
    final Path childPid = this.directory.resolve(CHILD_PID_NAME);
    final String java = javaExecutable();
    // the compiled class: launching a source file compiles it first, which took most of the budget on a busy machine
    final String classes = Path.of(InheritedPipes.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    final String fixture = InheritedPipes.class.getName();
    final String pidPath = childPid.toString();
    final CommandTask task = new CommandTask(java, CLASS_PATH_OPTION, classes, fixture, java, classes, pidPath);
    final Duration timeout = Duration.ofSeconds(5);
    final long before = System.nanoTime();
    try {
      final ProcessException thrown = assertThrows(ProcessException.class, () -> task.run(timeout));
      final long elapsed = System.nanoTime() - before;
      final Throwable cause = thrown.getCause();
      final boolean pidWritten = Files.exists(childPid);
      assertInstanceOf(TimeoutException.class, cause);
      assertTrue(pidWritten, "the parent must reach the inherited-pipe fixture before timing out");

      final boolean beforeChildEnded = elapsed < 8_000_000_000L;
      assertTrue(beforeChildEnded, "reader executor teardown must not wait for the 30-second child");

      final String pidText = Files.readString(childPid);
      final long pid = Long.parseLong(pidText);
      final ProcessHandle child = ProcessHandle.of(pid).orElse(null);
      if (child != null) {
        final Duration cleanupTimeout = Duration.ofSeconds(3);
        Polling.awaitCondition("the remembered child was terminated", cleanupTimeout, () -> !child.isAlive());
      }

      final Optional<Process> launched = task.getProcess();
      assertTrue(launched.isPresent());

      final Process parent = launched.orElseThrow();
      final boolean parentAlive = parent.isAlive();
      assertFalse(parentAlive);
    } finally {
      if (Files.exists(childPid)) {
        final String pidText = Files.readString(childPid);
        final long pid = Long.parseLong(pidText);
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
      }

      final Optional<Process> parent = task.getProcess();
      parent.ifPresent(Process::destroyForcibly);
    }
  }

  @Test
  void shouldExpireTheDeadlineAtItsLastNanosecond() {
    final long now = OUTPUT_TIMEOUT.toNanos();
    assertThrows(TimeoutException.class, () -> CommandTask.remainingNanoseconds(OUTPUT_TIMEOUT, 0L, now));
  }

  private static final class StringFuture extends CompletableFuture<String> {}

  // A parent that starts a child sharing its standard streams and ends, while the child holds the pipes for 30 seconds.
  // With three arguments, the Java executable, the class path and the file for the child's pid, it is the parent; with
  // one, the child.
  static final class InheritedPipes {

    private InheritedPipes() {}

    public static void main(final String[] args) throws IOException, InterruptedException {
      if (args.length == 1) {
        Thread.sleep(30_000L);
        return;
      }

      final Process child = new ProcessBuilder(args[0], CLASS_PATH_OPTION, args[1], InheritedPipes.class.getName(), "child")
        .inheritIO()
        .start();
      Files.writeString(Path.of(args[2]), Long.toString(child.pid()));
      Thread.sleep(500L);
    }
  }

  private enum WaitingStream {
    OUTPUT,
    ERROR,
  }
}
