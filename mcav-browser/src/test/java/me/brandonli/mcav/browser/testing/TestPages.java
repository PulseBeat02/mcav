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
package me.brandonli.mcav.browser.testing;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Web pages on the loopback interface for browser tests.
 *
 * <p>{@code /main} is red, {@code /popup} is blue and {@code /second} is green, so the streamed frames show which
 * page is on screen. Each page reports its mouse and keyboard events serially, preserving DOM event order in the
 * server's arrival log. Different pages remain independent. A popup waits for its final report before closing.
 * Pressing {@code o} on the main page opens the popup, and pressing {@code x} on the popup closes it. Pressing
 * {@code n} on the popup opens the second page and closes the popup a second later; pressing {@code w} on the
 * second page closes it 300 milliseconds later.
 * {@code /hooked} is the main page, but the server runs a hook before answering, so tests can act while the browser
 * is still loading.
 */
public final class TestPages implements AutoCloseable {

  /**
   * The background color of the main page as packed RGB.
   */
  public static final int MAIN_COLOR = 0xFF0000;

  /**
   * The background color of the popup as packed RGB.
   */
  public static final int POPUP_COLOR = 0x0000FF;

  /**
   * The background color of the second popup as packed RGB.
   */
  public static final int SECOND_COLOR = 0x00FF00;

  private static final String SCRIPT =
    """
    <script>
      const page = document.body.dataset.page;
      // Serialize transport as well as DOM observation: independent fetches can arrive out of order.
      let reports = Promise.resolve();
      const report = (type, event) => {
        const parameters = new URLSearchParams({
          page: page,
          type: type,
          x: String(Math.round(event.clientX || 0)),
          y: String(Math.round(event.clientY || 0)),
          button: String(event.button || 0),
          key: event.key || ''
        });
        const address = '/event?' + parameters.toString();
        reports = reports.then(async () => {
          const response = await fetch(address, { keepalive: true });
          if (!response.ok) {
            throw new Error('Event reporting failed with HTTP ' + response.status);
          }
        }).catch(error => console.error('TestPages event reporting failed', page, type, error));
        return reports;
      };
      const closeLater = delay => setTimeout(() => {
        report('closing', {}).then(() => window.close());
      }, delay);
      for (const type of ['mousemove', 'mousedown', 'mouseup', 'click', 'dblclick']) {
        document.addEventListener(type, event => report(type, event));
      }
      document.addEventListener('contextmenu', event => {
        event.preventDefault();
        report('contextmenu', event);
      });
      document.addEventListener('keydown', event => {
        const reported = report('keydown', event);
        if (page === 'main' && event.key === 'o') {
          window.open('/popup', '_blank');
        }
        if (page === 'popup' && event.key === 'n') {
          window.open('/second', '_blank');
          closeLater(1000);
        }
        if (page === 'second' && event.key === 'w') {
          closeLater(300);
        }
        if (page === 'popup' && event.key === 'x') {
          reported.then(() => window.close());
        }
      });
      report('size', { clientX: window.innerWidth, clientY: window.innerHeight });
    </script>
    """;

  private final HttpServer server;
  private final List<PageEvent> events;
  private final AtomicReference<Runnable> loadHook;

  private TestPages(final HttpServer server) {
    this.server = server;
    this.events = new ArrayList<>();
    this.loadHook = new AtomicReference<>(() -> {});
  }

