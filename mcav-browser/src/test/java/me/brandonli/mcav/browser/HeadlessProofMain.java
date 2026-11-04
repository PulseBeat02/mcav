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
package me.brandonli.mcav.browser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;

// The proof that the browser runs on a stock headless Linux server, run with java -jar by the proof bundle. It prints
// PASSED last, or why it failed. MCAV_PROOF_CACHE names a writable folder for the cache, as the user may have no home.
public final class HeadlessProofMain {

  private static final String PROOF_CACHE_VARIABLE = "MCAV_PROOF_CACHE";
  private static final String PROOF_LOCAL_VARIABLE = "MCAV_PROOF_LOCAL";
  private static final String PROOF_LOCAL_ENABLED = "1";
  private static final String DISPLAY_VARIABLE = "DISPLAY";
  private static final String WAYLAND_DISPLAY_VARIABLE = "WAYLAND_DISPLAY";

  private static final String USER_HOME_PROPERTY = "user.home";
  private static final String ARCHITECTURE_PROPERTY = "os.arch";

  private static final String HOME_DIRECTORY = "home";
  private static final String PAGE_PATH = "/";

  private static final String LOOPBACK_HOST = "127.0.0.1";

  private static final int FAILURE_EXIT_CODE = 1;

  private static final int ROUNDS = 2;

  private static final int WIDTH = 320;
  private static final int HEIGHT = 240;

  private static final int FRAME_INTERVAL = 1;

  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final int CHANNEL_MASK = 0xFF;
  private static final int RGB_MASK = 0xFFFFFF;

  private static final int DARK_CHANNEL_MAXIMUM = 80;
  private static final int RED_CHANNEL_MINIMUM = 200;
  private static final int OTHER_CHANNEL_MAXIMUM = 60;

  private static final Set<String> X_SERVER_PROGRAMS = Set.of("Xvfb", "Xorg", "Xwayland", "X");

  private static final String CONTENT_TYPE_HEADER = "Content-Type";

  private static final String HTML_CONTENT_TYPE = "text/html; charset=utf-8";

  static final String PASSED = "HEADLESS PROOF PASSED";

  private static final List<String> GRAALVM_DEFAULTS = List.of(
    "-XX:ThreadPriorityPolicy=1",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+EnableJVMCIProduct",
    "-XX:+EnableJVMCI",
    "-XX:-UnlockExperimentalVMOptions"
  );

  private static final long START_TIMEOUT_MILLISECONDS = TimeUnit.MINUTES.toMillis(10);
  private static final long STOP_TIMEOUT_MILLISECONDS = TimeUnit.SECONDS.toMillis(30);
  private static final long POLL_MILLISECONDS = 50L;

  private static final String PAGE =
    "<!doctype html><html><body style=\"margin:0;background:#ff0000\"><p style=\"margin:8px;font:bold 40px sans-serif;color:#000000\">mcav headless</p></body></html>";

  private HeadlessProofMain() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static void main(final String[] args) {
    int status = FAILURE_EXIT_CODE;
    try {
      run();
      System.out.println(PASSED);
      status = 0;
    } catch (final Exception | Error exception) {
      exception.printStackTrace(System.out);
      System.out.println("HEADLESS PROOF FAILED: %s".formatted(exception));
    }

    System.out.flush();
    System.exit(status);
  }

  private static void run() throws Exception {
    final String cache = System.getenv(PROOF_CACHE_VARIABLE);
    final String home = System.getProperty(USER_HOME_PROPERTY);
    System.out.println("user.home=%s MCAV_PROOF_CACHE=%s".formatted(home, cache));

    if (cache != null) {
      System.setProperty(USER_HOME_PROPERTY, Path.of(cache, HOME_DIRECTORY).toString());
    }

    final List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
    final Runtime.Version version = Runtime.version();
    final String architecture = System.getProperty(ARCHITECTURE_PROPERTY);
    System.out.println("JVM arguments: %s, Java %s, %s".formatted(arguments, version, architecture));
    final List<String> options = arguments
      .stream()
      .filter(argument -> !GRAALVM_DEFAULTS.contains(argument))
      .toList();
    check(options.isEmpty(), "the JVM runs with no option at all");
    check(GraphicsEnvironment.isHeadless(), "AWT is headless");
    check(System.getenv(DISPLAY_VARIABLE) == null && System.getenv(WAYLAND_DISPLAY_VARIABLE) == null, "there is no display");
    final List<String> xServers = findXServers();
    System.out.println("X servers running: %s".formatted(xServers));
    final boolean local = PROOF_LOCAL_ENABLED.equals(System.getenv(PROOF_LOCAL_VARIABLE));
    check(xServers.isEmpty() || local, "no X server runs");
    final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    final byte[] page = PAGE.getBytes(StandardCharsets.UTF_8);
    server.createContext(PAGE_PATH, exchange -> servePage(exchange, page));
    server.start();
    try {
      final int port = server.getAddress().getPort();
      final URI address = URI.create("http://%s:%s/".formatted(LOOPBACK_HOST, port));
      for (int round = 1; round <= ROUNDS; round++) {
        runRound(round, address);
      }
    } finally {
      server.stop(0);
    }
  }

