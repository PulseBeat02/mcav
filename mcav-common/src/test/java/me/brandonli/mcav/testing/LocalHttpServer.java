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
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An HTTP server on the loopback interface for tests that download files.
 *
 * <p>Every path answers with a queue of prepared responses. Once only one response is left, it is repeated for
 * every further request, so a path can fail a few times before it succeeds, or fail forever. Paths without
 * responses answer {@code 404}. Requests are handled on their own threads, so a response that waits or stalls never
 * holds up other requests.
 */
public final class LocalHttpServer implements AutoCloseable {

  private static final long MAX_WAIT_SECONDS = 60;

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

  /**
   * Starts a server on a free port of the loopback interface.
   *
   * @return the running server
   */
  public static LocalHttpServer start() {
    try {
      final InetAddress loopback = InetAddress.getLoopbackAddress();
      final InetSocketAddress address = new InetSocketAddress(loopback, 0);
      final HttpServer httpServer = HttpServer.create(address, 0);
      final ExecutorService executor = Executors.newCachedThreadPool();
      httpServer.setExecutor(executor);
      final LocalHttpServer localServer = new LocalHttpServer(httpServer, executor);
      httpServer.createContext("/", localServer::handle);
      httpServer.start();
      return localServer;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Adds a response to the queue of a path.
   *
   * @param path   the path, starting with a slash
   * @param status the status code
   * @param body   the body
   */
  public void respond(final String path, final int status, final byte[] body) {
    final Response response = new Response(status, body, null, -1);
    this.addResponse(path, response);
  }

  /**
   * Adds a response that is only sent once a gate opens. The request waits for the gate, or for the server to close.
   *
   * @param path   the path, starting with a slash
   * @param status the status code
   * @param body   the body
   * @param gate   the gate to wait for
   */
  public void respondWhenOpened(final String path, final int status, final byte[] body, final CountDownLatch gate) {
    final Response response = new Response(status, body, gate, -1);
    this.addResponse(path, response);
  }

  /**
   * Adds a response that stalls: it announces more bytes than the body has, sends the body, and then sends nothing
   * more, without closing the connection, until the server is closed.
   *
   * @param path           the path, starting with a slash
   * @param status         the status code
   * @param body           the part of the body that is sent
   * @param announcedLength the length announced in the headers, which must be larger than the body
   */
  public void respondStalling(final String path, final int status, final byte[] body, final long announcedLength) {
    final Response response = new Response(status, body, null, announcedLength);
    this.addResponse(path, response);
  }

  private void addResponse(final String path, final Response response) {
    final Deque<Response> queue = this.responses.computeIfAbsent(path, _ -> new ArrayDeque<>());
    synchronized (queue) {
      queue.addLast(response);
    }
  }

  /**
   * Gets the URI of a path on this server, built from the address the server is bound to.
   *
   * @param path the path, starting with a slash
   * @return the URI
   */
  public URI uri(final String path) {
    final InetSocketAddress address = this.server.getAddress();
    final InetAddress host = address.getAddress();
    final String hostAddress = host.getHostAddress();
    final int port = address.getPort();
    try {
      return new URI("http", null, hostAddress, port, path, null, null);
    } catch (final URISyntaxException exception) {
      throw new IllegalArgumentException("Not a valid path: " + path, exception);
    }
  }

  /**
   * Gets how many requests a path received.
   *
   * @param path the path, starting with a slash
   * @return the number of requests
   */
  public int getRequestCount(final String path) {
    final AtomicInteger count = this.requestCounts.get(path);
    return count == null ? 0 : count.get();
  }

  /**
   * Waits until a path has received at least the specified number of requests. Every counted request wakes the
   * waiting threads, so the wait ends as soon as the last request arrives, without polling.
   *
   * @param path    the path, starting with a slash
   * @param count   the number of requests to wait for
   * @param timeout how long to wait at most
   * @return true if the path received the requests in time, false if the timeout passed first
   * @throws InterruptedException if the waiting thread is interrupted
   */
  public boolean awaitRequests(final String path, final int count, final Duration timeout) throws InterruptedException {
    final long start = System.nanoTime();
    final long timeoutNanos = timeout.toNanos();
    final long deadline = start + timeoutNanos;
    synchronized (this.requestSignal) {
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
    if (response.announcedLength >= 0) {
      this.stall(exchange, response);
      return;
    }
    final byte[] body = response.body;
    final String method = exchange.getRequestMethod();
    final boolean head = method.equals("HEAD");
    exchange.sendResponseHeaders(response.status, head || body.length == 0 ? -1 : body.length);
    if (!head && body.length > 0) {
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
    // fewer bytes than announced were written, which is the point of a stalling response; close() drops the connection
    exchange.close();
    if (!closed) {
      throw new IOException("The stalling response gave up because the server was not closed within " + MAX_WAIT_SECONDS + " s");
    }
  }

  /**
   * Waits until the gate opens, the server closes, or the maximum wait passes. The gate is checked in short steps, so
   * a server that closes releases the waiting request right away.
   */
  private void awaitGateOrClose(final CountDownLatch gate) {
    final long start = System.nanoTime();
    final long maximumWait = TimeUnit.SECONDS.toNanos(MAX_WAIT_SECONDS);
    final long deadline = start + maximumWait;
    boolean opened = false;
    while (!opened && this.closing.getCount() > 0) {
      final long now = System.nanoTime();
      if (now >= deadline) {
        return;
      }
      try {
        opened = gate.await(10, TimeUnit.MILLISECONDS);
      } catch (final InterruptedException exception) {
        final Thread currentThread = Thread.currentThread();
        currentThread.interrupt();
        return;
      }
    }
  }

  /**
   * Waits until the server closes. Closing interrupts the handler threads, so an interrupt also means the server
   * closed.
   *
   * @return true once the server closed, false if it was not closed within the maximum wait
   */
  private boolean awaitClose() {
    try {
      return this.closing.await(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      return true;
    }
  }

  private Response nextResponse(final String path) {
    final Deque<Response> queue = this.responses.get(path);
    if (queue == null) {
      return new Response(404, new byte[0], null, -1);
    }
    synchronized (queue) {
      if (queue.size() > 1) {
        return queue.removeFirst();
      }
      final Response last = queue.peekFirst();
      return last == null ? new Response(404, new byte[0], null, -1) : last;
    }
  }

  /**
   * Releases every waiting and stalling response and stops the server.
   */
  @Override
  public void close() {
    this.closing.countDown();
    this.server.stop(0);
    this.executor.shutdownNow();
  }

  /**
   * A prepared response.
   */
  private static final class Response {

    private final int status;
    private final byte[] body;
    private final CountDownLatch gate;
    private final long announcedLength;

    Response(final int status, final byte[] body, final CountDownLatch gate, final long announcedLength) {
      this.status = status;
      this.body = body.clone();
      this.gate = gate;
      this.announcedLength = announcedLength;
    }
  }
}
