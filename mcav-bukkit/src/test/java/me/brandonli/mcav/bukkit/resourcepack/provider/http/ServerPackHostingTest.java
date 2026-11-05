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
import me.brandonli.mcav.bukkit.BukkitException;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ServerPackHostingTest {

  private static final byte[] PACK = { 'P', 'K', 3, 4, 1 };
  private static final byte[] REBUILT_PACK = { 'P', 'K', 3, 4, 2, 2 };
  private static final int ANY_FREE_PORT = 0;

  private static final String PACK_FILE_NAME = "pack.zip";

  private static final String LOOPBACK_ADDRESS = "127.0.0.1";
  private static final int PORT = 25566;

  private static final String GET_METHOD = "GET";
  private static final int HTTP_OK = 200;
  private static final String ZIP_CONTENT_TYPE = "application/zip";

  private static final String MESSAGE = "Message!";

  private static final String SERVER_THREAD_PREFIX = "mcav-pack-http";
  private static final long THREAD_EXIT_TIMEOUT_MILLISECONDS = 10_000L;

  @TempDir
  private Path directory;

  private Path zip;

  @BeforeEach
  void writePack() throws IOException {
    this.zip = this.directory.resolve(PACK_FILE_NAME);
    Files.write(this.zip, PACK);
  }

  private HttpResponse<byte[]> request(final int port, final String method) throws IOException, InterruptedException {
    final URI uri = URI.create("http://127.0.0.1:%s/pack.zip".formatted(port));
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

  private String header(final HttpResponse<?> response, final String name) {
    final HttpHeaders headers = response.headers();
    final Optional<String> value = headers.firstValue(name);
    return value.orElse(null);
  }

  private void assertServesThePackToGetRequests(final int port) throws IOException, InterruptedException {
    final HttpResponse<byte[]> get = this.request(port, GET_METHOD);
    final int status = get.statusCode();
    final byte[] body = get.body();
    final String contentType = this.header(get, "Content-Type");
    final String disposition = this.header(get, "Content-Disposition");

    assertEquals(HTTP_OK, status);
    assertArrayEquals(PACK, body);
    assertEquals(ZIP_CONTENT_TYPE, contentType);
    assertEquals("attachment; filename=\"pack.zip\"", disposition);
  }

  private void assertAnswersHeadRequestsWithTheHeadersOnly(final int port) throws IOException, InterruptedException {
    final HttpResponse<byte[]> head = this.request(port, "HEAD");
    final int status = head.statusCode();
    final String length = this.header(head, "Content-Length");
    final byte[] body = head.body();

    assertEquals(HTTP_OK, status);
    assertEquals("5", length);

    final int bodyLength = body.length;
    assertEquals(0, bodyLength);
  }

  private void assertServesTheRebuiltPack(final int port) throws IOException, InterruptedException {
    Files.write(this.zip, REBUILT_PACK);

    final HttpResponse<byte[]> rebuilt = this.request(port, GET_METHOD);
    final byte[] body = rebuilt.body();

    assertArrayEquals(REBUILT_PACK, body, "the file is read for every download");
  }

  @Test
  void shouldServeThePackOverHttpUntilItIsShutDown() throws IOException, InterruptedException {
    final FileHttpServer server = new FileHttpServer(ANY_FREE_PORT, this.zip);
    final ServerPackHosting hosting = new ServerPackHosting(this.zip, LOOPBACK_ADDRESS, PORT, server);
    hosting.start();
    final int port = server.getLocalPort();

    try {
      hosting.start();
      final int portAfterSecondStart = server.getLocalPort();
      assertEquals(port, portAfterSecondStart, "starting again keeps the running server");
      this.assertServesThePackToGetRequests(port);
      this.assertAnswersHeadRequestsWithTheHeadersOnly(port);

      this.assertServesTheRebuiltPack(port);
    } finally {
      hosting.shutdown();
      hosting.shutdown();
    }

    assertThrows(IOException.class, () -> this.request(port, GET_METHOD));
    assertThrows(IllegalStateException.class, server::getLocalPort);
  }

  @Test
  void shouldStartAgainAfterItWasShutDown() throws IOException, InterruptedException {
    final FileHttpServer server = new FileHttpServer(ANY_FREE_PORT, this.zip);
    final ServerPackHosting hosting = new ServerPackHosting(this.zip, LOOPBACK_ADDRESS, PORT, server);

    hosting.start();
    hosting.shutdown();
    hosting.start();

    try {
      final int port = server.getLocalPort();
      final HttpResponse<byte[]> get = this.request(port, GET_METHOD);
      final byte[] body = get.body();
      assertArrayEquals(PACK, body);
    } finally {
      hosting.shutdown();
    }
  }

  private List<String> runningServerThreadNames() {
    final Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
    final Set<Thread> threads = stackTraces.keySet();
    final List<String> names = new ArrayList<>();
    for (final Thread thread : threads) {
      final String name = thread.getName();
      final boolean serverThread = name.startsWith(SERVER_THREAD_PREFIX);
      final boolean running = thread.isAlive();
      if (serverThread && running) {
        names.add(name);
      }
    }

    return names;
  }

  // netty completes the termination future just before its threads end
  private void awaitServerThreadExits() throws InterruptedException {
    final Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
    final Set<Thread> threads = stackTraces.keySet();
    for (final Thread thread : threads) {
      final String name = thread.getName();
      if (name.startsWith(SERVER_THREAD_PREFIX)) {
        thread.join(THREAD_EXIT_TIMEOUT_MILLISECONDS);
      }
    }
  }

  @Test
  void shouldReleaseItsThreadsWhenItStopsAndWhenItCannotStart() throws IOException, InterruptedException {
    final FileHttpServer server = new FileHttpServer(ANY_FREE_PORT, this.zip);
    final ServerPackHosting hosting = new ServerPackHosting(this.zip, LOOPBACK_ADDRESS, PORT, server);

    hosting.start();
    final List<String> whileRunning = this.runningServerThreadNames();
    hosting.shutdown();
    this.awaitServerThreadExits();
    final List<String> afterShutdown = this.runningServerThreadNames();
    final boolean startedThreads = whileRunning.isEmpty();
    final List<String> noThreads = List.of();

    assertFalse(startedThreads, "the server serves the pack on its own threads");
    assertEquals(noThreads, afterShutdown, "stopping the server releases its threads");

    try (final ServerSocket occupied = new ServerSocket()) {
      final InetSocketAddress anyAddress = new InetSocketAddress(0);
      occupied.setReuseAddress(false);
      occupied.bind(anyAddress);
      final int port = occupied.getLocalPort();
      final ServerPackHosting blocked = new ServerPackHosting(this.zip, LOOPBACK_ADDRESS, port);

      assertThrows(HttpServerException.class, blocked::start);

      this.awaitServerThreadExits();
      final List<String> afterFailedStart = this.runningServerThreadNames();

      assertEquals(noThreads, afterFailedStart, "a start that fails releases its threads as well");
    }
  }

  @Test
  void shouldReportThePortOnlyWhileRunning() {
    final FileHttpServer server = new FileHttpServer(ANY_FREE_PORT, this.zip);

    final IllegalStateException exception = assertThrows(IllegalStateException.class, server::getLocalPort);
    final String message = exception.getMessage();

    assertEquals("The resource pack server is not running!", message);
  }

  @Test
  void shouldReportPortsThatAreInUse() throws IOException {
    try (final ServerSocket occupied = new ServerSocket()) {
      final InetSocketAddress anyAddress = new InetSocketAddress(0);
      occupied.setReuseAddress(false);
      occupied.bind(anyAddress);
      final int port = occupied.getLocalPort();
      final ServerPackHosting hosting = new ServerPackHosting(this.zip, LOOPBACK_ADDRESS, port);

      final HttpServerException exception = assertThrows(HttpServerException.class, hosting::start);
      final String message = exception.getMessage();
      final Throwable cause = exception.getCause();

      final String expectedMessage = "Port %s could not be bound for the resource pack server!".formatted(port);
      assertEquals(expectedMessage, message);
      assertNotNull(cause);

      hosting.shutdown();
    }
  }

  @Test
  void shouldDescribeWherePlayersDownloadThePack() {
    final HttpHosting hosting = PackHosting.http(this.zip, "example.org", PORT);

    final String url = hosting.getRawUrl();
    final String hostName = hosting.getHostName();
    final int port = hosting.getPort();
    final Path hostedZip = hosting.getZip();

    assertInstanceOf(ServerPackHosting.class, hosting);
    assertEquals("http://example.org:25566", url);
    assertEquals("example.org", hostName);
    assertEquals(PORT, port);
    assertSame(this.zip, hostedZip);
    assertEquals("http://%s:%s", HttpHosting.HOST_URL);
  }

  @Test
  void shouldBracketAnIpv6AddressInTheDownloadUrl() {
    final HttpHosting hosting = PackHosting.http(this.zip, "::1", PORT);

    final String url = hosting.getRawUrl();

    assertEquals("http://[::1]:25566", url);
  }

  @Test
  void shouldRejectInvalidArguments() {
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
  void shouldKeepTheMessageInExceptions() {
    final HttpServerException exception = new HttpServerException(MESSAGE);

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();

    assertEquals(MESSAGE, message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCauseInExceptions() {
    final IOException failure = new IOException("Cause!");
    final HttpServerException exception = new HttpServerException(MESSAGE, failure);

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();

    assertEquals(MESSAGE, message);
    assertSame(failure, cause);
  }

  @Test
  void shouldMakeItsExceptionsBukkitExceptions() {
    final HttpServerException exception = new HttpServerException(MESSAGE);
    assertInstanceOf(BukkitException.class, exception);
  }
}
