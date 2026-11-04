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
package me.brandonli.mcav.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.Nullable;

// An HTTP server on the loopback interface for tests that download files. Every path answers with a queue of prepared
// responses. Once only one response is left, it is repeated for every further request, so a path can fail a few times
// before it succeeds, or fail forever. Paths without responses answer 404. Requests are handled on their own threads,
// so a response that waits or stalls never holds up other requests.
public final class LocalHttpServer implements AutoCloseable {

  private static final long MAXIMUM_WAIT_SECONDS = 60;
  private static final long GATE_POLL_MILLISECONDS = 10L;
  private static final int STOP_DELAY_SECONDS = 0;

  private static final int EPHEMERAL_PORT = 0;
  private static final int DEFAULT_BACKLOG = 0;

  private static final String HTTP_SCHEME = "http";
  private static final String HEAD_METHOD = "HEAD";
  private static final String ROOT_PATH = "/";

  private static final int NOT_FOUND_STATUS = 404;

  private static final long NO_ANNOUNCED_LENGTH = -1L;
  private static final long NO_TRICKLE_MILLISECONDS = 0L;

  private static final long NO_BODY_LENGTH = -1L;

  private final HttpServer server;
  private final ExecutorService executor;
  private final Map<String, Deque<Response>> responses;
  private final Map<String, AtomicInteger> requestCounts;
  private final CountDownLatch closing;
  private final Object requestSignal;

  private LocalHttpServer(final HttpServer server, final ExecutorService executor) {
    this.server = server;
    this.executor = executor;
    this.responses = new ConcurrentHashMap<>();
    this.requestCounts = new ConcurrentHashMap<>();
    this.closing = new CountDownLatch(1);
    this.requestSignal = new Object();
  }

