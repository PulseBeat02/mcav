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
package me.brandonli.mcav.plugin.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class TonePage implements AutoCloseable {

  static final int TONE_HERTZ = 1000;

  private static final String PAGE = """
  <!doctype html><html><head><style>html,body{margin:0;width:100%%;height:100%%;background:#ff0000;}</style></head>
  <body><script>
    const context = new AudioContext();
    const oscillator = context.createOscillator();
    oscillator.frequency.value = %s;
    const gain = context.createGain();
    gain.gain.value = 0.5;
    oscillator.connect(gain);
    gain.connect(context.destination);
    oscillator.start();
    addEventListener('pointerdown', () => context.resume());
  </script></body></html>
  """.formatted(TONE_HERTZ);

  private static final String LOOPBACK_HOST = "localhost";
  private static final byte[] LOOPBACK_OCTETS = { 127, 0, 0, 1 };

  private static final String TONE_PATH = "/tone";
  private static final String URL_PREFIX = "http://127.0.0.1:";

  private final HttpServer server;

  private TonePage(final HttpServer server) {
    this.server = server;
  }

  // 127.0.0.1, the address the browser is sent to, even where Java prefers IPv6
  static TonePage start() throws IOException {
    final InetAddress loopback = InetAddress.getByAddress(LOOPBACK_HOST, LOOPBACK_OCTETS);
    final HttpServer server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
    server.createContext(TONE_PATH, TonePage::sendPage);
    server.start();
    return new TonePage(server);
  }

  private static void sendPage(final HttpExchange exchange) throws IOException {
    final byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);
    try (final OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  @Override
  public void close() {
    this.server.stop(0);
  }

  URI getUri() {
    return URI.create(URL_PREFIX + this.server.getAddress().getPort() + TONE_PATH);
  }
}
