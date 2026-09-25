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

import com.sun.net.httpserver.HttpServer;
import java.awt.GraphicsEnvironment;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;

/**
 * The proof that the browser runs on a stock headless Linux server: no X server, no Xvfb, nothing installed, and no
 * option of the JVM. The proof bundle runs it with {@code java -jar} in stock server images as a user without
 * privileges; it does what {@link NoFlagsServerMain} does, with a page it serves itself: install MCAV with the browser
 * module, stream a red page with black text at 320x240, check the center pixel, release MCAV, check that no browser
 * process is left, and all of it twice. It prints {@value #PASSED} last, or why it failed.
 *
 * <p>{@code MCAV_PROOF_CACHE} names a writable folder kept between runs, which becomes the home folder of mcav's
 * cache, as the user of a proof run may have none.
 */
public final class HeadlessProofMain {

  /**
   * The line printed when every check passed.
   */
  static final String PASSED = "HEADLESS PROOF PASSED";

  // GraalVM's JDK starts every JVM with these options for its compiler; they are not options of the server
  private static final List<String> GRAALVM_DEFAULTS = List.of(
    "-XX:ThreadPriorityPolicy=1",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+EnableJVMCIProduct",
    "-XX:+EnableJVMCI",
    "-XX:-UnlockExperimentalVMOptions"
  );
  private static final long START_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);
  private static final long STOP_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);
  private static final String PAGE =
    "<!doctype html><html><body style=\"margin:0;background:#ff0000\">" +
    "<p style=\"margin:8px;font:bold 40px sans-serif;color:#000000\">mcav headless</p></body></html>";

  private HeadlessProofMain() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Runs the proof and exits with 0 if it passed, 1 otherwise.
   *
   * @param args ignored
   */
  public static void main(final String[] args) {
    int status = 1;
    try {
      run();
      System.out.println(PASSED);
      status = 0;
    } catch (final Exception | Error failure) {
      failure.printStackTrace(System.out);
      System.out.println("HEADLESS PROOF FAILED: " + failure);
    }
    System.out.flush();
    System.exit(status);
  }

  private static void run() throws Exception {
    final String cache = System.getenv("MCAV_PROOF_CACHE");
    System.out.println("user.home=" + System.getProperty("user.home") + " MCAV_PROOF_CACHE=" + cache);
    if (cache != null) {
      System.setProperty("user.home", Path.of(cache, "home").toString());
    }
    final List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
    System.out.println("JVM arguments: " + arguments + ", Java " + Runtime.version() + ", " + System.getProperty("os.arch"));
    final List<String> options = arguments.stream().filter(argument -> !GRAALVM_DEFAULTS.contains(argument)).toList();
    check(options.isEmpty(), "the JVM runs with no option at all");
    check(GraphicsEnvironment.isHeadless(), "AWT is headless");
    check(System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null, "there is no display");
    final List<String> xServers = findXServers();
    System.out.println("X servers running: " + xServers);
    // a dry run on a development machine may see the X servers of others, which the browser does not use
    final boolean local = "1".equals(System.getenv("MCAV_PROOF_LOCAL"));
    check(xServers.isEmpty() || local, "no X server runs");
    final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 0), 0);
    final byte[] page = PAGE.getBytes(StandardCharsets.UTF_8);
    server.createContext("/", exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, page.length);
      try (final OutputStream body = exchange.getResponseBody()) {
        body.write(page);
      }
    });
    server.start();
    try {
      final URI address = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
      for (int round = 1; round <= 2; round++) {
        runRound(round, address);
      }
    } finally {
      server.stop(0);
    }
  }

  private static void runRound(final int round, final URI address) throws InterruptedException {
    final long start = System.nanoTime();
    final MCAVApi api = MCAV.api();
    api.install(BrowserModule.class);
    // the page is served on this machine, which the browser reaches only with private networks allowed
    final BrowserPlayer player = BrowserPlayer.create(BrowserOptions.builder().privateNetworks(true).build());
    final AtomicReference<int[]> last = new AtomicReference<>();
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then((image, metadata) -> {
      final int width = image.getWidth();
      final int height = image.getHeight();
      final int[] pixels = image.getPixels();
      int dark = 0;
      for (final int pixel : pixels) {
        final boolean black = ((pixel >> 16) & 0xFF) < 80 && ((pixel >> 8) & 0xFF) < 80 && (pixel & 0xFF) < 80;
        dark += black ? 1 : 0;
      }
      last.set(new int[] { width, height, pixels[(height / 2) * width + width / 2] & 0xFFFFFF, dark });
      return false;
    });
    final VideoPipelineStep pipeline = builder.build();
    player.getVideoAttachableCallback().attach(pipeline);
    check(player.start(BrowserSource.uri(address, 320, 240, 1)), "the browser starts");
    await("a red frame", START_TIMEOUT_MILLIS, () -> {
      final int[] frame = last.get();
      return frame != null && isRed(frame[2]);
    });
    final int[] frame = last.get();
    final long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
    System.out.printf(
      Locale.ROOT,
      "round %d: a %dx%d frame after %d s, center pixel %06x, %d dark pixels of text%n",
      round,
      frame[0],
      frame[1],
      seconds,
      frame[2],
      frame[3]
    );
    check(frame[0] == 320 && frame[1] == 240, "the frame is 320x240");
    check(countBrowserProcesses() > 0, "the browser runs in processes of its own");
    if (round == 1) {
      player.release();
    }
    api.release();
    check(HelperProcesses.count() == 0, "no browser session is left");
    await("every browser process to end", STOP_TIMEOUT_MILLIS, () -> countBrowserProcesses() == 0);
    System.out.printf(Locale.ROOT, "round %d: disabled, no browser process left%n", round);
  }

  private static boolean isRed(final int rgb) {
    return ((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) < 60 && (rgb & 0xFF) < 60;
  }

  private static List<String> findXServers() {
    try (final Stream<ProcessHandle> all = ProcessHandle.allProcesses()) {
      return all
        .map(process -> process.info().command().orElse(""))
        .filter(command -> {
          final Path name = Path.of(command).getFileName();
          final String program = name == null ? "" : name.toString();
          return program.equals("Xvfb") || program.equals("Xorg") || program.equals("Xwayland") || program.equals("X");
        })
        .toList();
    }
  }

  private static long countBrowserProcesses() {
    try (final Stream<ProcessHandle> descendants = ProcessHandle.current().descendants()) {
      return descendants.filter(ProcessHandle::isAlive).count();
    }
  }

  private static void await(final String description, final long timeoutMillis, final BooleanSupplier condition)
    throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - deadline > 0) {
        throw new AssertionError("Timed out waiting for " + description);
      }
      Thread.sleep(50L);
    }
  }

  private static void check(final boolean condition, final String description) {
    if (!condition) {
      throw new AssertionError("Failed: " + description);
    }
  }
}