  private static void servePage(final HttpExchange exchange, final byte[] page) throws IOException {
    exchange.getResponseHeaders().add(CONTENT_TYPE_HEADER, HTML_CONTENT_TYPE);
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, page.length);
    try (final OutputStream body = exchange.getResponseBody()) {
      body.write(page);
    }
  }

  private static void runRound(final int round, final URI address) throws InterruptedException {
    final long start = System.nanoTime();
    final MCAVApi api = MCAV.api();
    api.install(BrowserModule.class);
    final BrowserPlayer player = BrowserPlayer.create(BrowserOptions.builder().privateNetworks(true).build());
    final AtomicReference<FrameSummary> last = new AtomicReference<>();
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then((image, _) -> recordFrame(last, image));
    final VideoPipelineStep pipeline = builder.build();
    player.getVideoAttachableCallback().attach(pipeline);
    check(player.start(BrowserSource.uri(address, WIDTH, HEIGHT, FRAME_INTERVAL)), "the browser starts");
    await("a red frame", START_TIMEOUT_MILLISECONDS, () -> showsRed(last));
    final FrameSummary frame = last.get();
    final long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
    System.out.printf(
      Locale.ROOT,
      "round %d: a %dx%d frame after %d s, center pixel %06x, %d dark pixels of text%n",
      round,
      frame.width(),
      frame.height(),
      seconds,
      frame.center(),
      frame.darkPixels()
    );
    check(frame.width() == WIDTH && frame.height() == HEIGHT, "the frame is 320x240");
    check(countBrowserProcesses() > 0, "the browser runs in processes of its own");

    if (round == 1) {
      player.release();
    }

    api.release();
    check(HelperProcesses.count() == 0, "no browser session is left");
    await("every browser process to end", STOP_TIMEOUT_MILLISECONDS, () -> countBrowserProcesses() == 0);
    System.out.printf(Locale.ROOT, "round %d: disabled, no browser process left%n", round);
  }

  private static boolean recordFrame(final AtomicReference<FrameSummary> last, final ImageBuffer image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    int dark = 0;
    for (final int pixel : pixels) {
      final boolean black =
        ((pixel >> RED_SHIFT) & CHANNEL_MASK) < DARK_CHANNEL_MAXIMUM &&
        ((pixel >> GREEN_SHIFT) & CHANNEL_MASK) < DARK_CHANNEL_MAXIMUM &&
        (pixel & CHANNEL_MASK) < DARK_CHANNEL_MAXIMUM;
      dark += black ? 1 : 0;
    }

    final int center = pixels[(height / 2) * width + width / 2] & RGB_MASK;
    last.set(new FrameSummary(width, height, center, dark));

    return false;
  }

  private static boolean showsRed(final AtomicReference<FrameSummary> last) {
    final FrameSummary frame = last.get();
    return frame != null && isRed(frame.center());
  }

  private static boolean isRed(final int rgb) {
    return (
      ((rgb >> RED_SHIFT) & CHANNEL_MASK) > RED_CHANNEL_MINIMUM &&
      ((rgb >> GREEN_SHIFT) & CHANNEL_MASK) < OTHER_CHANNEL_MAXIMUM &&
      (rgb & CHANNEL_MASK) < OTHER_CHANNEL_MAXIMUM
    );
  }

  private static List<String> findXServers() {
    try (final Stream<ProcessHandle> all = ProcessHandle.allProcesses()) {
      return all
        .map(process -> process.info().command().orElse(""))
        .filter(HeadlessProofMain::isXServer)
        .toList();
    }
  }

  private static boolean isXServer(final String command) {
    final Path name = Path.of(command).getFileName();
    final String program = name == null ? "" : name.toString();
    return X_SERVER_PROGRAMS.contains(program);
  }

  private static long countBrowserProcesses() {
    try (final Stream<ProcessHandle> descendants = ProcessHandle.current().descendants()) {
      return descendants.filter(ProcessHandle::isAlive).count();
    }
  }

  private static void await(final String description, final long timeoutMilliseconds, final BooleanSupplier condition)
    throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMilliseconds);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - deadline > 0) {
        final String message = "Waiting for %s timed out!".formatted(description);
        throw new AssertionError(message);
      }
      Thread.sleep(POLL_MILLISECONDS);
    }
  }

  private static void check(final boolean holds, final String description) {
    if (!holds) {
      final String message = "The check that %s failed!".formatted(description);
      throw new AssertionError(message);
    }
  }

  private record FrameSummary(int width, int height, int center, int darkPixels) {}
}
