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

import com.google.common.base.MoreObjects;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

// Web pages on the loopback interface for browser tests: each page has its own color, so the frames show which one is on screen.
public final class TestPages implements AutoCloseable {

  public static final String MAIN_PATH = "/main";
  public static final String POPUP_PATH = "/popup";
  public static final String SECOND_PATH = "/second";
  public static final String TONE_PATH = "/tone";
  public static final String TONE_ELEMENT_PATH = "/tone-element";
  public static final String TONE_WRAPPED_PATH = "/tone-wrapped";
  public static final String AV_SYNC_PATH = "/av-sync";
  public static final String TONE_WAVE_PATH = "/tone.wav";
  public static final String EVENT_PATH = "/event";
  public static final String DIALOG_PATH = "/dialog";
  public static final String TO_FILE_PATH = "/to-file";
  public static final String TO_DOWNLOAD_PATH = "/to-download";
  public static final String TO_POPUP_PATH = "/to-popup";
  public static final String TO_POPUP_LINK_PATH = "/to-popup-link";
  public static final String TO_POPUP_SYNTHETIC_PATH = "/to-popup-synthetic";
  public static final String NAMED_FRAME_PATH = "/named-frame";
  public static final String FORM_TARGET_PATH = "/form-target";
  public static final String DOWNLOAD_PATH = "/download";

  public static final int MAIN_COLOR = 0xFF0000;
  public static final int POPUP_COLOR = 0x0000FF;
  public static final int SECOND_COLOR = 0x00FF00;
  public static final int AV_SYNC_COLOR = 0x000000;

  public static final int FRAME_WIDTH = 100;

  public static final int TONE_HERTZ = 1000;
  public static final double TONE_AMPLITUDE = 0.5;

  // how long /av-sync keeps its picture and sound on, then off
  public static final int TOGGLE_MILLISECONDS = 400;

  private static final Pattern PAIR_SEPARATOR = Pattern.compile("&");
  private static final char NAME_VALUE_SEPARATOR = '=';
  private static final int KEEP_TRAILING_EMPTY_PARTS = -1;

  private static final String LOOPBACK_ADDRESS = "127.0.0.1";

  private static final int EPHEMERAL_PORT = 0;
  private static final int DEFAULT_BACKLOG = 0;
  private static final int STOP_DELAY_SECONDS = 0;

  private static final String MAIN_PAGE_NAME = "main";

  private static final String CONTENT_TYPE_HEADER = "Content-Type";

  private static final String HTML_CONTENT_TYPE = "text/html; charset=utf-8";
  private static final String WAVE_CONTENT_TYPE = "audio/wav";
  private static final String DOWNLOAD_CONTENT_TYPE = "application/octet-stream";

  private static final int HTTP_OK = 200;
  private static final int HTTP_NO_CONTENT = 204;
  private static final int NO_RESPONSE_BODY = -1;

  private static final int SAMPLE_RATE = 48_000;
  private static final int CHANNELS = 2;
  private static final int BITS_PER_SAMPLE = 16;
  private static final int BYTES_PER_FRAME = 4;
  private static final int PCM_FORMAT = 1;
  private static final int FORMAT_CHUNK_BYTES = 16;
  private static final int WAVE_HEADER_BYTES = 44;
  private static final int RIFF_HEADER_BYTES = 8;

  private static final String RIFF_ID = "RIFF";
  private static final String WAVE_ID = "WAVE";
  private static final String FORMAT_ID = "fmt ";
  private static final String DATA_ID = "data";

  private static final int SCRIPT_DELAY_MILLISECONDS = 200;

