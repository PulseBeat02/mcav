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

final class VNCHandshakeDeadlineTest {

  @Test
  void concurrentAsyncStartsStillHaveIndependentHandshakeDeadlines(@TempDir final Path folder) throws Exception {
    final Path javaFolder = Path.of(System.getProperty("java.home"), "bin");
    final Path windowsJava = javaFolder.resolve("java.exe");
    final Path executable = Files.isRegularFile(windowsJava) ? windowsJava : javaFolder.resolve("java");
    final Path output = folder.resolve("deadline.log");
    final Process child = new ProcessBuilder(
      executable.toString(),
      "-Djava.util.concurrent.ForkJoinPool.common.parallelism=2",
      "-cp",
      System.getProperty("java.class.path"),
      DeadlineProbe.class.getName()
    )
      .redirectErrorStream(true)
      .redirectOutput(output.toFile())
      .start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "the isolated deadline probe terminates");
      assertEquals(0, child.exitValue(), () -> readOutput(output));
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the child is reaped");
    }
  }

  private static String readOutput(final Path path) {
    try {
      return Files.readString(path);
    } catch (final Exception exception) {
      throw new AssertionError(exception);
    }
  }

  /** Keeps the common pool size independent of the surrounding test worker. */
  public static final class DeadlineProbe {

    private DeadlineProbe() {}

    /**
     * Runs two stalled handshakes until their own deadlines close them.
     * @param arguments unused
     * @throws Exception if a fixture operation fails
     */
    public static void main(final String[] arguments) throws Exception {
      final ForkJoinPool pool = ForkJoinPool.commonPool();
      assertEquals(2, pool.getParallelism());
      final CountDownLatch entered = new CountDownLatch(2);
      final List<CountDownLatch> closed = new ArrayList<>();
      final List<VNCPlayerImpl> players = new ArrayList<>();
      final List<CompletableFuture<Boolean>> starts = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        final CountDownLatch stopped = new CountDownLatch(1);
        final Socket socket = mock(Socket.class);
        final VernacularClient client = mock(VernacularClient.class);
        when(socket.getInputStream()).thenReturn(InputStream.nullInputStream());
        when(socket.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        doAnswer(_ -> {
          stopped.countDown();
          return null;
        })
          .when(socket)
          .close();
        doAnswer(_ -> {
          entered.countDown();
          assertTrue(entered.await(10, TimeUnit.SECONDS));
          assertTrue(stopped.await(10, TimeUnit.SECONDS), "the handshake is closed");
          throw new IllegalStateException("socket closed");
        })
          .when(client)
          .start(any(Socket.class));
        closed.add(stopped);
        players.add(new VNCPlayerImpl(_ -> client, () -> socket, 1L, 500));
      }
      try {
        for (final VNCPlayerImpl player : players) {
          starts.add(player.startAsync(VNCSource.builder().host("127.0.0.1").build()));
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS), "both common-pool workers are in their handshakes");
        final long limit = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!starts.stream().allMatch(CompletableFuture::isDone) && pool.getQueuedSubmissionCount() < 2 && System.nanoTime() < limit) {
          LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertTrue(
          starts.stream().allMatch(CompletableFuture::isCompletedExceptionally),
          "deadline actions must not queue behind the two handshakes: queued=" + pool.getQueuedSubmissionCount()
        );
        for (final CountDownLatch stopped : closed) {
          assertEquals(0L, stopped.getCount());
        }
      } finally {
        closed.forEach(CountDownLatch::countDown);
        for (final CompletableFuture<Boolean> start : starts) {
          start.handle((result, failure) -> null).get(10, TimeUnit.SECONDS);
        }
        players.forEach(VNCPlayerImpl::release);
      }
    }
  }
}
