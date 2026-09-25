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

/**
 * The end of the benchmark pipeline: does what {@code CompressedMapResult} does with a frame (resize to the wall,
 * dither, encode the changes into map patches with {@link DeltaMapEncoder}) and stops where the patches would be
 * turned into map packets and sent. It counts the frames and watches the patches for a color.
 */
final class MapProbe implements DitherResultStep {

  private final MapConfiguration configuration;
  private final AtomicLong frames;
  private final AtomicLong lastPatchNanos;
  private final ForkJoinPool ditherPool;
  private @Nullable DeltaMapEncoder encoder;
  private volatile @Nullable Watch watch;

  MapProbe(final MapConfiguration configuration) {
    this.configuration = configuration;
    this.frames = new AtomicLong();
    this.lastPatchNanos = new AtomicLong(System.nanoTime());
    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    // the parallelism of CompressedMapResult's dither pool
    final int parallelism = Math.max(1, processors / 2 - 1);
    this.ditherPool = new ForkJoinPool(parallelism);
  }

  /**
   * Gets the index of a map palette color, given as {@code #rrggbb}.
   *
   * @param color the color
   * @return the palette index
   */
  static byte paletteIndex(final String color) {
    final int rgb = Integer.parseInt(color.substring(1), 16);
    final int red = (rgb >> 16) & 0xFF;
    final int green = (rgb >> 8) & 0xFF;
    final int blue = rgb & 0xFF;
    final DitherPalette palette = DitherPalette.DEFAULT_MAP_PALETTE;
    final int[] colors = palette.getPalette();
    for (int index = 0; index < colors.length; index++) {
      final int candidate = colors[index] & 0xFFFFFF;
      if (candidate == ((red << 16) | (green << 8) | blue) && index >= 4) {
        return (byte) index;
      }
    }
    throw new IllegalArgumentException("Not a map palette color: " + color);
  }

  @Override
  public synchronized void process(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    if (this.configuration.shouldResize()) {
      final int width = this.configuration.getMapWidthResolution();
      final int height = this.configuration.getMapHeightResolution();
      final ResizeFilter filter = new ResizeFilter(width, height);
      filter.applyFilter(samples);
    }
    final byte[] dithered = algorithm instanceof final ParallelDitherAlgorithm parallel
      ? parallel.ditherIntoBytes(samples, this.ditherPool)
      : algorithm.ditherIntoBytes(samples);
    final int width = samples.getWidth();
    final int height = samples.getHeight();
    final DeltaMapEncoder current = this.getEncoder(width, height);
    final List<MapTilePatch> patches = current.encode(dithered);
    final long now = System.nanoTime();
    this.frames.incrementAndGet();
    if (!patches.isEmpty()) {
      this.lastPatchNanos.set(now);
    }
    final Watch activeWatch = this.watch;
    if (activeWatch != null) {
      activeWatch.inspect(patches, now);
    }
  }

  @Override
  public void start() {
    // nothing to prepare: the patches are inspected, not sent
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
    final DeltaMapEncoder created = new DeltaMapEncoder(layout, DeltaMapEncoder.DEFAULT_MAX_BYTES_PER_FRAME);
    this.encoder = created;
    return created;
  }

  long getFrames() {
    return this.frames.get();
  }

  /**
   * Waits until no patch was produced for a while, so the next change starts from a settled wall.
   *
   * @param quietMillis   how long no patch may have been produced
   * @param timeoutMillis how long to wait at most
   * @throws InterruptedException if interrupted
   */
  void awaitQuiet(final long quietMillis, final long timeoutMillis) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    final long quietNanos = TimeUnit.MILLISECONDS.toNanos(quietMillis);
    while (System.nanoTime() - this.lastPatchNanos.get() < quietNanos && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
  }

  /**
   * Starts watching the patches for a color.
   *
   * @param index the palette index of the color
   * @return the watch
   */
  Watch watch(final byte index) {
    final DeltaMapEncoder current = this.encoder;
    if (current == null) {
      throw new IllegalStateException("No frame was encoded yet");
    }
    final MapLayout layout = current.getLayout();
    final int firstMapId = layout.getMapId(0);
    final int pagePixels = layout.getImageWidth() * layout.getImageHeight();
    final Watch created = new Watch(index, firstMapId, layout.getMapCount(), pagePixels);
    this.watch = created;
    return created;
  }

  /**
   * Watches the patches for a color: when the first patch shows it, and when every pixel of the wall has shown it.
   */
  static final class Watch {

    private final byte index;
    private final int firstMapId;
    private final BitSet covered;
    private final int pixels;
    private final CountDownLatch first;
    private final CountDownLatch done;
    private volatile long firstNanos;
    private volatile long fullNanos;

    Watch(final byte index, final int firstMapId, final int mapCount, final int pagePixels) {
      this.index = index;
      this.firstMapId = firstMapId;
      this.pixels = pagePixels;
      this.covered = new BitSet(mapCount * MapLayout.MAP_SIZE * MapLayout.MAP_SIZE);
      this.first = new CountDownLatch(1);
      this.done = new CountDownLatch(1);
    }

    void inspect(final List<MapTilePatch> patches, final long now) {
      for (final MapTilePatch patch : patches) {
        final byte[] colors = patch.getColors();
        final int mapOffset = (patch.getMapId() - this.firstMapId) * MapLayout.MAP_SIZE * MapLayout.MAP_SIZE;
        final int x = patch.getX();
        final int y = patch.getY();
        final int width = patch.getWidth();
        boolean shows = false;
        for (int position = 0; position < colors.length; position++) {
          if (colors[position] == this.index) {
            shows = true;
            final int row = y + position / width;
            final int column = x + (position % width);
            this.covered.set(mapOffset + row * MapLayout.MAP_SIZE + column);
          }
        }
        if (shows && this.firstNanos == 0L) {
          this.firstNanos = now;
          this.first.countDown();
        }
      }
      // the whole page is on the wall once 90% of its pixels showed the color; the rest may be dithered to a neighbor
      // color when a backend delivers the color slightly off, as a JPEG does
      if (this.firstNanos != 0L && this.fullNanos == 0L && this.covered.cardinality() >= this.pixels * 0.9) {
        this.fullNanos = now;
        this.done.countDown();
      }
    }

    boolean awaitFirst(final long timeoutMillis) throws InterruptedException {
      return this.first.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    boolean awaitFull(final long timeoutMillis) throws InterruptedException {
      return this.done.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    long getFirstNanos() {
      return this.firstNanos;
    }

    long getFullNanos() {
      return this.fullNanos;
    }
  }
}