  private static final String TONE_SCRIPT = """
  <script>
    const CLOCK_REPORTS = 10;
    const CLOCK_INTERVAL_MILLISECONDS = 1000;

    const context = new AudioContext();
    const reportClock = clock => {
      report("time", { key: context.currentTime.toFixed(2) });

      if (++clock.times === CLOCK_REPORTS) {
        clearInterval(clock.interval);
      }
    };

    const reportState = () => {
      report("state", { key: context.state });
      // whether the clock of the context runs: without an audio device that renders, it stands still
      const clock = { times: 0 };
      clock.interval = setInterval(() => reportClock(clock), CLOCK_INTERVAL_MILLISECONDS);
    };

    context.addEventListener("statechange", reportState);
    const oscillator = context.createOscillator();
    oscillator.frequency.value = %s;
    const gain = context.createGain();
    gain.gain.value = %s;
    oscillator.connect(gain);
    gain.connect(context.destination);
    oscillator.start();
    const reportFailure = exception => report("state", { key: `resume failed: ${exception.name}` });
    const resumeContext = () => context.resume().catch(reportFailure);
    addEventListener("pointerdown", resumeContext);
  </script>
  """.formatted(TONE_HERTZ, TONE_AMPLITUDE);

  private static final String WRAPPED_TONE_SCRIPT = """
  <script>
    const FORGED_FRAMES = 2048;
    const FORGED_LEVEL = 0.5;
    const TAP_INTERVAL_MILLISECONDS = 50;
    const CHANNELS = %s;
    const SAMPLE_RATE = %s;

    const taps = new Set();
    const keep = node => {
      if (node instanceof ScriptProcessorNode) {
        taps.add(node);
      }

      return node;
    };

    const wrapFactory = (prototype, name) => {
      const original = prototype[name];
      if (typeof original === "function") {
        const createNode = function (...callArguments) {
          return keep(original.apply(this, callArguments));
        };

        prototype[name] = createNode;
      }
    };

    for (const prototype of [BaseAudioContext.prototype, AudioContext.prototype]) {
      for (const name of ["createScriptProcessor", "createGain", "createMediaElementSource"]) {
        wrapFactory(prototype, name);
      }
    }
    const connect = AudioNode.prototype.connect;
    const connectAndKeep = function (target, ...rest) {
      keep(this);
      keep(target);

      return connect.call(this, target, ...rest);
    };

    AudioNode.prototype.connect = connectAndKeep;
    const listen = EventTarget.prototype.addEventListener;
    const listenAndKeep = function (type, listener, ...rest) {
      keep(this);

      return listen.call(this, type, listener, ...rest);
    };

    EventTarget.prototype.addEventListener = listenAndKeep;
    const context = new AudioContext();
    const oscillator = context.createOscillator();
    oscillator.frequency.value = %s;
    const gain = context.createGain();
    gain.gain.value = %s;
    oscillator.connect(gain);
    gain.connect(context.destination);
    oscillator.start();
    const forged = new AudioBuffer({ length: FORGED_FRAMES, numberOfChannels: CHANNELS, sampleRate: SAMPLE_RATE });
    forged.getChannelData(0).fill(FORGED_LEVEL);
    forged.getChannelData(1).fill(FORGED_LEVEL);
    const dispatchForgedFrames = () => {
      for (const tap of taps) {
        const event = new AudioProcessingEvent("audioprocess", { playbackTime: 0, inputBuffer: forged, outputBuffer: forged });
        if (typeof tap.onaudioprocess === "function") {
          tap.onaudioprocess(event);
        }
        tap.dispatchEvent(event);
      }
    };

    setInterval(dispatchForgedFrames, TAP_INTERVAL_MILLISECONDS);
    report("taps", { clientX: taps.size });
    addEventListener("pointerdown", () => context.resume());
  </script>
  """.formatted(CHANNELS, SAMPLE_RATE, TONE_HERTZ, TONE_AMPLITUDE);

  private static final String ELEMENT_SCRIPT = """
  <audio id="tone" src="/tone.wav" loop></audio>
  <script>
    const element = document.getElementById("tone");
    const parameters = new URLSearchParams(location.search);
    element.volume = Number(parameters.get("volume") || "1");
    element.muted = parameters.get("muted") === "1";
    const reportPlaying = () => report("play", { key: "playing" });
    const reportFailure = exception => report("play", { key: exception.name });
    const playElement = () => element.play()
      .then(reportPlaying)
      .catch(reportFailure);
    addEventListener("pointerdown", playElement);
  </script>
  """;

