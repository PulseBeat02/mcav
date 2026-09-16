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
package me.brandonli.mcav.bukkit.resourcepack.provider.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link ServerPackHosting}, {@link FileHttpServer}, and {@link HttpServerException} with a real server on the
 * loopback interface. The servers listen on a free port picked by the operating system, which is read back after
 * they started, so no other process can take the port in between.
 */
final class ServerPackHostingTest {

  private static final byte[] PACK = { 'P', 'K', 3, 4, 1 };
  private static final byte[] REBUILT_PACK = { 'P', 'K', 3, 4, 2, 2 };
  private static final int ANY_FREE_PORT = 0;

  @TempDir
  private Path directory;

  private Path zip;

  @BeforeEach
  void writePack() throws IOException {
    this.zip = this.directory.resolve("pack.zip");
    Files.write(this.zip, PACK);
  }

  private static HttpResponse<byte[]> request(final int port, final String method) throws IOException, InterruptedException {
    final URI uri = URI.create("http://127.0.0.1:" + port + "/pack.zip");
    final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri);
    final HttpRequest.BodyPublisher noBody = HttpRequest.BodyPublishers.noBody();
    requestBuilder.method(method, noBody);
    final HttpRequest request = requestBuilder.build();

    final HttpClient.Builder clientBuilder = HttpClient.newBuilder();
    clientBuilder.version(HttpClient.Version.HTTP_1_1);
    try (final HttpClient client = clientBuilder.build()) {
      final HttpResponse.BodyHandler<byte[]> handler = HttpResponse.BodyHandlers.ofByteArray();
      return client.send(request, handler);
    }
  }

  private static String header(final HttpResponse<?> response, final String name) {
    final HttpHeaders headers = response.headers();
    final Optional<String> value = headers.firstValue(name);
    return value.orElse(null);
  }

  private static void assertServesThePackToGetRequests(final int port) throws IOException, InterruptedException {
    final HttpResponse<byte[]> get = request(port, "GET");
    final int status = get.statusCode();
    final byte[] body = get.body();
    final String contentType = header(get, "Content-Type");
    final String disposition = header(get, "Content-Disposition");

    assertEquals(200, status);
    assertArrayEquals(PACK, body);
    assertEquals("application/zip", contentType);
    assertEquals("attachment; filename=\"pack.zip\"", disposition);
  }

  private static void assertAnswersHeadRequestsWithTheHeadersOnly(final int port) throws IOException, InterruptedException {
    final HttpResponse<byte[]> head = request(port, "HEAD");
    final int status = head.statusCode();
    final String length = header(head, "Content-Length");
    final byte[] body = head.body();

    assertEquals(200, status);
    assertEquals("5", length);
    assertEquals(0, body.length);
  }

  private void assertServesTheRebuiltPack(final int port) throws IOException, InterruptedException {
    Files.write(this.zip, REBUILT_PACK);

    final HttpResponse<byte[]> rebuilt = request(port, "GET");
    final byte[] body = rebuilt.body();

    assertArrayEquals(REBUILT_PACK, body, "the file is read for every download");
  }

  @Test
  void servesThePackOverHttpUntilItIsShutDown() throws IOException, InterruptedException {
    final FileHttpServer server = new FileHttpServer(ANY_FREE_PORT, this.zip);
    final ServerPackHosting hosting = new ServerPackHosting(this.zip, "127.0.0.1", 25566, server);
    hosting.start();
    final int port = server.getLocalPort();

    try {
      hosting.start();
      final int portAfterSecondStart = server.getLocalPort();
      assertEquals(port, portAfterSecondStart, "starting again keeps the running server");
      assertServesThePackToGetRequests(port);
      assertAnswersHeadRequestsWithTheHeadersOnly(port);
      this.assertServesTheRebuiltPack(port);
    } finally {
      hosting.shutdown();
      hosting.shutdown();
    }

    assertThrows(IOException.class, () -> request(port, "GET"));
    assertThrows(IllegalStateException.class, server::getLocalPort);
  }

  @Test
  void canBeStartedAgainAfterItWasShutDown() throws IOException, InterruptedException {
    final FileHttpServer server = new FileHttpServer(ANY_FREE_PORT, this.zip);
    final ServerPackHosting hosting = new ServerPackHosting(this.zip, "127.0.0.1", 25566, server);

    hosting.start();
    hosting.shutdown();
    hosting.start();

    try {
      final int port = server.getLocalPort();
      final HttpResponse<byte[]> get = request(port, "GET");
      final byte[] body = get.body();
      assertArrayEquals(PACK, body);
    } finally {
      hosting.shutdown();
    }
  }

  /**
   * Gets the names of the Netty threads of the pack server that are still running.
   *
   * @return the names of the running server threads
   */
  private static List<String> runningServerThreadNames() {
    final Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
    final Set<Thread> threads = stackTraces.keySet();
    final List<String> names = new ArrayList<>();
    for (final Thread thread : threads) {
      final String name = thread.getName();
      final boolean serverThread = name.startsWith("mcav-pack-http");
      final boolean running = thread.isAlive();
      if (serverThread && running) {
        names.add(name);
      }
    }
    return names;
  }

  @Test
  void releasesItsThreadsWhenItStopsAndWhenItCannotStart() throws IOException {
    final FileHttpServer server = new FileHttpServer(ANY_FREE_PORT, this.zip);
    final ServerPackHosting hosting = new ServerPackHosting(this.zip, "127.0.0.1", 25566, server);

    hosting.start();
    final List<String> whileRunning = runningServerThreadNames();
    hosting.shutdown();
    final List<String> afterShutdown = runningServerThreadNames();
    final boolean startedThreads = whileRunning.isEmpty();
    final List<String> noThreads = List.of();

    assertFalse(startedThreads, "the server serves the pack on its own threads");
    assertEquals(noThreads, afterShutdown, "stopping the server releases its threads");

    try (final ServerSocket occupied = new ServerSocket()) {
      final InetSocketAddress anyAddress = new InetSocketAddress(0);
      occupied.setReuseAddress(false);
      occupied.bind(anyAddress);
      final int port = occupied.getLocalPort();
      final ServerPackHosting blocked = new ServerPackHosting(this.zip, "127.0.0.1", port);

      assertThrows(HttpServerException.class, blocked::start);
      final List<String> afterFailedStart = runningServerThreadNames();

      assertEquals(noThreads, afterFailedStart, "a start that fails releases its threads as well");
    }
  }

  @Test
  void reportsThePortOnlyWhileRunning() {
    final FileHttpServer server = new FileHttpServer(ANY_FREE_PORT, this.zip);

    final IllegalStateException exception = assertThrows(IllegalStateException.class, server::getLocalPort);
    final String message = exception.getMessage();

    assertEquals("The resource pack server is not running", message);
  }

  @Test
  void reportsPortsThatAreInUse() throws IOException {
    try (final ServerSocket occupied = new ServerSocket()) {
      final InetSocketAddress anyAddress = new InetSocketAddress(0);
      occupied.setReuseAddress(false);
      occupied.bind(anyAddress);
      final int port = occupied.getLocalPort();
      final ServerPackHosting hosting = new ServerPackHosting(this.zip, "127.0.0.1", port);

      final HttpServerException exception = assertThrows(HttpServerException.class, hosting::start);
      final String message = exception.getMessage();
      final Throwable cause = exception.getCause();

      assertEquals("Failed to start the resource pack server on port " + port, message);
      assertNotNull(cause);
      hosting.shutdown();
    }
  }

  @Test
  void describesWherePlayersDownloadThePack() {
    final HttpHosting hosting = PackHosting.http(this.zip, "example.org", 25566);

    final String url = hosting.getRawUrl();
    final String hostName = hosting.getHostName();
    final int port = hosting.getPort();
    final Path hostedZip = hosting.getZip();

    assertInstanceOf(ServerPackHosting.class, hosting);
    assertEquals("http://example.org:25566", url);
    assertEquals("example.org", hostName);
    assertEquals(25566, port);
    assertSame(this.zip, hostedZip);
    assertEquals("http://%s:%s", HttpHosting.HOST_URL);
  }

  @Test
  void rejectsInvalidArguments() {
    final FileHttpServer server = new FileHttpServer(ANY_FREE_PORT, this.zip);
    final ServerPackHosting lowest = new ServerPackHosting(this.zip, "host", 1);
    final ServerPackHosting highest = new ServerPackHosting(this.zip, "host", 65535);

    final int lowestPort = lowest.getPort();
    final int highestPort = highest.getPort();

    assertEquals(1, lowestPort);
    assertEquals(65535, highestPort);
    assertThrows(IllegalArgumentException.class, () -> new ServerPackHosting(this.zip, "host", 0));
    assertThrows(IllegalArgumentException.class, () -> new ServerPackHosting(this.zip, "host", 65536));
    assertThrows(NullPointerException.class, () -> new ServerPackHosting(null, "host", 80));
    assertThrows(NullPointerException.class, () -> new ServerPackHosting(this.zip, null, 80));
    assertThrows(NullPointerException.class, () -> new ServerPackHosting(this.zip, "host", 80, null));
    assertThrows(IllegalArgumentException.class, () -> new ServerPackHosting(this.zip, "host", 0, server));
    assertThrows(NullPointerException.class, () -> PackHosting.http(null, "host", 80));
    assertThrows(NullPointerException.class, () -> PackHosting.http(this.zip, null, 80));
  }

  @Test
  void exceptionsKeepTheMessage() {
    final HttpServerException exception = new HttpServerException("message");

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();

    assertEquals("message", message);
    assertNull(cause);
  }

  @Test
  void exceptionsKeepTheMessageAndTheCause() {
    final IOException failure = new IOException("cause");
    final HttpServerException exception = new HttpServerException("message", failure);

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();

    assertEquals("message", message);
    assertSame(failure, cause);
  }

  @Test
  void exceptionsAreRuntimeExceptions() {
    final HttpServerException exception = new HttpServerException("message");
    assertInstanceOf(RuntimeException.class, exception, "a port in use is recoverable, so it is no Error");
  }
}
