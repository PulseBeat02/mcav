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

import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import me.brandonli.mcav.browser.BrowserOptions;
import me.brandonli.mcav.browser.BrowserPlayer;
import me.brandonli.mcav.browser.BrowserSource;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.command.MapDisplaySettings;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;

/**
 * Measures how fast a browser backend brings a web page onto a wall of maps, through the same pipeline the sandbox's
 * {@code /mcav browser create} builds: the page is streamed by the backend, dithered with {@code FILTER_LITE} and
 * encoded into map patches by the delta encoder that {@code CompressedMapResult} uses.
 *
 * <p>Two scenarios run for every size. <b>Frame rate</b>: a page that changes its color on every animation frame is
 * streamed for a fixed time, and the frames that reach the map encoder are counted. <b>Latency</b>: a page with one
 * solid color waits for server-sent events from this program; each event names a new palette color, and the time from
 * sending the event to the first map patch that shows the new color is measured, together with the time until 90% of
 * the pixels of the wall show it (the encoder spreads a full-screen change over several frames when it exceeds its
 * byte budget). Server-sent events reach every backend the same way, so the trigger costs the same for all of them.
 *
 * <p>Arguments: {@code <backend> <output file>}, where the backend is a name {@code backends} accepts. The results are
 * printed as Markdown and appended to the output file.
 */
public final class BrowserBenchmark {

  private static final long WARM_UP_MILLIS = 3_000L;
  private static final long FRAME_RATE_MILLIS = 10_000L;
  private static final int LATENCY_ROUNDS = 20;
  private static final long SETTLE_MILLIS = 600L;
  private static final long LATENCY_TIMEOUT_MILLIS = 10_000L;
  private static final long FULL_TIMEOUT_MILLIS = 3_000L;
  private static final long START_TIMEOUT_MILLIS = 120_000L;
  private static final int MAP_ID = 0;
  // exact colors of the map palette, so dithering maps every pixel of a solid page to one index
  private static final List<String> LATENCY_COLORS = List.of("#dc0000", "#7fb238", "#c7c7c7", "#f7e9a3");

  private BrowserBenchmark() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Runs the benchmark.
   *
   * @param args the backend and the output file
   * @throws Exception if the benchmark fails
   */
  public static void main(final String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Usage: BrowserBenchmark <backend> <output file>");
    }
    final String backend = args[0];
    final Path output = Path.of(args[1]);
    final Supplier<BrowserPlayer> factory = backends(backend);
    final List<WallSize> sizes = List.of(new WallSize(640, 640, 5, 5), new WallSize(1280, 720, 10, 6), new WallSize(1920, 1080, 15, 9));
    final List<String> rows = new ArrayList<>();
    try (final BenchmarkServer server = BenchmarkServer.start()) {
      for (final WallSize size : sizes) {
        final String frameRate = measureFrameRate(factory, server, size);
        final String latency = measureLatency(factory, server, size);
        final String row = "| " + backend + " | " + size + " | " + frameRate + " | " + latency + " |";
        System.out.println(row);
        rows.add(row);
      }
    }
    final String header =
      "| backend | browser size on wall | frames/s to the map encoder | latency to the first map patch / to 90% of the page on the wall (median, p90; " +
      LATENCY_ROUNDS +
      " rounds) |";
    final List<String> lines = new ArrayList<>();
    lines.add(header);
    lines.add("|---|---|---|---|");
    lines.addAll(rows);
    Files.write(output, lines, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    final PrintStream out = System.out;
    out.println(String.join(System.lineSeparator(), lines));
    System.exit(0);
  }

  /**
   * Gets the backend with the given name.
   *
   * @param backend the name of the backend
   * @return the factory of its players
   */
  static Supplier<BrowserPlayer> backends(final String backend) {
    // the benchmark pages are served on this machine, which the browser reaches only with private networks allowed
    final BrowserOptions local = BrowserOptions.builder().privateNetworks(true).build();
    return switch (backend) {
      case "jcef" -> () -> BrowserPlayer.create(local);
      default -> throw new IllegalArgumentException("Unknown backend " + backend);
    };
  }

  private static BrowserSource source(final URI page, final WallSize size) {
    return BrowserSource.uri(page, size.width(), size.height(), 1);
  }

  private static MapProbe attach(final BrowserPlayer player, final WallSize size) {
    final MapConfiguration configuration = MapDisplaySettings.createConfiguration(
      Pair.pair(size.columns(), size.rows()),
      Pair.pair(size.width(), size.height()),
      MAP_ID,
      Collections.emptyList()
    );
    final DitherAlgorithm algorithm = DitheringArgument.FILTER_LITE.createAlgorithm();
    final MapProbe probe = new MapProbe(configuration);
    final FunctionalVideoFilter filter = DitherFilter.dither(algorithm, probe);
    filter.start();
    final VideoPipelineStep pipeline = VideoPipelineStep.of(filter);
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(pipeline);
    return probe;
  }

