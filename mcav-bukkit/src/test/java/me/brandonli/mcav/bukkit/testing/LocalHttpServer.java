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
package me.brandonli.mcav.bukkit.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An HTTP server on the loopback interface for tests that download files.
 *
 * <p>Every path answers with a queue of prepared responses. Once only one response is left, it is repeated for
 * every further request, so a path can fail a few times before it succeeds, or fail forever. Paths without
 * responses answer {@code 404}.
 */
public final class LocalHttpServer implements AutoCloseable {

  private final HttpServer server;
  private final Map<String, Deque<Response>> responses;
  private final Map<String, AtomicInteger> requestCounts;

  private LocalHttpServer(final HttpServer server) {
    this.server = server;
    this.responses = new ConcurrentHashMap<>();
    this.requestCounts = new ConcurrentHashMap<>();
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
      final LocalHttpServer localServer = new LocalHttpServer(httpServer);
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
    final Deque<Response> queue = this.responses.computeIfAbsent(path, _ -> new ArrayDeque<>());
    final Response response = new Response(status, body);
    synchronized (queue) {
      queue.addLast(response);
    }
  }

  /**
   * Gets the URI of a path on this server.
   *
   * @param path the path, starting with a slash
   * @return the URI
   */
  public URI uri(final String path) {
    final InetSocketAddress address = this.server.getAddress();
    final int port = address.getPort();
    return URI.create("http://127.0.0.1:" + port + path);
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

  private void handle(final HttpExchange exchange) throws IOException {
    final URI requestUri = exchange.getRequestURI();
    final String path = requestUri.getPath();
    final AtomicInteger requestCount = this.requestCounts.computeIfAbsent(path, _ -> new AtomicInteger());
    requestCount.incrementAndGet();

    final Response response = this.nextResponse(path);
    final byte[] body = response.body;
    final String method = exchange.getRequestMethod();
    final boolean head = method.equals("HEAD");
    final long responseLength = head || body.length == 0 ? -1 : body.length;
    exchange.sendResponseHeaders(response.status, responseLength);
    if (!head && body.length > 0) {
      try (final OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    }
    exchange.close();
  }

  private Response nextResponse(final String path) {
    final Deque<Response> queue = this.responses.get(path);
    if (queue == null) {
      return new Response(404, new byte[0]);
    }
    synchronized (queue) {
      if (queue.size() > 1) {
        return queue.removeFirst();
      }
      final Response last = queue.peekFirst();
      return last == null ? new Response(404, new byte[0]) : last;
    }
  }

  /**
   * Stops the server immediately.
   */
  @Override
  public void close() {
    this.server.stop(0);
  }

  /**
   * A prepared response.
   */
  private static final class Response {

    private final int status;
    private final byte[] body;

    Response(final int status, final byte[] body) {
      this.status = status;
      this.body = body.clone();
    }
  }
}
