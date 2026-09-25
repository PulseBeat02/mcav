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
 * is still loading. The red pages {@code /to-popup-synthetic} (a script clicks a link to the popup without a click of
 * the user), {@code /named-frame} (a link in the upper left quarter opens the second page in a frame named
 * {@code inner}) and {@code /form-target} (a button over the whole page sends a form to the second page, targeting a
 * new window) test how windows open in place.
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

  /**
   * The width of the frame of {@code /named-frame}, in CSS pixels.
   */
  public static final int FRAME_WIDTH = 100;

  /**
   * The frequency of the tone of the sound pages, in hertz.
   */
  public static final int TONE_HERTZ = 1000;

  /**
   * The amplitude of the tone of the sound pages, a share of full scale.
   */
  public static final double TONE_AMPLITUDE = 0.5;

  /**
   * How long the picture and the sound of {@code /av-sync} stay on and off, in milliseconds.
   */
  public static final int TOGGLE_MILLIS = 400;

  // a 1000 Hz oscillator that plays once the page may: after the first click on it, which resumes its context
  private static final String TONE_SCRIPT =
    """
    <script>
      const context = new AudioContext();
      const oscillator = context.createOscillator();
      oscillator.frequency.value = %d;
      const gain = context.createGain();
      gain.gain.value = %s;
      oscillator.connect(gain);
      gain.connect(context.destination);
      oscillator.start();
      addEventListener('pointerdown', () => context.resume());
    </script>
    """.formatted(TONE_HERTZ, TONE_AMPLITUDE);

  // an audio element that plays the tone at the volume and muting of the address, from the first click
  private static final String ELEMENT_SCRIPT =
    """
    <audio id="tone" src="/tone.wav" loop></audio>
    <script>
      const element = document.getElementById('tone');
      const parameters = new URLSearchParams(location.search);
      element.volume = Number(parameters.get('volume') || '1');
      element.muted = parameters.get('muted') === '1';
      addEventListener('pointerdown', () => element.play());
    </script>
    """;

  // from the first click, the picture turns white and the tone plays at once, and both stop at once, in turns
  private static final String TOGGLE_SCRIPT =
    """
    <script>
      const context = new AudioContext();
      const oscillator = context.createOscillator();
      oscillator.frequency.value = %d;
      const gain = context.createGain();
      gain.gain.value = 0;
      oscillator.connect(gain);
      gain.connect(context.destination);
      oscillator.start();
      let on = false;
      let started = false;
      addEventListener('pointerdown', () => {
        context.resume();
        if (started) {
          return;
        }
        started = true;
        setInterval(() => {
          on = !on;
          document.body.style.background = on ? '#ffffff' : '#000000';
          gain.gain.setValueAtTime(on ? %s : 0, context.currentTime);
        }, %d);
      });
    </script>
    """.formatted(TONE_HERTZ, TONE_AMPLITUDE, TOGGLE_MILLIS);

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
          buttons: String(event.buttons || 0),
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
      document.addEventListener('wheel', event => report('wheel', { clientX: event.clientX, clientY: event.clientY, key: String(Math.sign(event.deltaY)) }));
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
      httpServer.createContext("/tone", exchange -> pages.page(exchange, "tone", MAIN_COLOR, TONE_SCRIPT));
      httpServer.createContext("/tone-element", exchange -> pages.page(exchange, "tone-element", MAIN_COLOR, ELEMENT_SCRIPT));
      httpServer.createContext("/av-sync", exchange -> pages.page(exchange, "av-sync", 0x000000, TOGGLE_SCRIPT));
      httpServer.createContext("/tone.wav", TestPages::toneWave);
      httpServer.createContext("/hooked", pages::hooked);
      httpServer.createContext("/event", pages::event);
      httpServer.createContext("/dialog", exchange ->
        pages.script(exchange, "alert('hi'); confirm('sure?'); document.body.style.background = '#00ff00';")
      );
      httpServer.createContext("/to-file", exchange -> pages.script(exchange, "location.href = 'file:///etc/passwd';"));
      httpServer.createContext("/to-download", exchange -> pages.script(exchange, "location.href = '/download';"));
      httpServer.createContext("/to-popup", exchange -> pages.script(exchange, "window.open('/popup', '_blank');"));
      httpServer.createContext("/to-popup-link", exchange ->
        pages.script(
          exchange,
          "document.body.insertAdjacentHTML('beforeend', '<a href=\"/popup\" target=\"_blank\" style=\"position:fixed;inset:0\"></a>');"
        )
      );
      httpServer.createContext("/to-popup-synthetic", exchange ->
        pages.script(
          exchange,
          "document.body.insertAdjacentHTML('beforeend', '<a id=\"link\" href=\"/popup\" target=\"_blank\">popup</a>');" +
          " document.getElementById('link').click();"
        )
      );
      httpServer.createContext("/named-frame", exchange ->
        pages.html(
          exchange,
          "<iframe name=\"inner\" src=\"about:blank\" style=\"position:fixed;right:0;bottom:0;width:" +
          FRAME_WIDTH +
          "px;height:60px;border:0\"></iframe>" +
          "<a href=\"/second\" target=\"inner\" style=\"position:fixed;left:0;top:0;width:50%;height:50%\"></a>"
        )
      );
      httpServer.createContext("/form-target", exchange ->
        pages.html(
          exchange,
          "<form action=\"/second\" method=\"get\"><button type=\"submit\" formtarget=\"_blank\"" +
          " style=\"position:fixed;inset:0;opacity:0\">send</button></form>"
        )
      );
      httpServer.createContext("/download", TestPages::download);
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
   * Forgets the events the pages reported so far.
   */
  public void clearEvents() {
    synchronized (this.events) {
      this.events.clear();
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
    this.page(exchange, name, color, "");
  }

  /**
   * Serves one second of the tone as a WAV file: 16-bit stereo at 48 kHz.
   *
   * @param exchange the request
   * @throws IOException if the answer cannot be sent
   */
  private static void toneWave(final HttpExchange exchange) throws IOException {
    final int rate = 48_000;
    final java.nio.ByteBuffer wave = java.nio.ByteBuffer.allocate(44 + rate * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    wave.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + rate * 4).put("WAVE".getBytes(StandardCharsets.US_ASCII));
    wave.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 2);
    wave.putInt(rate).putInt(rate * 4).putShort((short) 4).putShort((short) 16);
    wave.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(rate * 4);
    for (int frame = 0; frame < rate; frame++) {
      final short sample = (short) Math.round(Math.sin((2 * Math.PI * TONE_HERTZ * frame) / rate) * TONE_AMPLITUDE * 32767);
      wave.putShort(sample).putShort(sample);
    }
    final byte[] body = wave.array();
    exchange.getResponseHeaders().add("Content-Type", "audio/wav");
    exchange.sendResponseHeaders(200, body.length);
    try (final OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private void page(final HttpExchange exchange, final String name, final int color, final String extra) throws IOException {
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
      extra +
      "</body></html>";
    final byte[] body = html.getBytes(StandardCharsets.UTF_8);
    final Headers headers = exchange.getResponseHeaders();
    headers.add("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(200, body.length);
    try (final OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  /**
   * Serves a red page with more elements.
   *
   * @param exchange the request
   * @param elements the HTML of the elements
   * @throws IOException if the answer cannot be sent
   */
  private void html(final HttpExchange exchange, final String elements) throws IOException {
    final String html =
      "<!doctype html><html><head><style>html,body{margin:0;width:100%;height:100%;background:#ff0000;}</style></head><body>" +
      elements +
      "</body></html>";
    final byte[] body = html.getBytes(StandardCharsets.UTF_8);
    final Headers headers = exchange.getResponseHeaders();
    headers.add("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(200, body.length);
    try (final OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  /**
   * Serves the red main page with a script that runs when the page has loaded.
   *
   * @param exchange the request
   * @param script   the script
   * @throws IOException if the response fails
   */
  private void script(final HttpExchange exchange, final String script) throws IOException {
    final String html =
      "<!doctype html><html><head><style>html,body{margin:0;width:100%;height:100%;background:#ff0000;}</style></head>" +
      "<body><script>window.addEventListener('load', () => setTimeout(() => { " +
      script +
      " }, 200));</script></body></html>";
    final byte[] body = html.getBytes(StandardCharsets.UTF_8);
    final Headers headers = exchange.getResponseHeaders();
    headers.add("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(200, body.length);
    try (final OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private static void download(final HttpExchange exchange) throws IOException {
    final byte[] body = "not for the server".getBytes(StandardCharsets.UTF_8);
    final Headers headers = exchange.getResponseHeaders();
    headers.add("Content-Type", "application/octet-stream");
    headers.add("Content-Disposition", "attachment; filename=\"evil.exe\"");
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
    final String rawButtons = parameters.getOrDefault("buttons", "0");
    final String key = parameters.getOrDefault("key", "");
    final int x = Integer.parseInt(rawX);
    final int y = Integer.parseInt(rawY);
    final int button = Integer.parseInt(rawButton);
    final int buttons = Integer.parseInt(rawButtons);
    return new PageEvent(page, type, x, y, button, buttons, key);
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
    private final int buttons;
    private final String key;

    PageEvent(final String page, final String type, final int x, final int y, final int button, final int buttons, final String key) {
      this.page = page;
      this.type = type;
      this.x = x;
      this.y = y;
      this.button = button;
      this.buttons = buttons;
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
     * Gets the buttons held during a mouse event, 1 for the left and 2 for the right button.
     *
     * @return the buttons
     */
    public int getButtons() {
      return this.buttons;
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
      return (
        this.page +
        " " +
        this.type +
        " " +
        this.x +
        "," +
        this.y +
        " button " +
        this.button +
        " buttons " +
        this.buttons +
        " key '" +
        this.key +
        "'"
      );
    }
  }
}
