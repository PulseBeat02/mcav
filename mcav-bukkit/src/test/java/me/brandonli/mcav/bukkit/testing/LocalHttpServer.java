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

// the last queued response of a path repeats; a path without responses answers 404
public final class LocalHttpServer implements AutoCloseable {

  private static final String ROOT_PATH = "/";
  private static final String LOOPBACK_URL = "http://127.0.0.1";

  private static final int EPHEMERAL_PORT = 0;
  private static final int DEFAULT_BACKLOG = 0;

  private static final int IMMEDIATE_STOP_SECONDS = 0;

  private static final String HEAD_METHOD = "HEAD";

  private static final int NOT_FOUND_STATUS = 404;

  private static final long NO_RESPONSE_BODY = -1L;

  private final HttpServer server;
  private final Map<String, Deque<Response>> responses;
  private final Map<String, AtomicInteger> requestCounts;

  private LocalHttpServer(final HttpServer server) {
    this.server = server;
    this.responses = new ConcurrentHashMap<>();
    this.requestCounts = new ConcurrentHashMap<>();
  }

  public static LocalHttpServer start() {
    try {
      final InetAddress loopback = InetAddress.getLoopbackAddress();
      final InetSocketAddress address = new InetSocketAddress(loopback, EPHEMERAL_PORT);
      final HttpServer httpServer = HttpServer.create(address, DEFAULT_BACKLOG);
      final LocalHttpServer localServer = new LocalHttpServer(httpServer);
      httpServer.createContext(ROOT_PATH, localServer::serveNextResponse);
      httpServer.start();
      return localServer;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  public void respond(final String path, final int status, final byte[] body) {
    final Deque<Response> queue = this.responses.computeIfAbsent(path, _ -> new ArrayDeque<>());
    final Response response = new Response(status, body);
    synchronized (queue) {
      queue.addLast(response);
    }
  }

  public URI uri(final String path) {
    final InetSocketAddress address = this.server.getAddress();
    final int port = address.getPort();
    return URI.create("%s:%s%s".formatted(LOOPBACK_URL, port, path));
  }

  public int getRequestCount(final String path) {
    final AtomicInteger count = this.requestCounts.get(path);
    return count == null ? 0 : count.get();
  }

  private void serveNextResponse(final HttpExchange exchange) throws IOException {
    final URI requestUri = exchange.getRequestURI();
    final String path = requestUri.getPath();
    final AtomicInteger requestCount = this.requestCounts.computeIfAbsent(path, _ -> new AtomicInteger());
    requestCount.incrementAndGet();

    final Response response = this.nextResponse(path);
    final byte[] body = response.body;
    final String method = exchange.getRequestMethod();
    final boolean isHeadRequest = method.equals(HEAD_METHOD);
    final long responseLength = isHeadRequest || body.length == 0 ? NO_RESPONSE_BODY : body.length;
    exchange.sendResponseHeaders(response.status, responseLength);

    if (!isHeadRequest && body.length > 0) {
      try (final OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    }

    exchange.close();
  }

  private Response nextResponse(final String path) {
    final Deque<Response> queue = this.responses.get(path);
    if (queue == null) {
      return new Response(NOT_FOUND_STATUS, new byte[0]);
    }

    synchronized (queue) {
      if (queue.size() > 1) {
        return queue.removeFirst();
      }

      final Response last = queue.peekFirst();
      return last == null ? new Response(NOT_FOUND_STATUS, new byte[0]) : last;
    }
  }

  @Override
  public void close() {
    this.server.stop(IMMEDIATE_STOP_SECONDS);
  }

  private static final class Response {

    private final int status;
    private final byte[] body;

    Response(final int status, final byte[] body) {
      this.status = status;
      this.body = body.clone();
    }
  }
}
