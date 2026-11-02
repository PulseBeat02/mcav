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
package me.brandonli.mcav.vnc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shinyhut.vernacular.client.VernacularClient;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class VncHandshakeDeadlineTest {

  private static final long TIMEOUT_SECONDS = 10;

  private static final String JAVA_AGENT_OPTION = "-javaagent:";
  private static final String BYTE_BUDDY_AGENT = "byte-buddy-agent";

  private static final String BINARY_FOLDER = "bin";
  private static final String JAVA_EXECUTABLE = "java";
  private static final String WINDOWS_JAVA_EXECUTABLE = "java.exe";

  private static final String OUTPUT_FILE = "deadline.log";

  @Test
  void shouldGiveConcurrentAsyncStartsIndependentHandshakeDeadlines(@TempDir final Path folder) throws Exception {
    final Path javaFolder = Path.of(System.getProperty("java.home"), BINARY_FOLDER);
    final Path windowsJava = javaFolder.resolve(WINDOWS_JAVA_EXECUTABLE);
    final Path executable = Files.isRegularFile(windowsJava) ? windowsJava : javaFolder.resolve(JAVA_EXECUTABLE);
    final Path output = folder.resolve(OUTPUT_FILE);
    final List<String> command = new ArrayList<>();
    command.add(executable.toString());
    command.addAll(this.findByteBuddyAgents());
    command.add("-Djava.util.concurrent.ForkJoinPool.common.parallelism=2");
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(DeadlineProbe.class.getName());
    final Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      final boolean terminated = child.waitFor(30, TimeUnit.SECONDS);
      assertTrue(terminated, "the isolated deadline probe terminates");

      final int exitValue = child.exitValue();
      assertEquals(0, exitValue, () -> this.readOutput(output));
    } finally {
      child.destroyForcibly();
      final boolean reaped = child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(reaped, "the child is reaped");
    }
  }

  // Gives the probe's inline mocks the agent this JVM started with, since JEP 451 restricts attaching one.
  private List<String> findByteBuddyAgents() {
    final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    final List<String> arguments = runtime.getInputArguments();
    return arguments
      .stream()
      .filter(argument -> argument.startsWith(JAVA_AGENT_OPTION))
      .filter(argument -> argument.contains(BYTE_BUDDY_AGENT))
      .toList();
  }

  private String readOutput(final Path path) {
    try {
      return Files.readString(path);
    } catch (final Exception exception) {
      throw new AssertionError(exception);
    }
  }

  // Keeps the common pool size independent of the surrounding test worker.
  public static final class DeadlineProbe {

    private DeadlineProbe() {}

    public static void main(final String[] arguments) throws Exception {
      final DeadlineProbe probe = new DeadlineProbe();
      final ForkJoinPool pool = ForkJoinPool.commonPool();
      final int parallelism = pool.getParallelism();
      assertEquals(2, parallelism);

      final CountDownLatch entered = new CountDownLatch(2);
      final List<CountDownLatch> closed = new ArrayList<>();
      final List<VernacularVncPlayer> players = new ArrayList<>();
      final List<CompletableFuture<Boolean>> starts = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        final CountDownLatch stopped = new CountDownLatch(1);
        final Socket socket = mock(Socket.class);
        final VernacularClient client = mock(VernacularClient.class);
        when(socket.getInputStream()).thenReturn(InputStream.nullInputStream());
        when(socket.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        doAnswer(_ -> probe.recordSocketClosure(stopped))
          .when(socket)
          .close();
        doAnswer(_ -> probe.failClosedHandshake(entered, stopped))
          .when(client)
          .start(any(Socket.class));
        closed.add(stopped);
        players.add(new VernacularVncPlayer(_ -> client, () -> socket, 1L, 500));
      }
      try {
        for (final VernacularVncPlayer player : players) {
          starts.add(player.startAsync(VncSource.builder().host("127.0.0.1").build()));
        }
        final boolean bothEntered = entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(bothEntered, "both common-pool workers are in their handshakes");

        final long limit = System.nanoTime() + Duration.ofSeconds(TIMEOUT_SECONDS).toNanos();
        while (!starts.stream().allMatch(CompletableFuture::isDone) && pool.getQueuedSubmissionCount() < 2 && System.nanoTime() < limit) {
          LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        final boolean allFailed = starts.stream().allMatch(CompletableFuture::isCompletedExceptionally);
        assertTrue(allFailed, "deadline actions must not queue behind the two handshakes: queued=" + pool.getQueuedSubmissionCount());

        for (final CountDownLatch stopped : closed) {
          final long remaining = stopped.getCount();
          assertEquals(0L, remaining);
        }
      } finally {
        closed.forEach(CountDownLatch::countDown);
        for (final CompletableFuture<Boolean> start : starts) {
          start.handle((result, failure) -> null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        players.forEach(VernacularVncPlayer::release);
      }
    }

    private Void recordSocketClosure(final CountDownLatch stopped) {
      stopped.countDown();

      return null;
    }

    private Void failClosedHandshake(final CountDownLatch entered, final CountDownLatch stopped) throws InterruptedException {
      entered.countDown();
      final boolean bothEntered = entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      final boolean closedByDeadline = stopped.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(bothEntered);
      assertTrue(closedByDeadline, "the handshake is closed");

      final String message = "socket closed!";
      throw new IllegalStateException(message);
    }
  }
}