  private static final String TOGGLE_SCRIPT = """
  <script>
    const LIGHT_COLOR = "#ffffff";
    const DARK_COLOR = "#000000";

    const context = new AudioContext();
    const oscillator = context.createOscillator();
    oscillator.frequency.value = %s;
    const gain = context.createGain();
    gain.gain.value = 0;
    oscillator.connect(gain);
    gain.connect(context.destination);
    oscillator.start();
    let on = false;
    let started = false;
    const toggle = () => {
      on = !on;
      document.body.style.background = on ? LIGHT_COLOR : DARK_COLOR;
      gain.gain.setValueAtTime(on ? %s : 0, context.currentTime);
    };

    const startToggling = () => {
      context.resume();

      if (started) {
        return;
      }

      started = true;
      setInterval(toggle, %s);
    };

    addEventListener("pointerdown", startToggling);
  </script>
  """.formatted(TONE_HERTZ, TONE_AMPLITUDE, TOGGLE_MILLISECONDS);

  private static final String SCRIPT = """
  <script>
    const POPUP_CLOSE_DELAY_MILLISECONDS = 1000;
    const SECOND_CLOSE_DELAY_MILLISECONDS = 300;

    const page = document.body.dataset.page;
    // one report at a time: independent fetches can arrive out of order
    let reports = Promise.resolve();
    const checkResponse = response => {
      if (!response.ok) {
        const message = `HTTP status ${response.status} failed the event report!`;
        throw new Error(message);
      }
    };

    const report = (type, event) => {
      const parameters = new URLSearchParams({
        page: page,
        type: type,
        x: String(Math.round(event.clientX || 0)),
        y: String(Math.round(event.clientY || 0)),
        button: String(event.button || 0),
        buttons: String(event.buttons || 0),
        key: event.key || ""
      });
      const address = "/event?" + parameters.toString();
      const sendReport = () => fetch(address, { keepalive: true });
      const reportFailure = exception => console.error(`Event ${type} of page ${page} could not be reported.`, exception);
      reports = reports
        .then(sendReport)
        .then(checkResponse)
        .catch(reportFailure);

      return reports;
    };

    const closeWindow = () => window.close();
    const reportClosing = () => {
      report("closing", {}).then(closeWindow);
    };

    const closeLater = delay => setTimeout(reportClosing, delay);
    for (const type of ["mousemove", "mousedown", "mouseup", "click", "dblclick"]) {
      document.addEventListener(type, event => report(type, event));
    }
    const reportWheel = event => report("wheel", {
      clientX: event.clientX,
      clientY: event.clientY,
      key: String(Math.sign(event.deltaY))
    });
    document.addEventListener("wheel", reportWheel);
    const preventContextMenu = event => {
      event.preventDefault();
      report("contextmenu", event);
    };

    document.addEventListener("contextmenu", preventContextMenu);
    const onKeyDown = event => {
      const reported = report("keydown", event);
      if (page === "main" && event.key === "o") {
        window.open("/popup", "_blank");
      }
      if (page === "popup" && event.key === "n") {
        window.open("/second", "_blank");
        closeLater(POPUP_CLOSE_DELAY_MILLISECONDS);
      }
      if (page === "second" && event.key === "w") {
        closeLater(SECOND_CLOSE_DELAY_MILLISECONDS);
      }
      if (page === "popup" && event.key === "x") {
        reported.then(closeWindow);
      }
    };

    document.addEventListener("keydown", onKeyDown);
    report("size", { clientX: window.innerWidth, clientY: window.innerHeight });
  </script>
  """;

  private final HttpServer server;
  private final List<PageEvent> events;

  private TestPages(final HttpServer server) {
    this.server = server;
    this.events = new ArrayList<>();
  }

