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
package me.brandonli.mcav.sandbox.command.video;

import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.utils.CleanupUtils;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.ThrowableUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the video that is playing: its player, the filter that shows its frames, and the hologram that shows its
 * title, together with the worker thread that starts videos.
 *
 * <p>Only one video plays at a time. {@link #getStatus()} is true while a video is starting, so a second command
 * cannot start one at the same time.
 */
public final class VideoPlayerManager {

  private static final Equivalence<Object> IDENTITY = Equivalence.identity();
  private static final Logger LOGGER = LoggerFactory.getLogger(VideoPlayerManager.class);

  private volatile @Nullable VideoPlayerMultiplexer player;
  private volatile @Nullable FunctionalVideoFilter filter;
  private volatile @Nullable Hologram hologram;
  private volatile @Nullable Location hologramLocation;

  private final MCAVSandbox plugin;
  private final MCAVApi api;
  private final AtomicBoolean status;
  private final ExecutorService service;
  private final AudioProvider provider;
  private final Object lifecycle = new Object();
  private long generation;
  private long startGeneration;
  private boolean closed;
  private @Nullable VideoPlayerMultiplexer startingPlayer;
  private final Set<WorldCleanup> pendingWorld = new HashSet<>();

  /**
   * Constructs the manager and its worker thread.
   *
   * @param plugin the plugin, whose library and audio provider must be available
   */
  public VideoPlayerManager(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.status = new AtomicBoolean(false);
    this.service = Executors.newSingleThreadExecutor();
    this.provider = plugin.getAudioProvider();
    this.api = plugin.getMCAV();
    this.plugin = plugin;
  }

  /**
   * Sets the hologram that shows the title of the video.
   *
   * @param hologram the hologram, or {@code null} for none
   */
  public void setHologram(final @Nullable Hologram hologram) {
    synchronized (this.lifecycle) {
      this.hologram = hologram;
    }
  }

  /**
   * Gets the hologram that shows the title of the video.
   *
   * @return the hologram, or {@code null} if there is none
   */
  public @Nullable Hologram getHologram() {
    return this.hologram;
  }

  /**
   * Sets where the hologram of the next video appears.
   *
   * @param location the location, or {@code null} to show no hologram
   */
  public void setHologramLocation(final @Nullable Location location) {
    this.hologramLocation = location;
  }

  /**
   * Gets where the hologram of the next video appears.
   *
   * @return the location, or {@code null} if no hologram is shown
   */
  public @Nullable Location getHologramLocation() {
    return this.hologramLocation;
  }

  /**
   * Releases the video and stops the worker thread. Called on the main thread when the plugin is disabled.
   */
  public void shutdown() {
    synchronized (this.lifecycle) {
      if (this.closed) {
        return;
      }
      this.closed = true;
    }
    CleanupUtils.runAll(
      this::releaseVideoPlayer,
      this::drainWorldCleanup,
      () -> ExecutorUtils.shutdownExecutorGracefully(this.service),
      this::drainWorldCleanup
    );
  }

  /**
   * Checks whether the library found VLC. While VLC is still being prepared in the background, it is not supported
   * yet; {@link #isPreparing(Capability)} tells the two cases apart.
   *
   * @return true if VLC players can be created
   */
  public boolean isVLCSupported() {
    return this.api.hasCapability(Capability.VLC);
  }

  /**
   * Checks whether the library is still preparing a capability in the background, such as VLC or yt-dlp, which are
   * downloaded on the first start of a server that does not have them.
   *
   * @param capability the capability
   * @return true if the preparation has not finished yet, false if the capability is available or failed
   */
  public boolean isPreparing(final Capability capability) {
    Preconditions.checkNotNull(capability, "Capability must not be null");
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
   * @throws IllegalStateException if the thread is interrupted while waiting, or releasing on the main thread fails
   */
  public void releaseVideoPlayer() {
    this.cancelStart();
    this.clearCurrentVideo();
  }

  /** Invalidates queued resolution, display starts, native completion and notifications immediately. */
  public void cancelStart() {
    synchronized (this.lifecycle) {
      this.generation++;
    }
  }

  // Called after the shared status claim; that claim stays held until the worker finishes, including cancellation.
  long beginStart() {
    synchronized (this.lifecycle) {
      if (this.closed) {
        throw new CancellationException("The video manager is shut down");
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
      throw new CancellationException("The video startup was cancelled");
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
        CleanupUtils.runAll(
          () -> {
            if (oldFilter != null) {
              oldFilter.release();
            }
          },
          () -> {
            if (oldHologram != null) {
              oldHologram.kill();
            }
          }
        )
      );
      this.pendingWorld.add(worldTask);
    }
    CleanupUtils.runAll(
      this.provider::releaseAudioFilter,
      () -> {
        if (oldPlayer != null) {
          oldPlayer.release();
        }
      },
      () -> this.runOnMainThreadAndWait(worldTask)
    );
  }

  /**
   * Takes ownership before scheduling the display. A released display's accepted task cannot start it later.
   * The shared startup claim serializes callers with the worker that resolves and configures the video.
   *
   * @param output the new display filter
   */
  public void startFilter(final FunctionalVideoFilter output) {
    final long expected;
    synchronized (this.lifecycle) {
      // Startup has the shared claim, so no newer request can publish until this worker completes.
      this.filter = output;
      this.checkStartLocked();
      expected = this.startGeneration;
    }
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    try {
      scheduler.runTask(this.plugin, () -> {
        final boolean current;
        synchronized (this.lifecycle) {
          current = this.isCurrent(expected) && IDENTITY.equivalent(this.filter, output);
        }
        if (current) {
          try {
            output.start();
          } catch (final RuntimeException | Error exception) {
            ThrowableUtils.throwIfFatal(exception);
            cleanupAfterFailure(exception, this::releaseVideoPlayer);
            throw exception;
          }
        }
      });
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      cleanupAfterFailure(exception, this::clearCurrentVideo);
      throw exception;
    }
  }

  // Only the worker executes native start. Release detaches an in-flight player; this worker then closes it.
  boolean startNative(final BooleanSupplier start) {
    final VideoPlayerMultiplexer candidate;
    synchronized (this.lifecycle) {
      this.checkStartLocked();
      candidate = Preconditions.checkNotNull(this.player, "No video player was configured");
      this.startingPlayer = candidate;
    }
    final boolean started;
    try {
      started = start.getAsBoolean();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      cleanupAfterFailure(exception, () -> this.finishNative(candidate));
      throw exception;
    }
    this.finishNative(candidate);
    this.checkStart();
    return started;
  }

  /** Runs one cleanup after a recoverable failure; the caller explicitly rethrows that original failure. */
  private static void cleanupAfterFailure(final Throwable failure, final Runnable cleanup) {
    try {
      cleanup.run();
    } catch (final RuntimeException | Error cleanupFailure) {
      ThrowableUtils.throwIfFatal(cleanupFailure);
      if (!IDENTITY.equivalent(failure, cleanupFailure)) {
        failure.addSuppressed(cleanupFailure);
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
    awaitMainThread(completion);
  }

  private void executeWorldCleanup(final WorldCleanup cleanup) {
    try {
      cleanup.run();
    } finally {
      synchronized (this.lifecycle) {
        this.pendingWorld.remove(cleanup);
      }
    }
  }

  private void drainWorldCleanup() {
    final List<WorldCleanup> pending;
    synchronized (this.lifecycle) {
      pending = new ArrayList<>(this.pendingWorld);
    }
    final Runnable[] actions = new Runnable[pending.size()];
    for (int index = 0; index < actions.length; index++) {
      final WorldCleanup cleanup = pending.get(index);
      actions[index] = () -> this.executeWorldCleanup(cleanup);
    }
    CleanupUtils.runAll(actions);
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

  /**
   * Schedules a task on the main thread.
   *
   * @return the result of the task, or {@code null} if the plugin is disabled and the task was not scheduled
   */
  private @Nullable Future<@Nullable Void> scheduleOnMainThread(final Runnable task) {
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    final Callable<@Nullable Void> call = () -> {
      task.run();
      return null;
    };
    try {
      return scheduler.callSyncMethod(this.plugin, call);
    } catch (final IllegalPluginAccessException exception) {
      LOGGER.warn("The plugin is disabled; display and hologram cleanup is retained for the main-thread shutdown drain", exception);
      return null;
    }
  }

  private static void awaitMainThread(final Future<@Nullable Void> result) {
    try {
      result.get();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new IllegalStateException("Interrupted while releasing the video on the main thread", exception);
    } catch (final ExecutionException exception) {
      final Throwable cause = exception.getCause();
      if (cause instanceof final VirtualMachineError fatal) {
        throw fatal;
      }
      throw new IllegalStateException("Failed to release the video on the main thread", cause);
    }
  }

  /**
   * Gets the worker thread that starts and releases videos.
   *
   * @return the executor
   */
  public ExecutorService getService() {
    return this.service;
  }

  /**
   * Gets the flag that is true while a video is starting.
   *
   * @return the flag, shared by every video command
   */
  public AtomicBoolean getStatus() {
    return this.status;
  }

  /**
   * Sets the player of the video.
   *
   * @param player the player, or {@code null} for none
   */
  public void setPlayer(final @Nullable VideoPlayerMultiplexer player) {
    synchronized (this.lifecycle) {
      if (player == null) {
        this.player = null;
        return;
      }
      final boolean starting = this.status.get();
      if (!this.closed && (!starting || this.generation == this.startGeneration)) {
        this.player = player;
        return;
      }
    }
    final CancellationException failure = new CancellationException("The video startup was cancelled");
    cleanupAfterFailure(failure, player::release);
    throw failure;
  }

  /**
   * Gets the player of the video.
   *
   * @return the player, or {@code null} if no video plays
   */
  public @Nullable VideoPlayerMultiplexer getPlayer() {
    return this.player;
  }

  /**
   * Sets the filter that shows the frames of the video.
   *
   * @param filter the filter, or {@code null} for none
   */
  public void setFilter(final @Nullable FunctionalVideoFilter filter) {
    synchronized (this.lifecycle) {
      this.filter = filter;
    }
  }

  /**
   * Gets the filter that shows the frames of the video.
   *
   * @return the filter, or {@code null} if no video plays
   */
  public @Nullable FunctionalVideoFilter getFilter() {
    return this.filter;
  }
}