  private static String measureFrameRate(final Supplier<BrowserPlayer> factory, final BenchmarkServer server, final WallSize size)
    throws InterruptedException {
    final BrowserPlayer player = factory.get();
    try {
      final MapProbe probe = attach(player, size);
      final URI page = server.page("fps");
      startAndWait(player, source(page, size), probe);
      Thread.sleep(WARM_UP_MILLIS);
      final long framesBefore = probe.getFrames();
      final long start = System.nanoTime();
      Thread.sleep(FRAME_RATE_MILLIS);
      final long framesAfter = probe.getFrames();
      final long elapsed = System.nanoTime() - start;
      final double perSecond = ((framesAfter - framesBefore) * 1e9) / elapsed;
      return String.format(Locale.ROOT, "%.1f", perSecond);
    } finally {
      player.release();
    }
  }

  private static String measureLatency(final Supplier<BrowserPlayer> factory, final BenchmarkServer server, final WallSize size)
    throws InterruptedException {
    final BrowserPlayer player = factory.get();
    final List<Double> first = new ArrayList<>();
    final List<Double> full = new ArrayList<>();
    int missed = 0;
    int incomplete = 0;
    try {
      final MapProbe probe = attach(player, size);
      final URI page = server.page("latency");
      final int subscriptions = server.getSubscriptions();
      startAndWait(player, source(page, size), probe);
      server.awaitSubscription(subscriptions, START_TIMEOUT_MILLIS);
      Thread.sleep(WARM_UP_MILLIS);
      for (int round = 0; round < LATENCY_ROUNDS; round++) {
        final String color = LATENCY_COLORS.get(round % LATENCY_COLORS.size());
        final byte index = MapProbe.paletteIndex(color);
        probe.awaitQuiet(SETTLE_MILLIS, LATENCY_TIMEOUT_MILLIS);
        final MapProbe.Watch watch = probe.watch(index);
        final long sent = System.nanoTime();
        server.send(color);
        final boolean shown = watch.awaitFirst(LATENCY_TIMEOUT_MILLIS);
        if (!shown) {
          missed++;
          continue;
        }
        first.add((watch.getFirstNanos() - sent) / 1e6);
        final boolean complete = watch.awaitFull(FULL_TIMEOUT_MILLIS);
        if (complete) {
          full.add((watch.getFullNanos() - sent) / 1e6);
        } else {
          incomplete++;
        }
      }
    } finally {
      player.release();
    }
    final String missedText = missed == 0 ? "" : " (" + missed + " rounds without any packet)";
    final String incompleteText = incomplete == 0
      ? ""
      : " (wall incomplete after " + FULL_TIMEOUT_MILLIS + " ms in " + incomplete + " rounds)";
    return describe(first) + " / " + describe(full) + missedText + incompleteText;
  }

  private static void startAndWait(final BrowserPlayer player, final BrowserSource source, final MapProbe probe)
    throws InterruptedException {
    final boolean started = player.start(source);
    if (!started) {
      throw new IllegalStateException("The browser did not start");
    }
    final long deadline = System.nanoTime() + START_TIMEOUT_MILLIS * 1_000_000L;
    while (probe.getFrames() == 0) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("No frame reached the map encoder");
      }
      Thread.sleep(20L);
    }
  }

  private static String describe(final List<Double> values) {
    if (values.isEmpty()) {
      return "n/a";
    }
    final List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    final double median = sorted.get(sorted.size() / 2);
    final int p90Index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.9) - 1);
    final double p90 = sorted.get(p90Index);
    return String.format(Locale.ROOT, "%.0f ms, %.0f ms", median, p90);
  }

  /**
   * A browser size and the wall of maps it is shown on.
   */
  static final class WallSize {

    private final int width;
    private final int height;
    private final int columns;
    private final int rows;

    /**
     * Constructs a size.
     *
     * @param width   the width of the browser in pixels
     * @param height  the height of the browser in pixels
     * @param columns the width of the wall in maps
     * @param rows    the height of the wall in maps
     */
    WallSize(final int width, final int height, final int columns, final int rows) {
      this.width = width;
      this.height = height;
      this.columns = columns;
      this.rows = rows;
    }

    int width() {
      return this.width;
    }

    int height() {
      return this.height;
    }

    int columns() {
      return this.columns;
    }

    int rows() {
      return this.rows;
    }

    @Override
    public String toString() {
      return this.width + "x" + this.height + " on " + this.columns + "x" + this.rows;
    }
  }
}
