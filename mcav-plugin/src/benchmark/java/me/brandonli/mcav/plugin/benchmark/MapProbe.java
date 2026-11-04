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

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.map.DeltaMapEncoder;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherResultStep;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ParallelDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.checkerframework.checker.nullness.qual.Nullable;

final class MapProbe implements DitherResultStep {

  private static final int PROCESSORS_PER_DITHER_THREAD = 2;
  private static final int MINIMUM_PARALLELISM = 1;

  private static final int HEXADECIMAL_RADIX = 16;

  private static final int CHANNEL_MASK = 0xFF;
  private static final int RGB_MASK = 0xFFFFFF;

  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final int FIRST_OPAQUE_COLOR_INDEX = 4;

  private static final long POLL_INTERVAL_MILLISECONDS = 10L;

  private static final double COVERED_FRACTION = 0.9;

  private final MapConfiguration configuration;
  private final AtomicLong frames;
  private final AtomicLong lastPatchNanoseconds;
  private final ForkJoinPool ditherPool;
  private @Nullable DeltaMapEncoder encoder;
  private volatile @Nullable Watch watch;

  MapProbe(final MapConfiguration configuration) {
    this.configuration = configuration;
    this.frames = new AtomicLong();
    this.lastPatchNanoseconds = new AtomicLong(System.nanoTime());
    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    final int parallelism = Math.max(MINIMUM_PARALLELISM, processors / PROCESSORS_PER_DITHER_THREAD - 1);
    this.ditherPool = new ForkJoinPool(parallelism);
  }

  @Override
  public void start() {}

  static byte paletteIndex(final String color) {
    final int rgb = Integer.parseInt(color.substring(1), HEXADECIMAL_RADIX);
    final int red = (rgb >> RED_SHIFT) & CHANNEL_MASK;
    final int green = (rgb >> GREEN_SHIFT) & CHANNEL_MASK;
    final int blue = rgb & CHANNEL_MASK;
    final DitherPalette palette = DitherPalette.DEFAULT_MAP_PALETTE;
    final int[] colors = palette.getPalette();
    for (int index = 0; index < colors.length; index++) {
      final int candidate = colors[index] & RGB_MASK;
      if (candidate == ((red << RED_SHIFT) | (green << GREEN_SHIFT) | blue) && index >= FIRST_OPAQUE_COLOR_INDEX) {
        return (byte) index;
      }
    }
    final String message = "Color %s is not a map palette color!".formatted(color);
    throw new IllegalArgumentException(message);
  }

  @Override
  public synchronized void process(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    if (this.configuration.shouldResize()) {
      final int width = this.configuration.getMapWidthResolution();
      final int height = this.configuration.getMapHeightResolution();
      final ResizeFilter filter = new ResizeFilter(width, height);
      filter.applyFilter(samples);
    }
    final byte[] dithered =
      algorithm instanceof final ParallelDitherAlgorithm parallel
        ? parallel.ditherIntoBytes(samples, this.ditherPool)
        : algorithm.ditherIntoBytes(samples);
    final int width = samples.getWidth();
    final int height = samples.getHeight();
    final DeltaMapEncoder current = this.getEncoder(width, height);
    final List<MapTilePatch> patches = current.encode(dithered);
    final long now = System.nanoTime();
    this.frames.incrementAndGet();
    if (!patches.isEmpty()) {
      this.lastPatchNanoseconds.set(now);
    }
    final Watch activeWatch = this.watch;
    if (activeWatch != null) {
      activeWatch.recordPatches(patches, now);
    }
  }

  @Override
  public void release() {
    this.ditherPool.shutdownNow();
  }

