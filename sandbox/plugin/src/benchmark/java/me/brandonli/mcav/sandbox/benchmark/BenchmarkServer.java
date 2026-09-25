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
package me.brandonli.mcav.sandbox.benchmark;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Serves the benchmark pages on the loopback address: {@code /fps}, whose color changes on every animation frame,
 * and {@code /latency}, whose color changes whenever {@link #send(String)} pushes a server-sent event.
 */
final class BenchmarkServer implements AutoCloseable {

  private static final String FPS_PAGE = """
    <!doctype html><html><body style="margin:0;overflow:hidden">
    <div id="d" style="position:fixed;left:0;top:0;right:0;bottom:0;background:#dc0000"></div>
    <script>
    const colors = ["#dc0000", "#7fb238", "#c7c7c7", "#f7e9a3", "#6d9930", "#b40000"];
    let frame = 0;
    function step() { document.getElementById("d").style.background = colors[frame++ % colors.length]; requestAnimationFrame(step); }
    requestAnimationFrame(step);
    </script></body></html>
    """;
  private static final String LATENCY_PAGE = """
    <!doctype html><html><body style="margin:0;overflow:hidden">
    <div id="d" style="position:fixed;left:0;top:0;right:0;bottom:0;background:#f7e9a3"></div>
    <script>
    const events = new EventSource("/events");
    events.onmessage = message => { document.getElementById("d").style.background = message.data; };
    </script></body></html>
    """;

  private final HttpServer server;
  private final ExecutorService executor;
  private final List<OutputStream> listeners;
  private int subscriptions;

  private BenchmarkServer(final HttpServer server, final ExecutorService executor) {
    this.server = server;
    this.executor = executor;
    this.listeners = new CopyOnWriteArrayList<>();
  }

  static BenchmarkServer start() throws IOException {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final InetSocketAddress address = new InetSocketAddress(loopback, 0);
    final HttpServer server = HttpServer.create(address, 0);
    final ExecutorService executor = Executors.newCachedThreadPool();
    final BenchmarkServer created = new BenchmarkServer(server, executor);
    server.createContext("/fps", exchange -> respond(exchange, FPS_PAGE));
    server.createContext("/latency", exchange -> respond(exchange, LATENCY_PAGE));
    server.createContext("/events", created::openEvents);
    server.setExecutor(executor);
    server.start();
    return created;
  }

  URI page(final String name) {
    final InetSocketAddress address = this.server.getAddress();
    final int port = address.getPort();
    return URI.create("http://127.0.0.1:" + port + "/" + name);
  }

  private static void respond(final HttpExchange exchange, final String page) throws IOException {
    final byte[] body = page.getBytes(StandardCharsets.UTF_8);
    final Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "text/html; charset=utf-8");
    headers.set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(200, body.length);
    try (final OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private void openEvents(final HttpExchange exchange) throws IOException {
    final Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "text/event-stream");
    headers.set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(200, 0);
    final OutputStream out = exchange.getResponseBody();
    out.write(": open\n\n".getBytes(StandardCharsets.UTF_8));
    out.flush();
    this.listeners.add(out);
    synchronized (this) {
      this.subscriptions++;
      this.notifyAll();
    }
  }

  synchronized int getSubscriptions() {
    return this.subscriptions;
  }

  /**
   * Waits until a page subscribed to the events after an earlier count of subscriptions.
   *
   * @param before        the count before the page was opened
   * @param timeoutMillis how long to wait
   * @throws InterruptedException if interrupted
   */
  synchronized void awaitSubscription(final int before, final long timeoutMillis) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (this.subscriptions <= before) {
      final long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      if (remaining <= 0) {
        throw new IllegalStateException("The page never subscribed to the events");
      }
      this.wait(remaining);
    }
  }

  void send(final String color) {
    final byte[] event = ("data: " + color + "\n\n").getBytes(StandardCharsets.UTF_8);
    final List<OutputStream> gone = new java.util.ArrayList<>();
    for (final OutputStream listener : this.listeners) {
      try {
        listener.write(event);
        listener.flush();
      } catch (final IOException exception) {
        // the page of a released browser
        gone.add(listener);
      }
    }
    this.listeners.removeAll(gone);
  }

  @Override
  public void close() {
    this.server.stop(0);
    this.executor.shutdownNow();
  }
}
