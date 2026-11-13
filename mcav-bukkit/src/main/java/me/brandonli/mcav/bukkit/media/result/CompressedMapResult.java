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
import java.util.HashMap;
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
import me.brandonli.mcav.bukkit.BukkitModule;
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
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Displays video on a grid of maps while sending as little data as possible.
 *
 * <p>{@link MapResult} sends every map in every frame, which costs 16 KB per map per frame, or roughly 12 MB/s
 * for a 5x5 wall at 30 frames per second. This result only sends the parts of the maps that actually changed,
 * and it caps the average amount of data sent per frame: a frame is sent whole when the budget saved by the frames
 * before it covers it and skipped otherwise, so a scene cut does not flood the connections of the viewers and they
 * never see parts of two frames. See {@link DeltaMapEncoder} for how the changes are found.
 *
 * <p>Players that start viewing, for example because they joined the server, first receive the current picture and
 * then follow the updates like every other viewer. After {@link #start()}, a player who joins receives it at once,
 * even while no frames arrive, such as while the video is paused; otherwise on the next frame. A client can drop map
 * data that arrives while it is still joining, so a player who joins receives the picture again three seconds later,
 * unless a frame arrived meanwhile.
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
 *
 * <p>The caller owns this result and its lifecycle; attaching it to a pipeline does not call start or release.
 * Input buffers remain caller-owned and are consumed synchronously, even when converted display data is applied
 * on a later server tick. Serialize frame delivery with lifecycle operations unless this class explicitly provides
 * locking, and never mutate an input buffer concurrently with conversion.
 *
 * <p>This class is thread-safe: processing, start and release are serialized by an internal lock. The result owns any
 * dithering pool it creates, but does not own the supplied algorithm. Release waits for the current process call to
 * leave the lock; start permits reuse, with a new encoder created on the next frame. The picture for a player who
 * joins is sent from an asynchronous Bukkit task under the same lock, so the server thread never waits for a frame.
 *
 * {@snippet :
 * final CompressedMapResult result = new CompressedMapResult(configuration);
 * final DitherAlgorithm algorithm = DitherAlgorithm.temporalFloydSteinberg();
 * final FunctionalVideoFilter filter = DitherFilter.dither(algorithm, result);
 * filter.start();
 *
 * final VideoPipelineStep pipeline = VideoPipelineStep.of(filter);
 * final VideoAttachableCallback video = player.getVideoAttachableCallback();
 * video.attach(pipeline);
 *
 * // when playback ends
 * filter.release();
 * }
 *
 * @since 1.0.0
 */
public final class CompressedMapResult implements DitherResultStep {

  private static final Logger LOGGER = LoggerFactory.getLogger(CompressedMapResult.class);

  private static final int MINIMUM_PARALLELISM = 1;

  private static final int PROCESSORS_PER_DITHER_THREAD = 2;

  private static final String DITHER_THREAD_NAME_PREFIX = "mcav-map-dither-";

  // A client can drop map data that arrives while it is still joining.
  private static final long JOIN_RESEND_TICKS = 60L;

  private final MapConfiguration configuration;
  private final int maximumBytesPerFrame;
  private final Map<UUID, Object> activeViewers;
  private final Map<Integer, BitSet> sentPixels;
  private final Lock lock;

  private @Nullable DeltaMapEncoder encoder;
  private @Nullable ForkJoinPool ditherPool;
  private @Nullable Listener joinListener;
  private boolean released;
  private long processedFrames;

  /**
   * Creates a result with an ordinary-update budget of
   * {@link DeltaMapEncoder#DEFAULT_MAXIMUM_BYTES_PER_FRAME} bytes per frame on average.
   *
   * @param configuration the configuration describing the map grid and the viewers
   * @throws NullPointerException if the configuration is null
   * @since 1.0.0
   */
  public CompressedMapResult(final MapConfiguration configuration) {
    this(configuration, DeltaMapEncoder.DEFAULT_MAXIMUM_BYTES_PER_FRAME);
  }

  /**
   * Creates a result with the given budget for ordinary delta updates.
   *
   * <p>The byte budget is an average per frame for ordinary delta updates, including estimated patch overhead. A frame
   * is sent whole when the budget saved by the frames before it covers it, and skipped otherwise. Resize cleanup and
   * new-viewer snapshots are additional. Lower values show fewer frames per second while much of the picture changes.
   *
   * @param configuration        the configuration describing the map grid and the viewers
   * @param maximumBytesPerFrame the average ordinary-update budget per frame, including estimated patch overhead
   * @throws IllegalArgumentException if the budget is not positive
   * @throws NullPointerException     if {@code configuration} is null
   * @since 1.0.0
   */
  public CompressedMapResult(final MapConfiguration configuration, final int maximumBytesPerFrame) {
    Preconditions.checkNotNull(configuration, "Map configuration must not be null!");
    Preconditions.checkArgument(maximumBytesPerFrame > 0, "Byte budget %s must be positive!", maximumBytesPerFrame);

    this.configuration = configuration;
    this.maximumBytesPerFrame = maximumBytesPerFrame;
    this.activeViewers = new HashMap<>();
    this.sentPixels = new TreeMap<>();
    this.lock = new ReentrantLock();
  }

  /**
   * Accepts frames again and listens for players who join, to send them the current picture. The encoder itself is
   * created lazily when the first frame arrives, because the frame size is unknown until then. Starting undoes a
   * previous {@link #release()}, so the same result can show a second video, the way every other result of this
   * module can.
   *
   * @throws IllegalStateException if no plugin has been injected
   * @since 1.0.0
   */
  @Override
  public void start() {
    this.lock.lock();
    try {
      this.listenForJoins();
      this.released = false;
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Stops processing frames and listening for joins, shuts down the dithering threads, and clears the maps of every
   * viewer. Frames that arrive after this method was called are ignored.
   *
   * @since 1.0.0
   */
  @Override
  public void release() {
    this.lock.lock();
    try {
      if (this.released) {
        return;
      }

      this.released = true;
      this.stopListeningForJoins();
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

  private void listenForJoins() {
    if (this.joinListener != null) {
      return;
    }

    final Plugin plugin = BukkitModule.getPlugin();
    final Listener listener = new Listener() {};
    final EventExecutor executor = (_, _) -> this.sendToNewViewersLater(plugin);
    final PluginManager pluginManager = Bukkit.getPluginManager();
    pluginManager.registerEvent(PlayerJoinEvent.class, listener, EventPriority.MONITOR, executor, plugin);
    this.joinListener = listener;
  }

  private void sendToNewViewersLater(final Plugin plugin) {
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduler.runTaskAsynchronously(plugin, () -> this.sendToNewViewers(plugin));
  }

  private void sendToNewViewers(final Plugin plugin) {
    this.lock.lock();
    try {
      final DeltaMapEncoder currentEncoder = this.encoder;
      if (currentEncoder == null) {
        return;
      }

      final List<UUID> joined = this.sendToViewers(currentEncoder, List.of());
      this.sendSnapshotAgainLater(plugin, joined);
    } finally {
      this.lock.unlock();
    }
  }

  private void sendSnapshotAgainLater(final Plugin plugin, final List<UUID> joined) {
    final long frame = this.processedFrames;
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduler.runTaskLaterAsynchronously(plugin, () -> this.sendSnapshotAgain(joined, frame), JOIN_RESEND_TICKS);
  }

  private void sendSnapshotAgain(final List<UUID> joined, final long frame) {
    this.lock.lock();
    try {
      final DeltaMapEncoder currentEncoder = this.encoder;
      if (currentEncoder == null || this.processedFrames > frame) {
        return;
      }

      final Collection<UUID> viewers = this.configuration.getViewers();
      final List<UUID> watching = joined.stream().filter(viewers::contains).toList();
      if (!watching.isEmpty()) {
        this.sendSnapshot(currentEncoder, watching);
      }
    } finally {
      this.lock.unlock();
    }
  }

  private void stopListeningForJoins() {
    final Listener listener = this.joinListener;
    if (listener == null) {
      return;
    }

    HandlerList.unregisterAll(listener);
    this.joinListener = null;
  }

  private void clearMaps() {
    final Collection<UUID> viewers = this.configuration.getViewers();
    final int startMapId = this.configuration.getMap();
    final int columns = this.configuration.getMapBlockWidth();
    final int rows = this.configuration.getMapBlockHeight();
    final int mapCount = columns * rows;
    MapPacketFactory.clear(viewers, startMapId, mapCount);
  }

  /**
   * Resizes the frame if the configuration asks for it, dithers it, and sends only the changed parts of the maps
   * to the viewers, within the byte budget. Viewers that just started watching receive the complete picture
   * first. Frames that arrive after {@link #release()} are ignored.
   *
   * @param samples   the frame, which is resized in place if resizing is configured
   * @param algorithm the dithering algorithm; a {@link ParallelDitherAlgorithm} runs on dedicated daemon threads
   * @throws NullPointerException     if {@code samples} or {@code algorithm} is null
   * @throws IllegalArgumentException if {@code algorithm} returns more or fewer colors than the frame has pixels
   * @since 1.0.0
   */
  @Override
  public void process(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    Preconditions.checkNotNull(samples, "Samples must not be null!");
    Preconditions.checkNotNull(algorithm, "Dither algorithm must not be null!");

    this.lock.lock();
    try {
      if (this.released) {
        return;
      }

      this.ditherAndSendFrame(samples, algorithm);
    } finally {
      this.lock.unlock();
    }
  }

  private void ditherAndSendFrame(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    this.resizeIfConfigured(samples);
    final byte[] dithered = this.dither(samples, algorithm);

    final int width = samples.getWidth();
    final int height = samples.getHeight();
    final DeltaMapEncoder currentEncoder = this.createEncoderIfLayoutChanged(width, height);
    final List<MapTilePatch> patches = currentEncoder.encode(dithered);
    this.sendToViewers(currentEncoder, patches);
    this.processedFrames++;
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

  private List<UUID> sendToViewers(final DeltaMapEncoder currentEncoder, final List<MapTilePatch> patches) {
    final List<UUID> existingViewers = new ArrayList<>();
    final List<UUID> newViewers = new ArrayList<>();
    this.sortConnectedViewers(existingViewers, newViewers);

    if (!existingViewers.isEmpty()) {
      MapPacketFactory.send(existingViewers, patches);
      this.rememberCoverage(patches);
    }

    if (!newViewers.isEmpty()) {
      this.sendSnapshot(currentEncoder, newViewers);
    }

    return newViewers;
  }

  private void sendSnapshot(final DeltaMapEncoder currentEncoder, final List<UUID> viewers) {
    /*
     * A layout change forgets the viewer baseline, so old viewers also receive this reset. Keep historical cleanup
     * available for viewers absent during earlier resizes, including when the snapshot is empty.
     */
    final List<MapTilePatch> currentSnapshot = currentEncoder.snapshot();
    final List<MapTilePatch> snapshot = this.createHistoryClears(currentSnapshot);
    snapshot.addAll(currentSnapshot);
    MapPacketFactory.send(viewers, snapshot);
    this.rememberCoverage(currentSnapshot);
  }

  private void rememberCoverage(final List<MapTilePatch> patches) {
    for (final MapTilePatch patch : patches) {
      final int mapId = patch.getMapId();
      final BitSet pixels = this.sentPixels.computeIfAbsent(mapId, _ -> new BitSet(MapLayout.MAP_SIZE * MapLayout.MAP_SIZE));
      this.setCoverage(pixels, patch, Coverage.COVERED);
    }
  }

  // the exact union, not a bounding box, which would erase pixels this result never drew
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
        this.setCoverage(pixels, patch, Coverage.UNCOVERED);
      }
    }

    final List<MapTilePatch> clears = new ArrayList<>();
    for (final Map.Entry<Integer, BitSet> entry : uncovered.entrySet()) {
      final int mapId = entry.getKey();
      final BitSet pixels = entry.getValue();
      this.appendClearRectangles(mapId, pixels, clears);
    }

    return clears;
  }

  private void setCoverage(final BitSet pixels, final MapTilePatch patch, final Coverage coverage) {
    final int left = patch.getX();
    final int top = patch.getY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();
    for (int row = 0; row < height; row++) {
      final int start = (top + row) * MapLayout.MAP_SIZE + left;
      pixels.set(start, start + width, coverage == Coverage.COVERED);
    }
  }

  private void appendClearRectangles(final int mapId, final BitSet pixels, final List<MapTilePatch> clears) {
    final int size = MapLayout.MAP_SIZE;
    int first = pixels.nextSetBit(0);
    while (first >= 0) {
      final int left = first % size;
      final int top = first / size;
      final int nextClear = pixels.nextClearBit(first);
      final int right = Math.min(nextClear, (top + 1) * size);
      final int width = right - first;
      final int height = this.clearRunHeight(pixels, left, top, width);
      final byte[] transparent = new byte[width * height];
      final MapTilePatch clear = MapTilePatch.of(mapId, left, top, width, height, transparent);
      clears.add(clear);
      this.setCoverage(pixels, clear, Coverage.UNCOVERED);
      first = pixels.nextSetBit(0);
    }
  }

  private int clearRunHeight(final BitSet pixels, final int left, final int top, final int width) {
    final int size = MapLayout.MAP_SIZE;
    int height = 1;
    while (top + height < size) {
      final int nextRowStart = (top + height) * size + left;
      final int nextRowClear = pixels.nextClearBit(nextRowStart);
      if (nextRowClear < nextRowStart + width) {
        break;
      }
      height++;
    }

    return height;
  }

  private void sortConnectedViewers(final List<UUID> existingViewers, final List<UUID> newViewers) {
    final Collection<UUID> viewers = this.configuration.getViewers();
    final Set<UUID> connectedViewers = new HashSet<>();
    for (final UUID viewer : viewers) {
      final @Nullable Object connection = PacketUtils.findConnectionIdentity(viewer).orElse(null);
      if (connection == null) {
        continue;
      }
      connectedViewers.add(viewer);
      final Object previous = this.activeViewers.put(viewer, connection);
      final boolean alreadyWatching = connection.equals(previous);
      final List<UUID> group = alreadyWatching ? existingViewers : newViewers;
      group.add(viewer);
    }

    final Set<UUID> activeViewerIds = this.activeViewers.keySet();
    activeViewerIds.retainAll(connectedViewers);
  }

  private DeltaMapEncoder createEncoderIfLayoutChanged(final int width, final int height) {
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

    final MapLayout layout = MapLayout.of(startMapId, columns, rows, width, height);
    final DeltaMapEncoder createdEncoder = new DeltaMapEncoder(layout, this.maximumBytesPerFrame);
    this.encoder = createdEncoder;
    this.activeViewers.clear();

    return createdEncoder;
  }

  private byte[] dither(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    if (algorithm instanceof final ParallelDitherAlgorithm parallelAlgorithm) {
      ForkJoinPool pool = this.ditherPool;
      if (pool == null) {
        pool = this.createDitherPool();
        this.ditherPool = pool;
      }

      return parallelAlgorithm.ditherIntoBytes(samples, pool);
    }

    return algorithm.ditherIntoBytes(samples);
  }

  private ForkJoinPool createDitherPool() {
    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    final int parallelism = Math.max(MINIMUM_PARALLELISM, processors / PROCESSORS_PER_DITHER_THREAD - 1);
    final ForkJoinPool.ForkJoinWorkerThreadFactory threadFactory = this::createDitherThread;
    final Thread.UncaughtExceptionHandler exceptionHandler = this::logUncaughtException;
    return new ForkJoinPool(parallelism, threadFactory, exceptionHandler, false);
  }

  private void logUncaughtException(final Thread thread, final Throwable failure) {
    final String threadName = thread.getName();
    LOGGER.error("Map dithering thread {} failed with an uncaught exception.", threadName, failure);
  }

  private ForkJoinWorkerThread createDitherThread(final ForkJoinPool pool) {
    final ForkJoinPool.ForkJoinWorkerThreadFactory defaultFactory = ForkJoinPool.defaultForkJoinWorkerThreadFactory;
    final ForkJoinWorkerThread thread = defaultFactory.newThread(pool);
    final int poolIndex = thread.getPoolIndex();
    thread.setDaemon(true);
    thread.setName(DITHER_THREAD_NAME_PREFIX + poolIndex);
    return thread;
  }

  /**
   * Requests a complete snapshot for every connected viewer on the next processed frame. Repeated requests before
   * that frame coalesce. The encoder's delta history and the coverage used for resize cleanup are retained.
   * This request sends no packets itself, shares the processing lock, and does not resume a released result.
   * The snapshot is additional to the ordinary delta byte budget.
   *
   * @since 1.0.0
   */
  public void refresh() {
    this.lock.lock();
    try {
      this.activeViewers.clear();
    } finally {
      this.lock.unlock();
    }
  }

  private enum Coverage {
    COVERED,
    UNCOVERED,
  }
}