  /**
   * Starts the server on a free port of the loopback interface.
   *
   * @return the running server
   */
  public static TestPages start() {
    try {
      final InetAddress loopback = InetAddress.ofLiteral("127.0.0.1");
      final InetSocketAddress address = new InetSocketAddress(loopback, 0);
      final HttpServer httpServer = HttpServer.create(address, 0);
      final TestPages pages = new TestPages(httpServer);
      httpServer.createContext("/main", exchange -> pages.page(exchange, "main", MAIN_COLOR));
      httpServer.createContext("/popup", exchange -> pages.page(exchange, "popup", POPUP_COLOR));
      httpServer.createContext("/second", exchange -> pages.page(exchange, "second", SECOND_COLOR));
      httpServer.createContext("/hooked", pages::hooked);
      httpServer.createContext("/event", pages::event);
      httpServer.start();
      return pages;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Gets the address of a page.
   *
   * @param path the path, such as {@code /main}
   * @return the address
   */
  public URI uri(final String path) {
    final InetSocketAddress address = this.server.getAddress();
    final int port = address.getPort();
    return URI.create("http://127.0.0.1:" + port + path);
  }

  /**
   * Sets the hook that runs before {@code /hooked} is answered.
   *
   * @param hook the hook
   */
  public void setLoadHook(final Runnable hook) {
    this.loadHook.set(hook);
  }

  /**
   * Gets the events the pages reported so far.
   *
   * @return a copy of the events in the order they arrived
   */
  public List<PageEvent> getEvents() {
    synchronized (this.events) {
      return List.copyOf(this.events);
    }
  }

  /**
   * Gets the events of one type the pages reported so far.
   *
   * @param type the event type, such as {@code click}
   * @return a copy of the matching events in the order they arrived
   */
  public List<PageEvent> getEvents(final String type) {
    final List<PageEvent> all = this.getEvents();
    final List<PageEvent> matching = new ArrayList<>();
    for (final PageEvent event : all) {
      final String eventType = event.getType();
      if (eventType.equals(type)) {
        matching.add(event);
      }
    }
    return matching;
  }

  /**
   * Maps a coordinate of a frame onto the page the way the players do, using the size the page reported when it
   * loaded.
   *
   * @param value     the coordinate in the frame
   * @param frameSize the width or height of the frames
   * @param width     true to map a horizontal coordinate, false for a vertical one
   * @return the coordinate on the page
   */
  public int toPage(final int value, final int frameSize, final boolean width) {
    final List<PageEvent> sizes = this.getEvents("size");
    final PageEvent size = sizes.getFirst();
    final int pageSize = width ? size.getX() : size.getY();
    final long scaled = Math.round(value * ((double) pageSize / frameSize));
    return Math.clamp(scaled, 0, pageSize - 1);
  }

  /**
   * Counts the events of one type the pages reported so far.
   *
   * @param type the event type
   * @return the number of matching events
   */
  public int count(final String type) {
    final List<PageEvent> matching = this.getEvents(type);
    return matching.size();
  }

  private void page(final HttpExchange exchange, final String name, final int color) throws IOException {
    final String hex = String.format("#%06x", color);
    final String html =
      "<!doctype html><html><head><title>" +
      name +
      "</title><style>html,body{margin:0;width:100%;height:100%;background:" +
      hex +
      ";}</style></head><body data-page=\"" +
      name +
      "\">" +
      SCRIPT +
      "</body></html>";
    final byte[] body = html.getBytes(StandardCharsets.UTF_8);
    final Headers headers = exchange.getResponseHeaders();
    headers.add("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(200, body.length);
    try (final OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private void hooked(final HttpExchange exchange) throws IOException {
    final Runnable hook = this.loadHook.get();
    hook.run();
    this.page(exchange, "main", MAIN_COLOR);
  }

  private void event(final HttpExchange exchange) throws IOException {
    final URI requestUri = exchange.getRequestURI();
    final String query = requestUri.getRawQuery();
    final Map<String, String> parameters = parse(query);
    final PageEvent pageEvent = toEvent(parameters);
    synchronized (this.events) {
      this.events.add(pageEvent);
    }
    exchange.sendResponseHeaders(204, -1);
    exchange.close();
  }

  private static PageEvent toEvent(final Map<String, String> parameters) {
    final String page = parameters.getOrDefault("page", "");
    final String type = parameters.getOrDefault("type", "");
    final String rawX = parameters.getOrDefault("x", "0");
    final String rawY = parameters.getOrDefault("y", "0");
    final String rawButton = parameters.getOrDefault("button", "0");
    final String key = parameters.getOrDefault("key", "");
    final int x = Integer.parseInt(rawX);
    final int y = Integer.parseInt(rawY);
    final int button = Integer.parseInt(rawButton);
    return new PageEvent(page, type, x, y, button, key);
  }

  private static Map<String, String> parse(final String query) {
    final Map<String, String> parameters = new HashMap<>();
    if (query == null) {
      return parameters;
    }
    // a limit of -1 keeps every pair, including empty ones, which are skipped below like any pair without a name
    final String[] pairs = query.split("&", -1);
    for (final String pair : pairs) {
      final int separator = pair.indexOf('=');
      if (separator < 0) {
        continue;
      }
      final String encodedName = pair.substring(0, separator);
      final String encodedValue = pair.substring(separator + 1);
      final String name = URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
      final String value = URLDecoder.decode(encodedValue, StandardCharsets.UTF_8);
      parameters.put(name, value);
    }
    return parameters;
  }

  @Override
  public void close() {
    this.server.stop(0);
  }

  /**
   * An event a page reported.
   */
  public static final class PageEvent {

    private final String page;
    private final String type;
    private final int x;
    private final int y;
    private final int button;
    private final String key;

    PageEvent(final String page, final String type, final int x, final int y, final int button, final String key) {
      this.page = page;
      this.type = type;
      this.x = x;
      this.y = y;
      this.button = button;
      this.key = key;
    }

    /**
     * Gets the page that reported the event.
     *
     * @return {@code main} or {@code popup}
     */
    public String getPage() {
      return this.page;
    }

    /**
     * Gets the DOM event type.
     *
     * @return the type, such as {@code click}
     */
    public String getType() {
      return this.type;
    }

    /**
     * Gets the x coordinate in CSS pixels.
     *
     * @return the x coordinate
     */
    public int getX() {
      return this.x;
    }

    /**
     * Gets the y coordinate in CSS pixels.
     *
     * @return the y coordinate
     */
    public int getY() {
      return this.y;
    }

    /**
     * Gets the mouse button, 0 for the left and 2 for the right button.
     *
     * @return the button
     */
    public int getButton() {
      return this.button;
    }

    /**
     * Gets the key of a keyboard event.
     *
     * @return the key, or an empty string for mouse events
     */
    public String getKey() {
      return this.key;
    }

    @Override
    public String toString() {
      return this.page + " " + this.type + " " + this.x + "," + this.y + " button " + this.button + " key '" + this.key + "'";
    }
  }
}
