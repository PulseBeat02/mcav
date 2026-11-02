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
package me.brandonli.mcav.plugin.benchmark;

import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
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
import me.brandonli.mcav.plugin.command.MapDisplaySettings;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
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

  private static final long WARM_UP_MILLISECONDS = 3_000L;
  private static final long FRAME_RATE_MILLISECONDS = 10_000L;
  private static final int LATENCY_ROUNDS = 20;
  private static final long SETTLE_MILLISECONDS = 600L;
  private static final long LATENCY_TIMEOUT_MILLISECONDS = 10_000L;
  private static final long FULL_TIMEOUT_MILLISECONDS = 3_000L;
  private static final long START_TIMEOUT_MILLISECONDS = 120_000L;
  private static final int MAP_ID = 0;
  private static final List<String> LATENCY_COLORS = List.of("#dc0000", "#7fb238", "#c7c7c7", "#f7e9a3");

  private static final int ARGUMENT_COUNT = 2;

  private static final List<WallSize> WALL_SIZES = List.of(
    new WallSize(640, 640, 5, 5),
    new WallSize(1280, 720, 10, 6),
    new WallSize(1920, 1080, 15, 9)
  );

  private static final String ROW_START = "| ";
  private static final String CELL_SEPARATOR = " | ";
  private static final String ROW_END = " |";
  private static final String HEADER_RULE = "|---|---|---|---|";
  private static final String RESULT_SEPARATOR = " / ";

  private static final String JCEF_BACKEND = "jcef";

  private static final String FRAME_RATE_PAGE_NAME = "fps";
  private static final String LATENCY_PAGE_NAME = "latency";

  private static final double NANOSECONDS_PER_SECOND = 1e9;
  private static final double NANOSECONDS_PER_MILLISECOND = 1e6;

  private static final long POLL_INTERVAL_MILLISECONDS = 20L;

  private static final int MEDIAN_DIVISOR = 2;
  private static final double NINETIETH_PERCENTILE = 0.9;

  private BrowserBenchmark() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Runs the benchmark.
   *
   * @param args the backend and the output file
   * @throws Exception if the benchmark fails
   */
  public static void main(final String[] args) throws Exception {
    if (args.length != ARGUMENT_COUNT) {
      throw new IllegalArgumentException("Usage: BrowserBenchmark <backend> <output file>");
    }

    final String backend = args[0];
    final Path output = Path.of(args[1]);
    final Supplier<BrowserPlayer> factory = backends(backend);
    final List<WallSize> sizes = WALL_SIZES;
    final List<String> rows = new ArrayList<>();
    try (final BenchmarkServer server = BenchmarkServer.start()) {
      for (final WallSize size : sizes) {
        final String frameRate = measureFrameRate(factory, server, size);
        final String latency = measureLatency(factory, server, size);
        final String row = ROW_START + backend + CELL_SEPARATOR + size + CELL_SEPARATOR + frameRate + CELL_SEPARATOR + latency + ROW_END;
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
    lines.add(HEADER_RULE);
    lines.addAll(rows);
    Files.write(output, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    final PrintStream out = System.out;
    out.println(String.join(System.lineSeparator(), lines));
    System.exit(0);
  }

  /**
   * Returns the backend with the given name.
   *
   * @param backend the name of the backend
   * @return the factory of its players
   */
  private static Supplier<BrowserPlayer> backends(final String backend) {
    final BrowserOptions local = BrowserOptions.builder().privateNetworks(true).build();
    return switch (backend) {
      case JCEF_BACKEND -> () -> BrowserPlayer.create(local);
      default -> {
        final String message = "Backend %s is unknown!".formatted(backend);
        throw new IllegalArgumentException(message);
      }
    };
  }

  private static String measureFrameRate(final Supplier<BrowserPlayer> factory, final BenchmarkServer server, final WallSize size)
    throws InterruptedException {
    final BrowserPlayer player = factory.get();
    try {
      final MapProbe probe = attach(player, size);
      final URI page = server.page(FRAME_RATE_PAGE_NAME);
      startAndWait(player, source(page, size), probe);
      Thread.sleep(WARM_UP_MILLISECONDS);
      final long framesBefore = probe.getFrames();
      final long start = System.nanoTime();
      Thread.sleep(FRAME_RATE_MILLISECONDS);
      final long framesAfter = probe.getFrames();
      final long elapsed = System.nanoTime() - start;
      final double perSecond = ((framesAfter - framesBefore) * NANOSECONDS_PER_SECOND) / elapsed;
      return String.format(Locale.ROOT, "%.1f", perSecond);
    } finally {
      player.release();
    }
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

  private static String measureLatency(final Supplier<BrowserPlayer> factory, final BenchmarkServer server, final WallSize size)
    throws InterruptedException {
    final BrowserPlayer player = factory.get();
    final List<Double> first = new ArrayList<>();
    final List<Double> full = new ArrayList<>();
    try {
      final MapProbe probe = attach(player, size);
      final URI page = server.page(LATENCY_PAGE_NAME);
      final int subscriptions = server.getSubscriptions();
      startAndWait(player, source(page, size), probe);
      server.awaitSubscription(subscriptions, START_TIMEOUT_MILLISECONDS);
      Thread.sleep(WARM_UP_MILLISECONDS);
      for (int round = 0; round < LATENCY_ROUNDS; round++) {
        final String color = LATENCY_COLORS.get(round % LATENCY_COLORS.size());
        final byte index = MapProbe.paletteIndex(color);
        probe.awaitQuiet(SETTLE_MILLISECONDS, LATENCY_TIMEOUT_MILLISECONDS);
        final MapProbe.Watch watch = probe.watch(index);
        final long sent = System.nanoTime();
        server.send(color);
        recordLatencies(watch, sent, first, full);
      }
    } finally {
      player.release();
    }
    final int missed = LATENCY_ROUNDS - first.size();
    final int incomplete = first.size() - full.size();
    final String missedText = missed == 0 ? "" : " (" + missed + " rounds without any packet)";
    final String incompleteText =
      incomplete == 0 ? "" : " (wall incomplete after " + FULL_TIMEOUT_MILLISECONDS + " ms in " + incomplete + " rounds)";
    return describe(first) + RESULT_SEPARATOR + describe(full) + missedText + incompleteText;
  }

  private static void recordLatencies(final MapProbe.Watch watch, final long sent, final List<Double> first, final List<Double> full)
    throws InterruptedException {
    final boolean shown = watch.awaitFirst(LATENCY_TIMEOUT_MILLISECONDS);
    if (!shown) {
      return;
    }

    first.add((watch.getFirstNanoseconds() - sent) / NANOSECONDS_PER_MILLISECOND);
    final boolean complete = watch.awaitFull(FULL_TIMEOUT_MILLISECONDS);
    if (complete) {
      full.add((watch.getFullNanoseconds() - sent) / NANOSECONDS_PER_MILLISECOND);
    }
  }

  private static void startAndWait(final BrowserPlayer player, final BrowserSource source, final MapProbe probe)
    throws InterruptedException {
    final boolean started = player.start(source);
    if (!started) {
      throw new IllegalStateException("The browser did not start!");
    }

    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(START_TIMEOUT_MILLISECONDS);
    while (probe.getFrames() == 0) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("No frame reached the map encoder!");
      }
      Thread.sleep(POLL_INTERVAL_MILLISECONDS);
    }
  }

  private static String describe(final List<Double> values) {
    if (values.isEmpty()) {
      return "n/a";
    }

    final List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    final double median = sorted.get(sorted.size() / MEDIAN_DIVISOR);
    final int ninetiethPercentileIndex = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * NINETIETH_PERCENTILE) - 1);
    final double ninetiethPercentile = sorted.get(ninetiethPercentileIndex);
    return String.format(Locale.ROOT, "%.0f ms, %.0f ms", median, ninetiethPercentile);
  }

  /**
   * A browser size and the wall of maps it is shown on.
   */
  private static final class WallSize {

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