  public static LocalHttpServer start() {
    try {
      final InetAddress loopback = InetAddress.getLoopbackAddress();
      final InetSocketAddress address = new InetSocketAddress(loopback, EPHEMERAL_PORT);
      final HttpServer httpServer = HttpServer.create(address, DEFAULT_BACKLOG);
      final ExecutorService executor = Executors.newCachedThreadPool();
      httpServer.setExecutor(executor);
      final LocalHttpServer localServer = new LocalHttpServer(httpServer, executor);
      httpServer.createContext(ROOT_PATH, localServer::handle);
      httpServer.start();
      return localServer;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  public void respond(final String path, final int status, final byte[] body) {
    final Response response = new Response(status, body, null, NO_ANNOUNCED_LENGTH);
    this.addResponse(path, response);
  }

  // Adds a response that is only sent once a gate opens. The request waits for the gate, or for the server to close.
  public void respondWhenOpened(final String path, final int status, final byte[] body, final CountDownLatch gate) {
    final Response response = new Response(status, body, gate, NO_ANNOUNCED_LENGTH);
    this.addResponse(path, response);
  }

  public void respondStalling(final String path, final int status, final byte[] body, final long announcedLength) {
    final Response response = new Response(status, body, null, announcedLength);
    this.addResponse(path, response);
  }

  public void respondRedirect(final String path, final int status, final String location) {
    final Response response = new Response(status, new byte[0], null, NO_ANNOUNCED_LENGTH, location, NO_TRICKLE_MILLISECONDS);
    this.addResponse(path, response);
  }

  public void respondStallingRedirect(
    final String path,
    final int status,
    final String location,
    final byte[] body,
    final long announcedLength
  ) {
    final Response response = new Response(status, body, null, announcedLength, location, NO_TRICKLE_MILLISECONDS);
    this.addResponse(path, response);
  }

  public void respondTrickling(final String path, final int status, final byte[] body, final long intervalMilliseconds) {
    final Response response = new Response(status, body, null, NO_ANNOUNCED_LENGTH, null, intervalMilliseconds);
    this.addResponse(path, response);
  }

  private void addResponse(final String path, final Response response) {
    final Deque<Response> queue = this.responses.computeIfAbsent(path, _ -> new ArrayDeque<>());
    synchronized (queue) {
      queue.addLast(response);
    }
  }

  public URI uri(final String path) {
    final InetSocketAddress address = this.server.getAddress();
    final InetAddress host = address.getAddress();
    final String hostAddress = host.getHostAddress();
    final int port = address.getPort();
    try {
      return new URI(HTTP_SCHEME, null, hostAddress, port, path, null, null);
    } catch (final URISyntaxException exception) {
      final String message = "Path %s is not valid!".formatted(path);
      throw new IllegalArgumentException(message, exception);
    }
  }

  public boolean isServing(final String host, final int port) {
    final InetSocketAddress address = this.server.getAddress();
    final InetAddress boundHost = address.getAddress();
    final String boundHostName = boundHost.getHostName();
    final String boundHostAddress = boundHost.getHostAddress();
    final boolean sameHost = host.equals(boundHostName) || host.equals(boundHostAddress);
    final int boundPort = address.getPort();
    return sameHost && port == boundPort;
  }

  public int getRequestCount(final String path) {
    final AtomicInteger count = this.requestCounts.get(path);
    return count == null ? 0 : count.get();
  }

  public int getTotalRequestCount() {
    final Collection<AtomicInteger> counts = this.requestCounts.values();
    return counts.stream().mapToInt(AtomicInteger::get).sum();
  }

  // Waits until a path has received at least the specified number of requests. Every counted request wakes the waiting
  // threads, so the wait ends as soon as the last request arrives, without polling.
  public boolean awaitRequests(final String path, final int count, final Duration timeout) throws InterruptedException {
    final long start = System.nanoTime();
    final long timeoutNanoseconds = timeout.toNanos();
    final long deadline = start + timeoutNanoseconds;
    synchronized (this.requestSignal) {
      return this.awaitRequestCount(path, count, deadline);
    }
  }

  private boolean awaitRequestCount(final String path, final int count, final long deadline) throws InterruptedException {
    int received = this.getRequestCount(path);
    while (received < count) {
      final long now = System.nanoTime();
      final long remaining = deadline - now;
      if (remaining <= 0) {
        return false;
      }
      TimeUnit.NANOSECONDS.timedWait(this.requestSignal, remaining);
      received = this.getRequestCount(path);
    }

    return true;
  }

  private void signalRequest() {
    synchronized (this.requestSignal) {
      this.requestSignal.notifyAll();
    }
  }

  private void handle(final HttpExchange exchange) throws IOException {
    final URI requestUri = exchange.getRequestURI();
    final String path = requestUri.getPath();
    final AtomicInteger count = this.requestCounts.computeIfAbsent(path, _ -> new AtomicInteger());
    count.incrementAndGet();
    this.signalRequest();
    final Response response = this.nextResponse(path);
    final CountDownLatch gate = response.gate;
    if (gate != null) {
      this.awaitGateOrClose(gate);
    }

    if (response.location != null) {
      exchange.getResponseHeaders().add("Location", response.location);
    }

    if (response.announcedLength >= 0) {
      this.stall(exchange, response);
      return;
    }

    if (response.trickleMilliseconds > 0) {
      this.trickle(exchange, response);
      return;
    }

    final byte[] body = response.body;
    final String method = exchange.getRequestMethod();
    final boolean isHeadRequest = method.equals(HEAD_METHOD);
    exchange.sendResponseHeaders(response.status, isHeadRequest || body.length == 0 ? NO_BODY_LENGTH : body.length);

    if (!isHeadRequest && body.length > 0) {
      try (final OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    }

    exchange.close();
  }

  private void stall(final HttpExchange exchange, final Response response) throws IOException {
    exchange.sendResponseHeaders(response.status, response.announcedLength);
    final OutputStream output = exchange.getResponseBody();
    output.write(response.body);
    output.flush();
    final boolean closed = this.awaitClose();
    exchange.close();

    if (!closed) {
      final String message = "The stalling response gave up because the server was not closed within %s s!".formatted(MAXIMUM_WAIT_SECONDS);
      throw new IOException(message);
    }
  }

  private void trickle(final HttpExchange exchange, final Response response) throws IOException {
    exchange.sendResponseHeaders(response.status, response.body.length);
    try (final OutputStream output = exchange.getResponseBody()) {
      this.trickleBody(output, response);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  private void trickleBody(final OutputStream output, final Response response) throws IOException, InterruptedException {
    for (final byte value : response.body) {
      output.write(value);
      output.flush();
      if (this.closing.await(response.trickleMilliseconds, TimeUnit.MILLISECONDS)) {
        return;
      }
    }
  }

  // Waits until the gate opens, the server closes, or the maximum wait passes. The gate is checked in short steps, so a
  // server that closes releases the waiting request right away.
  private void awaitGateOrClose(final CountDownLatch gate) {
    final long start = System.nanoTime();
    final long maximumWait = TimeUnit.SECONDS.toNanos(MAXIMUM_WAIT_SECONDS);
    final long deadline = start + maximumWait;
    boolean opened = false;
    while (!opened && this.closing.getCount() > 0) {
      final long now = System.nanoTime();
      if (now >= deadline) {
        return;
      }
      try {
        opened = gate.await(GATE_POLL_MILLISECONDS, TimeUnit.MILLISECONDS);
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();
        return;
      }
    }
  }

  // Waits until the server closes. Closing interrupts the handler threads, so an interrupt also means the server
  // closed.
  private boolean awaitClose() {
    try {
      return this.closing.await(MAXIMUM_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      return true;
    }
  }

  private Response nextResponse(final String path) {
    final Deque<Response> queue = this.responses.get(path);
    if (queue == null) {
      return new Response(NOT_FOUND_STATUS, new byte[0], null, NO_ANNOUNCED_LENGTH);
    }

    synchronized (queue) {
      if (queue.size() > 1) {
        return queue.removeFirst();
      }

      final Response last = queue.peekFirst();
      return last == null ? new Response(NOT_FOUND_STATUS, new byte[0], null, NO_ANNOUNCED_LENGTH) : last;
    }
  }

  @Override
  public void close() {
    this.closing.countDown();
    this.server.stop(STOP_DELAY_SECONDS);
    this.executor.shutdownNow();
  }

  private static final class Response {

    private final int status;
    private final byte[] body;
    private final CountDownLatch gate;
    private final long announcedLength;
    private final @Nullable String location;
    private final long trickleMilliseconds;

    Response(final int status, final byte[] body, final CountDownLatch gate, final long announcedLength) {
      this(status, body, gate, announcedLength, null, NO_TRICKLE_MILLISECONDS);
    }

    Response(
      final int status,
      final byte[] body,
      final CountDownLatch gate,
      final long announcedLength,
      final @Nullable String location,
      final long trickleMilliseconds
    ) {
      this.status = status;
      this.body = body.clone();
      this.gate = gate;
      this.announcedLength = announcedLength;
      this.location = location;
      this.trickleMilliseconds = trickleMilliseconds;
    }
  }
}
