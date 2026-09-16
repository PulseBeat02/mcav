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
package me.brandonli.mcav.sandbox.e2e;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves a Maven repository folder over plain HTTP on the loopback address. The plugin downloads its libraries with
 * Gremlin, whose {@link java.net.http.HttpClient} cannot read {@code file:} URIs, so the modules this build publishes
 * into a folder reach the server through this repository. Only {@code GET} and {@code HEAD} of files inside the folder
 * are answered; everything else gets a 404 or a 405. Closing it stops the server.
 */
final class LocalMavenRepositoryServer implements AutoCloseable {

  // the build lists the repository as http://127.0.0.1:<port>/, so the server binds exactly that IPv4 address, parsed
  // from the literal rather than looked up
  private static final InetAddress LOOPBACK_ADDRESS = InetAddress.ofLiteral("127.0.0.1");
  private static final int HTTP_OK = 200;
  private static final int HTTP_NOT_FOUND = 404;
  private static final int HTTP_METHOD_NOT_ALLOWED = 405;
  private static final long NO_BODY = -1;

  private final Path root;
  private final HttpServer server;
  private final ExecutorService executor;
  private final AtomicInteger servedFileCount;

  private LocalMavenRepositoryServer(final Path root, final HttpServer server) {
    this.root = root;
    this.server = server;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    this.servedFileCount = new AtomicInteger();
    server.createContext("/", this::handle);
    server.setExecutor(this.executor);
  }

  /**
   * Starts serving a repository folder on {@code http://127.0.0.1:<port>/}.
   *
   * @param directory the repository folder
   * @param port      the port to listen on
   * @return the running repository server
   * @throws IOException if the port cannot be bound
   */
  static LocalMavenRepositoryServer start(final Path directory, final int port) throws IOException {
    final Path absoluteDirectory = directory.toAbsolutePath();
    final Path root = absoluteDirectory.normalize();
    final InetSocketAddress address = new InetSocketAddress(LOOPBACK_ADDRESS, port);
    final HttpServer server = HttpServer.create(address, 0);
    final LocalMavenRepositoryServer repository = new LocalMavenRepositoryServer(root, server);
    server.start();
    return repository;
  }

  /**
   * Gets how many files the repository has served so far.
   *
   * @return the number of successful {@code GET} requests
   */
  int getServedFileCount() {
    return this.servedFileCount.get();
  }

  private void handle(final HttpExchange exchange) throws IOException {
    try {
      final String method = exchange.getRequestMethod();
      final boolean head = method.equals("HEAD");
      final boolean get = method.equals("GET");
      if (!head && !get) {
        exchange.sendResponseHeaders(HTTP_METHOD_NOT_ALLOWED, NO_BODY);
        return;
      }
      final URI requestUri = exchange.getRequestURI();
      final Optional<Path> file = this.findFile(requestUri);
      if (file.isEmpty()) {
        exchange.sendResponseHeaders(HTTP_NOT_FOUND, NO_BODY);
        return;
      }
      final Path existingFile = file.get();
      this.sendFile(exchange, existingFile, head);
    } finally {
      exchange.close();
    }
  }

  /**
   * Finds the file a request asks for; paths that would leave the repository folder are treated as missing.
   */
  private Optional<Path> findFile(final URI requestUri) {
    final String requestPath = requestUri.getPath();
    if (requestPath == null) {
      return Optional.empty();
    }
    final String relativePath = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
    final Path candidate;
    try {
      candidate = this.root.resolve(relativePath);
    } catch (final InvalidPathException exception) {
      return Optional.empty();
    }
    final Path file = candidate.normalize();
    final boolean inside = file.startsWith(this.root);
    if (!inside || !Files.isRegularFile(file)) {
      return Optional.empty();
    }
    return Optional.of(file);
  }

  private void sendFile(final HttpExchange exchange, final Path file, final boolean head) throws IOException {
    final Headers responseHeaders = exchange.getResponseHeaders();
    responseHeaders.set("Content-Type", "application/octet-stream");
    if (head) {
      exchange.sendResponseHeaders(HTTP_OK, NO_BODY);
      return;
    }
    final long size = Files.size(file);
    // a length of 0 would make the server send a chunked body, so an empty file is sent without one
    final long responseLength = size == 0 ? NO_BODY : size;
    exchange.sendResponseHeaders(HTTP_OK, responseLength);
    try (final OutputStream body = exchange.getResponseBody()) {
      Files.copy(file, body);
    }
    this.servedFileCount.incrementAndGet();
  }

  /**
   * Stops the server at once and ends its request threads.
   */
  @Override
  public void close() {
    this.server.stop(0);
    this.executor.close();
  }
}
