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
package me.brandonli.mcav.utils.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import me.brandonli.mcav.testing.LocalHttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

// A client the downloader forgets to close leaves its selector thread running; isolated, as another test's client would look like a leak.
@Isolated
final class HttpClientShutdownTest {

  private static final Pattern SELECTOR_THREAD_NAME = Pattern.compile("HttpClient-\\d+-SelectorManager");
  private static final Duration SHUTDOWN_LIMIT = Duration.ofSeconds(5);

  private static final byte[] CONTENT = "client shutdown".getBytes(StandardCharsets.UTF_8);
  private static final String FILE_ROUTE = "/file";
  private static final String MISSING_ROUTE = "/missing";
  private static final String MOVED_ROUTE = "/moved";
  private static final String LOOPBACK_ADDRESS = "http://127.0.0.1:";

  private static final int HTTP_OK = 200;
  private static final int HTTP_FOUND = 302;

  @Test
  void shouldShutDownTheClientOfAnErrorStatus() throws InterruptedException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final URI missing = server.uri(MISSING_ROUTE);
      final Set<Thread> before = this.findSelectorThreads();
      assertThrows(HttpStatusException.class, () -> HttpDownloader.openStream(missing));

      this.assertNewSelectorThreadsEnd(before);
    }
  }

  @Test
  void shouldShutDownTheClientOfAFailedConnection() throws IOException, InterruptedException {
    final URI unreachable = this.findUnusedPort();
    final Set<Thread> before = this.findSelectorThreads();
    assertThrows(DownloadException.class, () -> HttpDownloader.openStream(unreachable));

    this.assertNewSelectorThreadsEnd(before);
  }

  @Test
  void shouldShutDownTheClientOfAnInterruptedRequest() throws InterruptedException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      final URI file = server.uri(FILE_ROUTE);
      final Set<Thread> before = this.findSelectorThreads();
      final Thread current = Thread.currentThread();
      current.interrupt();
      assertThrows(DownloadException.class, () -> HttpDownloader.openStream(file));

      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted);

      this.assertNewSelectorThreadsEnd(before);
    }
  }

  @Test
  void shouldShutDownTheClientOfAFollowedRedirect() throws IOException, InterruptedException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      server.respondRedirect(MOVED_ROUTE, HTTP_FOUND, FILE_ROUTE);
      final URI moved = server.uri(MOVED_ROUTE);
      final Set<Thread> before = this.findSelectorThreads();
      try (final InputStream body = HttpDownloader.openPinnedStream(moved)) {
        final byte[] content = body.readAllBytes();
        assertArrayEquals(CONTENT, content);
      }

      this.assertNewSelectorThreadsEnd(before);
    }
  }

  private Set<Thread> findSelectorThreads() {
    final Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
    final Set<Thread> threads = stacks.keySet();
    return threads.stream().filter(this::isSelectorThread).collect(Collectors.toSet());
  }

  private boolean isSelectorThread(final Thread thread) {
    final String name = thread.getName();
    return SELECTOR_THREAD_NAME.matcher(name).matches();
  }

  private void assertNewSelectorThreadsEnd(final Set<Thread> before) throws InterruptedException {
    final Set<Thread> after = this.findSelectorThreads();
    for (final Thread thread : after) {
      if (before.contains(thread)) {
        continue;
      }

      thread.join(SHUTDOWN_LIMIT);
      final boolean alive = thread.isAlive();
      assertFalse(alive, "the downloader closes every client it opens");
    }
  }

  private URI findUnusedPort() throws IOException {
    final int port;
    try (final ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    final String address = LOOPBACK_ADDRESS + port + FILE_ROUTE;
    return URI.create(address);
  }
}
