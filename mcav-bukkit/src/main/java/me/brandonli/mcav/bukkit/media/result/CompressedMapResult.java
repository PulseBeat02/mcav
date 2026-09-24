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
package me.brandonli.mcav.bukkit.media.result;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.map.DeltaMapEncoder;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherResultStep;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ParallelDitherAlgorithm;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Displays video on a grid of maps while sending as little data as possible.
 *
 * <p>{@link MapResult} sends every map in every frame, which costs 16 KB per map per frame, or roughly 12 MB/s
 * for a 5x5 wall at 30 frames per second. This result only sends the parts of the maps that actually changed,
 * and it caps the amount of data sent per frame, so a scene cut is spread over a few frames instead of flooding
 * the connections of the viewers. See {@link DeltaMapEncoder} for how the changes are found.
 *
 * <p>Players that start viewing while the video is playing, for example because they joined the server, first
 * receive the current picture once and then follow the updates like every other viewer.
 *
 * <p>When the frame size changes or a viewer starts watching again, pixels previously sent by this result but
 * outside the current snapshot are cleared. Cleanup leaves pixels never sent by this result untouched. Coverage history
 * uses at most one bit per map pixel, regardless of how often the frame size changes, and is discarded on release.
 * This cleanup and new-viewer snapshots are additional to the normal delta byte budget. Clears and the replacement
 * snapshot share a bundle when their combined patch count fits the limit described by {@link MapPacketFactory}.
 *
 * <p>The savings depend heavily on the dithering algorithm. Error diffusion algorithms spread tiny changes across
 * the whole picture, so almost every pixel changes in every frame. A temporally stable algorithm, such as
 * {@link DitherAlgorithm#temporalFloydSteinberg()}, keeps unchanged areas identical between frames and gives by
 * far the best results.
 */
public class CompressedMapResult implements DitherResultStep {

  private static final Logger LOGGER = LoggerFactory.getLogger(CompressedMapResult.class);

  private final MapConfiguration configuration;
  private final int maxBytesPerFrame;
  private final Set<UUID> activeViewers;
  private final Map<Integer, BitSet> sentPixels;
  private final Lock lock;

  private @Nullable DeltaMapEncoder encoder;
  private @Nullable ForkJoinPool ditherPool;
  private boolean released;

  /**
   * Constructs a new {@code CompressedMapResult} with an ordinary-update budget of
   * {@link DeltaMapEncoder#DEFAULT_MAX_BYTES_PER_FRAME} bytes per frame.
   *
   * @param configuration the configuration describing the map grid and the viewers
   */
  public CompressedMapResult(final MapConfiguration configuration) {
    this(configuration, DeltaMapEncoder.DEFAULT_MAX_BYTES_PER_FRAME);
  }

  /**
   * Constructs a new {@code CompressedMapResult}.
   *
   * @param configuration    the configuration describing the map grid and the viewers
   * @param maxBytesPerFrame the budget for ordinary delta updates, including estimated patch overhead. The most
   *                         urgent map is always sent even if it exceeds the budget. Resize cleanup and new-viewer
   *                         snapshots are additional. Lower values spread motion and scene cuts over more frames
   * @throws IllegalArgumentException if the budget is not positive
   */
  public CompressedMapResult(final MapConfiguration configuration, final int maxBytesPerFrame) {
    Preconditions.checkNotNull(configuration, "Map configuration must not be null");
    Preconditions.checkArgument(maxBytesPerFrame > 0, "Byte budget must be positive");
    this.configuration = configuration;
    this.maxBytesPerFrame = maxBytesPerFrame;
    this.activeViewers = new HashSet<>();
    this.sentPixels = new TreeMap<>();
    this.lock = new ReentrantLock();
  }

  /**
   * Resizes the frame if the configuration asks for it, dithers it, and sends only the changed parts of the maps
   * to the viewers, within the byte budget. Viewers that just started watching receive the complete picture
   * first. Frames that arrive after {@link #release()} are ignored.
   *
   * @param samples   the frame, which is resized in place if resizing is configured
   * @param algorithm the dithering algorithm; a {@link ParallelDitherAlgorithm} runs on dedicated daemon threads
   */
  @Override
  public void process(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    Preconditions.checkNotNull(samples, "Samples must not be null");
    Preconditions.checkNotNull(algorithm, "Dither algorithm must not be null");
    this.lock.lock();
    try {
      if (this.released) {
        return;
      }
      this.processFrame(samples, algorithm);
    } finally {
      this.lock.unlock();
    }
  }

  private void processFrame(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    this.resizeIfConfigured(samples);
    final byte[] dithered = this.dither(samples, algorithm);

    final int width = samples.getWidth();
    final int height = samples.getHeight();
    final DeltaMapEncoder currentEncoder = this.getEncoder(width, height);
    final List<MapTilePatch> patches = currentEncoder.encode(dithered);
    this.sendToViewers(currentEncoder, patches);
  }

  private void resizeIfConfigured(final ImageBuffer samples) {
    final boolean shouldResize = this.configuration.shouldResize();
    if (!shouldResize) {
      return;
    }
    final int width = this.configuration.getMapWidthResolution();
    final int height = this.configuration.getMapHeightResolution();
    final ResizeFilter filter = new ResizeFilter(width, height);
    filter.applyFilter(samples);
  }

  private void sendToViewers(final DeltaMapEncoder currentEncoder, final List<MapTilePatch> patches) {
    final List<UUID> existingViewers = new ArrayList<>();
    final List<UUID> newViewers = new ArrayList<>();
    this.sortConnectedViewers(existingViewers, newViewers);
    if (!existingViewers.isEmpty()) {
      MapPacketFactory.send(existingViewers, patches);
      this.rememberCoverage(patches);
    }
    if (!newViewers.isEmpty()) {
      // A layout change forgets the viewer baseline, so old viewers also receive this reset. Keep historical
      // cleanup available for viewers absent during earlier resizes, including when the snapshot is empty.
      final List<MapTilePatch> currentSnapshot = currentEncoder.snapshot();
      final List<MapTilePatch> snapshot = this.createHistoryClears(currentSnapshot);
      snapshot.addAll(currentSnapshot);
      MapPacketFactory.send(newViewers, snapshot);
      this.rememberCoverage(currentSnapshot);
    }
  }

  private void rememberCoverage(final List<MapTilePatch> patches) {
    for (final MapTilePatch patch : patches) {
      final int mapId = patch.getMapId();
      final BitSet pixels = this.sentPixels.computeIfAbsent(mapId, _ -> new BitSet(MapLayout.MAP_SIZE * MapLayout.MAP_SIZE));
      setCoverage(pixels, patch, true);
    }
  }

  /**
   * Subtracts the current snapshot from the exact union of previously sent pixels. A bounding rectangle would
   * erase pixels never owned by the result when, for example, a wide image is followed by a tall image.
   */
  private List<MapTilePatch> createHistoryClears(final List<MapTilePatch> snapshot) {
    final Map<Integer, BitSet> uncovered = new TreeMap<>();
    for (final Map.Entry<Integer, BitSet> entry : this.sentPixels.entrySet()) {
      final int mapId = entry.getKey();
      final BitSet pixels = entry.getValue();
      final BitSet copy = (BitSet) pixels.clone();
      uncovered.put(mapId, copy);
    }
    for (final MapTilePatch patch : snapshot) {
      final int mapId = patch.getMapId();
      final BitSet pixels = uncovered.get(mapId);
      if (pixels != null) {
        setCoverage(pixels, patch, false);
      }
    }
    final List<MapTilePatch> clears = new ArrayList<>();
    for (final Map.Entry<Integer, BitSet> entry : uncovered.entrySet()) {
      final int mapId = entry.getKey();
      final BitSet pixels = entry.getValue();
      appendClearRectangles(mapId, pixels, clears);
    }
    return clears;
  }

  private static void setCoverage(final BitSet pixels, final MapTilePatch patch, final boolean covered) {
    final int x = patch.getX();
    final int y = patch.getY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();
    for (int row = 0; row < height; row++) {
      final int start = (y + row) * MapLayout.MAP_SIZE + x;
      pixels.set(start, start + width, covered);
    }
  }

  /**
   * Consumes a coverage bitmap as disjoint transparent rectangles. Each horizontal run is extended down through
   * rows that contain it, keeping solid regions compact without including a pixel outside the recorded coverage.
   */
  private static void appendClearRectangles(final int mapId, final BitSet pixels, final List<MapTilePatch> clears) {
    final int size = MapLayout.MAP_SIZE;
    int first = pixels.nextSetBit(0);
    while (first >= 0) {
      final int x = first % size;
      final int y = first / size;
      final int nextClear = pixels.nextClearBit(first);
      final int right = Math.min(nextClear, (y + 1) * size);
      final int width = right - first;
      int height = 1;
      while (y + height < size) {
        final int nextRowStart = (y + height) * size + x;
        final int nextRowClear = pixels.nextClearBit(nextRowStart);
        if (nextRowClear < nextRowStart + width) {
          break;
        }
        height++;
      }
      final byte[] transparent = new byte[width * height];
      final MapTilePatch clear = new MapTilePatch(mapId, x, y, width, height, transparent);
      clears.add(clear);
      setCoverage(pixels, clear, false);
      first = pixels.nextSetBit(0);
    }
  }

  /**
   * Sorts the connected viewers into those who already watch, and receive only the changes, and those who just
   * started watching, and need the complete picture first. Viewers who disconnected are forgotten.
   */
  private void sortConnectedViewers(final List<UUID> existingViewers, final List<UUID> newViewers) {
    final Collection<UUID> viewers = this.configuration.getViewers();
    final Set<UUID> connectedViewers = new HashSet<>();
    for (final UUID viewer : viewers) {
      final boolean connected = PacketUtils.isConnected(viewer);
      if (!connected) {
        continue;
      }
      connectedViewers.add(viewer);
      final boolean alreadyWatching = this.activeViewers.contains(viewer);
      final List<UUID> group = alreadyWatching ? existingViewers : newViewers;
      group.add(viewer);
    }
    this.activeViewers.retainAll(connectedViewers);
    this.activeViewers.addAll(newViewers);
  }

  private DeltaMapEncoder getEncoder(final int width, final int height) {
    final int startMapId = this.configuration.getMap();
    final int columns = this.configuration.getMapBlockWidth();
    final int rows = this.configuration.getMapBlockHeight();
    final DeltaMapEncoder currentEncoder = this.encoder;
    if (currentEncoder != null) {
      final MapLayout currentLayout = currentEncoder.getLayout();
      final boolean layoutUnchanged = currentLayout.matches(startMapId, columns, rows, width, height);
      if (layoutUnchanged) {
        return currentEncoder;
      }
    }
    final MapLayout layout = new MapLayout(startMapId, columns, rows, width, height);
    final DeltaMapEncoder createdEncoder = new DeltaMapEncoder(layout, this.maxBytesPerFrame);
    this.encoder = createdEncoder;
    this.activeViewers.clear();
    return createdEncoder;
  }

  private byte[] dither(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    if (algorithm instanceof final ParallelDitherAlgorithm parallelAlgorithm) {
      ForkJoinPool pool = this.ditherPool;
      if (pool == null) {
        pool = createDitherPool();
        this.ditherPool = pool;
      }
      return parallelAlgorithm.ditherIntoBytes(samples, pool);
    }
    return algorithm.ditherIntoBytes(samples);
  }

  // leaves half of the processors to the server, so the main thread and the network threads can keep up
  private static ForkJoinPool createDitherPool() {
    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    final int parallelism = Math.max(1, processors / 2 - 1);
    final ForkJoinPool.ForkJoinWorkerThreadFactory threadFactory = CompressedMapResult::createDitherThread;
    final Thread.UncaughtExceptionHandler exceptionHandler = CompressedMapResult::logUncaughtException;
    return new ForkJoinPool(parallelism, threadFactory, exceptionHandler, false);
  }

  // exceptions of dither tasks are rethrown to the caller by the pool; anything else that escapes a worker is logged
  private static void logUncaughtException(final Thread thread, final Throwable throwable) {
    final String threadName = thread.getName();
    LOGGER.error("Uncaught exception in map dithering thread {}", threadName, throwable);
  }

  private static ForkJoinWorkerThread createDitherThread(final ForkJoinPool pool) {
    final ForkJoinPool.ForkJoinWorkerThreadFactory defaultFactory = ForkJoinPool.defaultForkJoinWorkerThreadFactory;
    final ForkJoinWorkerThread thread = defaultFactory.newThread(pool);
    final int poolIndex = thread.getPoolIndex();
    thread.setDaemon(true);
    thread.setName("mcav-map-dither-" + poolIndex);
    return thread;
  }

  /**
   * Accepts frames again. The encoder itself is created lazily when the first frame arrives, because the frame size
   * is unknown until then; this only undoes a previous {@link #release()}, so the same result can show a second
   * video, the way every other result of this module can.
   */
  @Override
  public void start() {
    this.lock.lock();
    try {
      // release() leaves the encoder and the viewers cleared, so there is nothing to undo but the latch itself;
      // without this, process() returned early forever and a reused result rendered nothing at all
      this.released = false;
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Stops processing frames, shuts down the dithering threads, and clears the maps of every viewer. Frames that
   * arrive after this method was called are ignored.
   */
  @Override
  public void release() {
    this.lock.lock();
    try {
      if (this.released) {
        return;
      }
      this.released = true;
      this.encoder = null;
      this.activeViewers.clear();
      this.sentPixels.clear();

      final ForkJoinPool pool = this.ditherPool;
      if (pool != null) {
        pool.shutdownNow();
        this.ditherPool = null;
      }
      // Clearing is part of the same transition: a restarted frame must be sent after this clear.
      this.clearMaps();
    } finally {
      this.lock.unlock();
    }
  }

  private void clearMaps() {
    final Collection<UUID> viewers = this.configuration.getViewers();
    final int startMapId = this.configuration.getMap();
    final int columns = this.configuration.getMapBlockWidth();
    final int rows = this.configuration.getMapBlockHeight();
    final int mapCount = columns * rows;
    MapPacketFactory.clear(viewers, startMapId, mapCount);
  }
}