  public static TestPages start() {
    try {
      final InetAddress loopback = InetAddress.ofLiteral(LOOPBACK_ADDRESS);
      final InetSocketAddress address = new InetSocketAddress(loopback, EPHEMERAL_PORT);
      final HttpServer httpServer = HttpServer.create(address, DEFAULT_BACKLOG);
      final TestPages pages = new TestPages(httpServer);
      httpServer.createContext(MAIN_PATH, exchange -> servePage(exchange, MAIN_PAGE_NAME, MAIN_COLOR));
      httpServer.createContext(POPUP_PATH, exchange -> servePage(exchange, "popup", POPUP_COLOR));
      httpServer.createContext(SECOND_PATH, exchange -> servePage(exchange, "second", SECOND_COLOR));
      httpServer.createContext(TONE_PATH, exchange -> servePage(exchange, "tone", MAIN_COLOR, TONE_SCRIPT));
      httpServer.createContext(TONE_ELEMENT_PATH, exchange -> servePage(exchange, "tone-element", MAIN_COLOR, ELEMENT_SCRIPT));
      httpServer.createContext(TONE_WRAPPED_PATH, exchange -> servePage(exchange, "tone-wrapped", MAIN_COLOR, WRAPPED_TONE_SCRIPT));
      httpServer.createContext(AV_SYNC_PATH, exchange -> servePage(exchange, "av-sync", AV_SYNC_COLOR, TOGGLE_SCRIPT));
      httpServer.createContext(TONE_WAVE_PATH, TestPages::serveToneWave);
      httpServer.createContext(EVENT_PATH, pages::recordEvent);
      httpServer.createContext(DIALOG_PATH, TestPages::serveDialogs);
      httpServer.createContext(TO_FILE_PATH, exchange ->
        serveScript(
          exchange,
          """
          location.href = "file:///etc/passwd";\
          """
        )
      );
      httpServer.createContext(TO_DOWNLOAD_PATH, exchange ->
        serveScript(
          exchange,
          """
          location.href = "/download";\
          """
        )
      );
      httpServer.createContext(TO_POPUP_PATH, exchange ->
        serveScript(
          exchange,
          """
          window.open("/popup", "_blank");\
          """
        )
      );
      httpServer.createContext(TO_POPUP_LINK_PATH, TestPages::serveLinkToAPopup);
      httpServer.createContext(TO_POPUP_SYNTHETIC_PATH, TestPages::serveLinkToAPopupThatAScriptClicks);
      httpServer.createContext(NAMED_FRAME_PATH, TestPages::serveLinkIntoANamedFrame);
      httpServer.createContext(FORM_TARGET_PATH, TestPages::serveFormThatTargetsANewWindow);
      httpServer.createContext(DOWNLOAD_PATH, TestPages::serveDownload);
      httpServer.start();
      return pages;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Override
  public void close() {
    this.server.stop(STOP_DELAY_SECONDS);
  }

  private static void serveDialogs(final HttpExchange exchange) throws IOException {
    serveScript(
      exchange,
      """
      alert("hi"); confirm("sure?"); document.body.style.background = "#00ff00";\
      """
    );
  }

  private static void serveLinkToAPopup(final HttpExchange exchange) throws IOException {
    serveScript(
      exchange,
      """
      document.body.insertAdjacentHTML("beforeend", `<a href="/popup" target="_blank" style="position:fixed;inset:0"></a>`);\
      """
    );
  }

  private static void serveLinkToAPopupThatAScriptClicks(final HttpExchange exchange) throws IOException {
    serveScript(
      exchange,
      """
      document.body.insertAdjacentHTML("beforeend", `<a id="link" href="/popup" target="_blank">popup</a>`); document.getElementById("link").click();\
      """
    );
  }

  private static void serveLinkIntoANamedFrame(final HttpExchange exchange) throws IOException {
    serveHtml(
      exchange,
      "<iframe name=\"inner\" src=\"about:blank\" style=\"position:fixed;right:0;bottom:0;width:%spx;height:60px;border:0\"></iframe><a href=\"/second\" target=\"inner\" style=\"position:fixed;left:0;top:0;width:50%%;height:50%%\"></a>".formatted(
        FRAME_WIDTH
      )
    );
  }

  private static void serveFormThatTargetsANewWindow(final HttpExchange exchange) throws IOException {
    serveHtml(
      exchange,
      "<form action=\"/second\" method=\"get\"><button type=\"submit\" formtarget=\"_blank\" style=\"position:fixed;inset:0;opacity:0\">send</button></form>"
    );
  }

  public URI uri(final String path) {
    final InetSocketAddress address = this.server.getAddress();
    final int port = address.getPort();
    return URI.create("http://%s:%s%s".formatted(LOOPBACK_ADDRESS, port, path));
  }

  public void clearEvents() {
    synchronized (this.events) {
      this.events.clear();
    }
  }

  public int count(final String type) {
    final List<PageEvent> matching = this.getEvents(type);
    return matching.size();
  }

  private static void servePage(final HttpExchange exchange, final String name, final int color) throws IOException {
    servePage(exchange, name, color, "");
  }

  private static void servePage(final HttpExchange exchange, final String name, final int color, final String extra) throws IOException {
    final String hexadecimal = String.format(Locale.ROOT, "#%06x", color);
    final String html =
      "<!doctype html><html><head><title>%s</title><style>html,body{margin:0;width:100%%;height:100%%;background:%s;}</style></head><body data-page=\"%s\">%s%s</body></html>".formatted(
        name,
        hexadecimal,
        name,
        SCRIPT,
        extra
      );
    final byte[] body = html.getBytes(StandardCharsets.UTF_8);
    sendBody(exchange, HTML_CONTENT_TYPE, body);
  }

  private static void serveToneWave(final HttpExchange exchange) throws IOException {
    final byte[] body = createToneWave();
    sendBody(exchange, WAVE_CONTENT_TYPE, body);
  }

  private static byte[] createToneWave() {
    final ByteBuffer wave = ByteBuffer.allocate(WAVE_HEADER_BYTES + SAMPLE_RATE * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN);
    wave
      .put(RIFF_ID.getBytes(StandardCharsets.US_ASCII))
      .putInt(WAVE_HEADER_BYTES - RIFF_HEADER_BYTES + SAMPLE_RATE * BYTES_PER_FRAME)
      .put(WAVE_ID.getBytes(StandardCharsets.US_ASCII));
    wave
      .put(FORMAT_ID.getBytes(StandardCharsets.US_ASCII))
      .putInt(FORMAT_CHUNK_BYTES)
      .putShort((short) PCM_FORMAT)
      .putShort((short) CHANNELS);
    wave
      .putInt(SAMPLE_RATE)
      .putInt(SAMPLE_RATE * BYTES_PER_FRAME)
      .putShort((short) BYTES_PER_FRAME)
      .putShort((short) BITS_PER_SAMPLE);
    wave.put(DATA_ID.getBytes(StandardCharsets.US_ASCII)).putInt(SAMPLE_RATE * BYTES_PER_FRAME);
    for (int frame = 0; frame < SAMPLE_RATE; frame++) {
      final short sample = toneSample(frame);
      wave.putShort(sample).putShort(sample);
    }

    return wave.array();
  }

  private static short toneSample(final int frame) {
    final double angle = (2 * Math.PI * TONE_HERTZ * frame) / SAMPLE_RATE;
    final double sine = Math.sin(angle);
    final double scaled = sine * TONE_AMPLITUDE * Short.MAX_VALUE;
    return (short) Math.round(scaled);
  }

  private static void serveHtml(final HttpExchange exchange, final String elements) throws IOException {
    final String html =
      "<!doctype html><html><head><style>html,body{margin:0;width:100%%;height:100%%;background:#ff0000;}</style></head><body>%s</body></html>".formatted(
        elements
      );
    final byte[] body = html.getBytes(StandardCharsets.UTF_8);
    sendBody(exchange, HTML_CONTENT_TYPE, body);
  }

  private static void serveScript(final HttpExchange exchange, final String script) throws IOException {
    final String html =
      "<!doctype html><html><head><style>html,body{margin:0;width:100%%;height:100%%;background:#ff0000;}</style></head><body><script>window.addEventListener(\"load\", () => setTimeout(() => { %s }, %s));</script></body></html>".formatted(
        script,
        SCRIPT_DELAY_MILLISECONDS
      );
    final byte[] body = html.getBytes(StandardCharsets.UTF_8);
    sendBody(exchange, HTML_CONTENT_TYPE, body);
  }

  private static void serveDownload(final HttpExchange exchange) throws IOException {
    final byte[] body = "not for the server".getBytes(StandardCharsets.UTF_8);
    final Headers headers = exchange.getResponseHeaders();
    headers.add("Content-Disposition", "attachment; filename=\"evil.exe\"");
    sendBody(exchange, DOWNLOAD_CONTENT_TYPE, body);
  }

  private static void sendBody(final HttpExchange exchange, final String contentType, final byte[] body) throws IOException {
    final Headers headers = exchange.getResponseHeaders();
    headers.add(CONTENT_TYPE_HEADER, contentType);
    exchange.sendResponseHeaders(HTTP_OK, body.length);
    try (final OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private void recordEvent(final HttpExchange exchange) throws IOException {
    final URI requestUri = exchange.getRequestURI();
    final String query = requestUri.getRawQuery();
    final Map<String, String> parameters = this.parse(query);
    final PageEvent pageEvent = this.toEvent(parameters);
    synchronized (this.events) {
      this.events.add(pageEvent);
    }

    exchange.sendResponseHeaders(HTTP_NO_CONTENT, NO_RESPONSE_BODY);
    exchange.close();
  }

  private PageEvent toEvent(final Map<String, String> parameters) {
    final String page = parameters.getOrDefault("page", "");
    final String type = parameters.getOrDefault("type", "");
    final String rawX = parameters.getOrDefault("x", "0");
    final String rawY = parameters.getOrDefault("y", "0");
    final String rawButton = parameters.getOrDefault("button", "0");
    final String rawButtons = parameters.getOrDefault("buttons", "0");
    final String key = parameters.getOrDefault("key", "");
    final int pageX = Integer.parseInt(rawX);
    final int pageY = Integer.parseInt(rawY);
    final int button = Integer.parseInt(rawButton);
    final int buttons = Integer.parseInt(rawButtons);
    return new PageEvent(page, type, pageX, pageY, button, buttons, key);
  }

  private Map<String, String> parse(final String query) {
    final Map<String, String> parameters = new HashMap<>();
    if (query == null) {
      return parameters;
    }

    final String[] pairs = PAIR_SEPARATOR.split(query, KEEP_TRAILING_EMPTY_PARTS);
    for (final String pair : pairs) {
      final int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
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

  public List<PageEvent> getEvents() {
    synchronized (this.events) {
      return List.copyOf(this.events);
    }
  }

  public List<PageEvent> getEvents(final String type) {
    final List<PageEvent> all = this.getEvents();
    return all
      .stream()
      .filter(event -> event.getType().equals(type))
      .toList();
  }

  public static final class PageEvent {

    private final String page;
    private final String type;
    private final int pageX;
    private final int pageY;
    private final int button;
    private final int buttons;
    private final String key;

    PageEvent(
      final String page,
      final String type,
      final int pageX,
      final int pageY,
      final int button,
      final int buttons,
      final String key
    ) {
      this.page = page;
      this.type = type;
      this.pageX = pageX;
      this.pageY = pageY;
      this.button = button;
      this.buttons = buttons;
      this.key = key;
    }

    public String getPage() {
      return this.page;
    }

    public String getType() {
      return this.type;
    }

    public int getX() {
      return this.pageX;
    }

    public int getY() {
      return this.pageY;
    }

    public int getButton() {
      return this.button;
    }

    // MouseEvent.buttons: 1 for the left, 2 for the right button
    public int getButtons() {
      return this.buttons;
    }

    public String getKey() {
      return this.key;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final PageEvent pageEvent)) {
        return false;
      }

      return (
        this.page.equals(pageEvent.page) &&
        this.type.equals(pageEvent.type) &&
        this.pageX == pageEvent.pageX &&
        this.pageY == pageEvent.pageY &&
        this.button == pageEvent.button &&
        this.buttons == pageEvent.buttons &&
        this.key.equals(pageEvent.key)
      );
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.page, this.type, this.pageX, this.pageY, this.button, this.buttons, this.key);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
        .add("page", this.page)
        .add("type", this.type)
        .add("pageX", this.pageX)
        .add("pageY", this.pageY)
        .add("button", this.button)
        .add("buttons", this.buttons)
        .add("key", this.key)
        .toString();
    }
  }
}
