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
package me.brandonli.mcav.plugin.command.video;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.bukkit.hologram.Hologram;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.multimedia.cv.AbstractVideoPlayerCv;
import me.brandonli.mcav.media.player.pipeline.filter.audio.VolumeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.PluginException;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.utils.CaptureDevices;
import me.brandonli.mcav.plugin.utils.CleanupUtils;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.ThrowableUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the video that is playing: its player, the filter that shows its frames, and the hologram that shows its
 * title, together with the worker thread that starts videos.
 *
 * <p>Only one video plays at a time. {@link #getStarting()} is true while a video is starting, so a second command
 * cannot start one at the same time. The volume and whether videos loop are settings of the manager, which the
 * videos started later keep.
 *
 * @since 1.0.0
 */
public final class VideoPlayerManager {

  private static final String STARTUP_CANCELLED_MESSAGE = "The video startup was cancelled!";

  private static final Equivalence<Object> IDENTITY = Equivalence.identity();
  private static final Logger LOGGER = LoggerFactory.getLogger(VideoPlayerManager.class);

  private static final long LOOP_CHECK_TICKS = 10L;

  private volatile @Nullable VideoPlayerMultiplexer player;
  private volatile @Nullable FunctionalVideoFilter filter;
  private volatile @Nullable Hologram hologram;
  private volatile @Nullable Location hologramLocation;
  private volatile boolean looping;
  private volatile List<CaptureDevices.Device> devices = List.of();

  private final MCAVSandbox plugin;
  private final MCAVApi api;
  private final AtomicBoolean starting;
  private final ExecutorService service;
  private final AudioProvider provider;
  private final Object lifecycle = new Object();
  private long generation;
  private long startGeneration;
  private boolean closed;
  private @Nullable VideoPlayerMultiplexer startingPlayer;
  private final Set<WorldCleanup> pendingWorldCleanups = new HashSet<>();
  private final VolumeFilter volume = new VolumeFilter();
  private @Nullable BukkitTask loopWatcher;

  /**
   * Creates the manager and its worker thread.
   *
   * @param plugin the plugin, whose library and audio provider must be available
   * @since 1.0.0
   */
  public VideoPlayerManager(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");

    this.starting = new AtomicBoolean(false);
    this.service = Executors.newSingleThreadExecutor();
    this.provider = plugin.getAudioProvider();
    this.api = plugin.getMCAV();
    this.plugin = plugin;
  }

  /**
   * Releases the video and stops the worker thread. Called on the main thread when the plugin is disabled.
   *
   * @since 1.0.0
   */
  public void shutdown() {
    synchronized (this.lifecycle) {
      if (this.closed) {
        return;
      }

      this.closed = true;
    }
    CleanupUtils.runAll(
      () -> this.setLooping(false),
      this::releaseVideoPlayer,
      this::drainWorldCleanup,
      () -> ExecutorUtils.shutdownExecutorGracefully(this.service),
      this::drainWorldCleanup
    );
  }

  /**
   * Sets the hologram that shows the title of the video.
   *
   * @param hologram the hologram, or {@code null} for none
   * @since 1.0.0
   */
  public void setHologram(final @Nullable Hologram hologram) {
    synchronized (this.lifecycle) {
      this.hologram = hologram;
    }
  }

  /**
   * Returns whether the library is still preparing a capability in the background, such as VLC or yt-dlp, which are
   * downloaded on the first start of a server that does not have them.
   *
   * @param capability the capability
   * @return true if the preparation has not finished yet, false if the capability is available or failed
   * @since 1.0.0
   */
  public boolean isPreparing(final Capability capability) {
    Preconditions.checkNotNull(capability, "Capability must not be null!");

    final CompletableFuture<Boolean> ready = this.api.whenCapabilityReady(capability);
    final boolean done = ready.isDone();
    return !done;
  }

  /**
   * Releases the video and its audio output. Every part is forgotten before it is released, so a failure never
   * releases it twice.
   *
   * <p>The player is released on the calling thread, because stopping it may block. The filter and the hologram
   * change the world, so they are released on the main thread: at once when called on the main thread, otherwise
   * the calling thread waits until the main thread has released them. When the plugin is already disabled, the
   * main thread accepts no more tasks; cleanup remains owned until the explicit main-thread shutdown drain.
   * Accepted cleanup tasks are also retained, so shutdown can complete them before waiting for the worker.
   *
   * @throws PluginException if the thread is interrupted while waiting, or releasing on the main thread fails
   * @since 1.0.0
   */
  public void releaseVideoPlayer() {
    this.cancelStart();
    this.clearCurrentVideo();
  }

  /**
   * Invalidates queued resolution, display starts, native completion and notifications immediately.
   *
   * @since 1.0.0
   */
  public void cancelStart() {
    synchronized (this.lifecycle) {
      this.generation++;
    }
  }

  long beginStart() {
    synchronized (this.lifecycle) {
      if (this.closed) {
        final String message = "The video manager is shut down!";
        throw new CancellationException(message);
      }

      this.startGeneration = ++this.generation;

      return this.startGeneration;
    }
  }

  boolean isCurrent(final long expected) {
    synchronized (this.lifecycle) {
      return !this.closed && this.generation == expected;
    }
  }

  void checkStart() {
    synchronized (this.lifecycle) {
      this.checkStartLocked();
    }
  }

  private void checkStartLocked() {
    if (this.closed || this.generation != this.startGeneration) {
      throw new CancellationException(STARTUP_CANCELLED_MESSAGE);
    }
  }

  // Replacing/cleaning a video inside its own startup must not invalidate that startup's generation.
  void clearCurrentVideo() {
    final VideoPlayerMultiplexer oldPlayer;
    final FunctionalVideoFilter oldFilter;
    final Hologram oldHologram;
    final WorldCleanup worldTask;
    synchronized (this.lifecycle) {
      final VideoPlayerMultiplexer current = this.player;
      final boolean workerOwns = IDENTITY.equivalent(current, this.startingPlayer);
      oldPlayer = workerOwns ? null : current;
      oldFilter = this.filter;
      oldHologram = this.hologram;
      this.player = null;
      this.filter = null;
      this.hologram = null;
      worldTask = new WorldCleanup(() ->
        CleanupUtils.runAll(() -> this.releaseOldFilter(oldFilter), () -> this.killOldHologram(oldHologram))
      );
      this.pendingWorldCleanups.add(worldTask);
    }
    CleanupUtils.runAll(
      this.provider::releaseAudioFilter,
      () -> this.releaseOldPlayer(oldPlayer),
      () -> this.runOnMainThreadAndWait(worldTask)
    );
  }

  private void releaseOldFilter(final @Nullable FunctionalVideoFilter oldFilter) {
    if (oldFilter != null) {
      oldFilter.release();
    }
  }

  private void killOldHologram(final @Nullable Hologram oldHologram) {
    if (oldHologram != null) {
      oldHologram.kill();
    }
  }

  private void releaseOldPlayer(final @Nullable VideoPlayerMultiplexer oldPlayer) {
    if (oldPlayer != null) {
      oldPlayer.release();
    }
  }

  /**
   * Takes ownership before scheduling the display. A released display's accepted task cannot start it later.
   * The shared startup claim serializes callers with the worker that resolves and configures the video.
   *
   * @param output the new display filter
   * @since 1.0.0
   */
  public void startFilter(final FunctionalVideoFilter output) {
    final long expected;
    synchronized (this.lifecycle) {
      this.filter = output;
      this.checkStartLocked();
      expected = this.startGeneration;
    }
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    try {
      scheduler.runTask(this.plugin, () -> this.startIfCurrent(expected, output));
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.cleanupAfterFailure(exception, this::clearCurrentVideo);
      throw exception;
    }
  }

  private void startIfCurrent(final long expected, final FunctionalVideoFilter output) {
    final boolean current;
    synchronized (this.lifecycle) {
      current = this.isCurrent(expected) && IDENTITY.equivalent(this.filter, output);
    }
    if (!current) {
      return;
    }

    try {
      output.start();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.cleanupAfterFailure(exception, this::releaseVideoPlayer);
      throw exception;
    }
  }

  // Only the worker executes native start. Release detaches an in-flight player; this worker then closes it.
  boolean startNative(final BooleanSupplier start) {
    final VideoPlayerMultiplexer candidate;
    synchronized (this.lifecycle) {
      this.checkStartLocked();
      candidate = Preconditions.checkNotNull(this.player, "No video player was configured!");
      this.startingPlayer = candidate;
    }
    final boolean started;
    try {
      started = start.getAsBoolean();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.cleanupAfterFailure(exception, () -> this.finishNative(candidate));
      throw exception;
    }
    this.finishNative(candidate);
    this.checkStart();

    return started;
  }

  private void cleanupAfterFailure(final Throwable failure, final Runnable cleanup) {
    try {
      cleanup.run();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      if (!IDENTITY.equivalent(failure, exception)) {
        failure.addSuppressed(exception);
      }
    }
  }

  private void finishNative(final VideoPlayerMultiplexer candidate) {
    final boolean detached;
    synchronized (this.lifecycle) {
      this.startingPlayer = null;
      detached = !IDENTITY.equivalent(this.player, candidate);
    }
    if (detached) {
      candidate.release();
    }
  }

  private void runOnMainThreadAndWait(final WorldCleanup cleanup) {
    final boolean mainThread = Bukkit.isPrimaryThread();
    if (mainThread) {
      this.executeWorldCleanup(cleanup);
      return;
    }

    final CompletableFuture<@Nullable Void> completion = cleanup.completion;
    if (!completion.isDone()) {
      final Future<@Nullable Void> scheduled = this.scheduleOnMainThread(() -> this.executeWorldCleanup(cleanup));
      if (scheduled == null) {
        // Keep ownership for the explicit main-thread shutdown drain, even if scheduling is already disabled.
        return;
      }
    }
    this.awaitMainThread(completion);
  }

  private void executeWorldCleanup(final WorldCleanup cleanup) {
    try {
      cleanup.run();
    } finally {
      synchronized (this.lifecycle) {
        this.pendingWorldCleanups.remove(cleanup);
      }
    }
  }

  private void drainWorldCleanup() {
    final List<WorldCleanup> pending;
    synchronized (this.lifecycle) {
      pending = new ArrayList<>(this.pendingWorldCleanups);
    }
    final Runnable[] actions = new Runnable[pending.size()];
    for (int index = 0; index < actions.length; index++) {
      final WorldCleanup cleanup = pending.get(index);
      actions[index] = () -> this.executeWorldCleanup(cleanup);
    }
    CleanupUtils.runAll(actions);
  }

  // null when the plugin is disabled and the task was not scheduled
  private @Nullable Future<@Nullable Void> scheduleOnMainThread(final Runnable task) {
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    final Callable<@Nullable Void> call = Executors.callable(task, null);
    try {
      return scheduler.callSyncMethod(this.plugin, call);
    } catch (final IllegalPluginAccessException exception) {
      LOGGER.debug("The plugin is disabled; display and hologram cleanup is retained for the main-thread shutdown drain.", exception);
      return null;
    }
  }

  private void awaitMainThread(final Future<@Nullable Void> result) {
    try {
      result.get();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      final String message = "Interrupted while releasing the video on the main thread!";
      throw new PluginException(message, exception);
    } catch (final ExecutionException exception) {
      final Throwable cause = requireNonNull(exception.getCause()); // checker framework
      if (cause instanceof final VirtualMachineError virtualMachineError) {
        throw virtualMachineError;
      }

      final String message = "Failed to release the video on the main thread!";
      throw new PluginException(message, cause);
    }
  }

  /**
   * Sets the player of the video.
   *
   * @param player the player, or {@code null} for none
   * @since 1.0.0
   */
  public void setPlayer(final @Nullable VideoPlayerMultiplexer player) {
    synchronized (this.lifecycle) {
      if (player == null) {
        this.player = null;
        return;
      }

      final boolean starting = this.starting.get();
      if (!this.closed && (!starting || this.generation == this.startGeneration)) {
        this.player = player;
        return;
      }
    }
    final CancellationException failure = new CancellationException(STARTUP_CANCELLED_MESSAGE);
    this.cleanupAfterFailure(failure, player::release);
    throw failure;
  }

  /**
   * Sets the filter that shows the frames of the video.
   *
   * @param filter the filter, or {@code null} for none
   * @since 1.0.0
   */
  public void setFilter(final @Nullable FunctionalVideoFilter filter) {
    synchronized (this.lifecycle) {
      this.filter = filter;
    }
  }

  /**
   * Makes the videos play again from their start whenever they end, or stop at their end. A video is checked for its
   * end twice a second, off the main thread. Only the players that decode with FFmpeg or OpenCV restart a video; a
   * live stream or a camera has no end to restart from.
   *
   * @param looping true to loop
   * @since 1.0.0
   */
  public synchronized void setLooping(final boolean looping) {
    this.looping = looping;
    final BukkitTask watcher = this.loopWatcher;
    if (looping && watcher == null) {
      this.loopWatcher = Bukkit.getScheduler().runTaskTimerAsynchronously(
        this.plugin,
        this::restartEnded,
        LOOP_CHECK_TICKS,
        LOOP_CHECK_TICKS
      );
    } else if (!looping && watcher != null) {
      watcher.cancel();
      this.loopWatcher = null;
    }
  }

  // isPlaying() stays true while paused, so a paused video is left alone
  void restartEnded() {
    final VideoPlayerMultiplexer current = this.player;
    if (this.looping && current instanceof final AbstractVideoPlayerCv cvPlayer && !cvPlayer.isPlaying()) {
      cvPlayer.seek(0L);
    }
  }

  /**
   * Returns the hologram that shows the title of the video.
   *
   * @return the hologram, or empty if there is none
   * @since 1.0.0
   */
  public Optional<Hologram> getHologram() {
    return Optional.ofNullable(this.hologram);
  }

  /**
   * Sets where the hologram of the next video appears.
   *
   * @param location the location, or {@code null} to show no hologram
   * @since 1.0.0
   */
  public void setHologramLocation(final @Nullable Location location) {
    this.hologramLocation = location;
  }

  /**
   * Returns where the hologram of the next video appears.
   *
   * @return the location, or empty if no hologram is shown
   * @since 1.0.0
   */
  public Optional<Location> getHologramLocation() {
    return Optional.ofNullable(this.hologramLocation);
  }

  /**
   * Returns whether the library found VLC. While VLC is still being prepared in the background, it is not supported
   * yet; {@link #isPreparing(Capability)} tells the two cases apart.
   *
   * @return true if VLC players can be created
   * @since 1.0.0
   */
  public boolean isVlcSupported() {
    return this.api.hasCapability(Capability.VLC);
  }

  /**
   * Returns the worker thread that starts and releases videos.
   *
   * @return the executor
   * @since 1.0.0
   */
  public ExecutorService getService() {
    return this.service;
  }

  /**
   * Returns the flag that is true while a video is starting.
   *
   * @return the flag, shared by every video command
   * @since 1.0.0
   */
  public AtomicBoolean getStarting() {
    return this.starting;
  }

  /**
   * Returns the player of the video.
   *
   * @return the player, or empty if no video plays
   * @since 1.0.0
   */
  public Optional<VideoPlayerMultiplexer> getPlayer() {
    return Optional.ofNullable(this.player);
  }

  /**
   * Returns the filter that shows the frames of the video.
   *
   * @return the filter, or empty if no video plays
   * @since 1.0.0
   */
  public Optional<FunctionalVideoFilter> getFilter() {
    return Optional.ofNullable(this.filter);
  }

  /**
   * Returns the volume every video plays at, which the audio pipeline of every video applies before its output.
   *
   * @return the volume filter, shared by the videos
   * @since 1.0.0
   */
  public VolumeFilter getVolume() {
    return this.volume;
  }

  /**
   * Returns whether the videos loop.
   *
   * @return true if a video plays again from its start when it ends
   * @since 1.0.0
   */
  public boolean isLooping() {
    return this.looping;
  }

  /**
   * Remembers the capture devices {@code /mcav video devices} listed last, the only ones the DEVICE player may open.
   *
   * @param listed the devices
   * @since 1.0.0
   */
  public void setDevices(final List<CaptureDevices.Device> listed) {
    this.devices = List.copyOf(listed);
  }

  /**
   * Returns the capture devices listed last.
   *
   * @return the devices, none before the first listing
   * @since 1.0.0
   */
  public List<CaptureDevices.Device> getDevices() {
    return this.devices;
  }

  private static final class WorldCleanup implements Runnable {

    private final Runnable task;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();

    private WorldCleanup(final Runnable task) {
      this.task = task;
    }

    @Override
    public void run() {
      if (!this.claimed.compareAndSet(false, true)) {
        return;
      }

      try {
        this.task.run();
        this.completion.complete(null);
      } catch (final RuntimeException | Error exception) {
        this.completion.completeExceptionally(exception);
        throw exception;
      }
    }
  }
}