  private DeltaMapEncoder getEncoder(final int width, final int height) {
    final int startMapId = this.configuration.getMap();
    final int columns = this.configuration.getMapBlockWidth();
    final int rows = this.configuration.getMapBlockHeight();
    final DeltaMapEncoder current = this.encoder;
    if (current != null) {
      final MapLayout layout = current.getLayout();
      if (layout.matches(startMapId, columns, rows, width, height)) {
        return current;
      }
    }
    final MapLayout layout = new MapLayout(startMapId, columns, rows, width, height);
    final DeltaMapEncoder created = new DeltaMapEncoder(layout, DeltaMapEncoder.DEFAULT_MAXIMUM_BYTES_PER_FRAME);
    this.encoder = created;

    return created;
  }

  void awaitQuiet(final long quietMilliseconds, final long timeoutMilliseconds) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMilliseconds);
    final long quietNanoseconds = TimeUnit.MILLISECONDS.toNanos(quietMilliseconds);
    while (System.nanoTime() - this.lastPatchNanoseconds.get() < quietNanoseconds && System.nanoTime() < deadline) {
      Thread.sleep(POLL_INTERVAL_MILLISECONDS);
    }
  }

  Watch watch(final byte index) {
    final DeltaMapEncoder current = this.encoder;
    if (current == null) {
      final String message = "No frame was encoded yet!";
      throw new IllegalStateException(message);
    }

    final MapLayout layout = current.getLayout();
    final int firstMapId = layout.getMapId(0);
    final int pagePixels = layout.getImageWidth() * layout.getImageHeight();
    final Watch created = new Watch(index, firstMapId, layout.getMapCount(), pagePixels);
    this.watch = created;

    return created;
  }

  long getFrames() {
    return this.frames.get();
  }

  static final class Watch {

    private final byte index;
    private final int firstMapId;
    private final BitSet covered;
    private final int pagePixels;
    private final CountDownLatch first;
    private final CountDownLatch full;
    private volatile long firstNanoseconds;
    private volatile long fullNanoseconds;

    Watch(final byte index, final int firstMapId, final int mapCount, final int pagePixels) {
      this.index = index;
      this.firstMapId = firstMapId;
      this.pagePixels = pagePixels;
      this.covered = new BitSet(mapCount * MapLayout.MAP_SIZE * MapLayout.MAP_SIZE);
      this.first = new CountDownLatch(1);
      this.full = new CountDownLatch(1);
    }

    private void recordPatches(final List<MapTilePatch> patches, final long now) {
      for (final MapTilePatch patch : patches) {
        this.recordShownPixels(patch, now);
      }
      if (this.firstNanoseconds != 0L && this.fullNanoseconds == 0L && this.covered.cardinality() >= this.pagePixels * COVERED_FRACTION) {
        this.fullNanoseconds = now;
        this.full.countDown();
      }
    }

    private void recordShownPixels(final MapTilePatch patch, final long now) {
      final byte[] colors = patch.getColors();
      final int mapOffset = (patch.getMapId() - this.firstMapId) * MapLayout.MAP_SIZE * MapLayout.MAP_SIZE;
      final int left = patch.getX();
      final int top = patch.getY();
      final int width = patch.getWidth();
      boolean shows = false;
      for (int position = 0; position < colors.length; position++) {
        if (colors[position] == this.index) {
          shows = true;
          final int row = top + position / width;
          final int column = left + (position % width);
          this.covered.set(mapOffset + row * MapLayout.MAP_SIZE + column);
        }
      }
      if (shows && this.firstNanoseconds == 0L) {
        this.firstNanoseconds = now;
        this.first.countDown();
      }
    }

    boolean awaitFirst(final long timeoutMilliseconds) throws InterruptedException {
      return this.first.await(timeoutMilliseconds, TimeUnit.MILLISECONDS);
    }

    boolean awaitFull(final long timeoutMilliseconds) throws InterruptedException {
      return this.full.await(timeoutMilliseconds, TimeUnit.MILLISECONDS);
    }

    long getFirstNanoseconds() {
      return this.firstNanoseconds;
    }

    long getFullNanoseconds() {
      return this.fullNanoseconds;
    }
  }
}
