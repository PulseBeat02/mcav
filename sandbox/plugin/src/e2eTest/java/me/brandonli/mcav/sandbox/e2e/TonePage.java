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

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Serves a page on this machine that shows red and plays a 1000 Hz tone through Web Audio as soon as it may.
 */
final class TonePage implements AutoCloseable {

  /**
   * The frequency of the tone, in hertz.
   */
  static final int TONE_HERTZ = 1000;

  private static final String PAGE =
    """
    <!doctype html><html><head><style>html,body{margin:0;width:100%;height:100%;background:#ff0000;}</style></head>
    <body><script>
      const context = new AudioContext();
      const oscillator = context.createOscillator();
      oscillator.frequency.value = 1000;
      const gain = context.createGain();
      gain.gain.value = 0.5;
      oscillator.connect(gain);
      gain.connect(context.destination);
      oscillator.start();
      addEventListener('pointerdown', () => context.resume());
    </script></body></html>
    """;

  private final HttpServer server;

  private TonePage(final HttpServer server) {
    this.server = server;
  }

  /**
   * Starts the page on a free port of the loopback interface.
   *
   * @return the running page
   * @throws IOException if no port is free
   */
  static TonePage start() throws IOException {
    final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/tone", exchange -> {
      final byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    return new TonePage(server);
  }

  /**
   * Gets the address of the page.
   *
   * @return the address
   */
  URI getUri() {
    return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/tone");
  }

  @Override
  public void close() {
    this.server.stop(0);
  }
}
