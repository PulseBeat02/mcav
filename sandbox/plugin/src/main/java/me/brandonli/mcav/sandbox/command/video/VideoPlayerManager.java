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

import com.google.common.base.Preconditions;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.bukkit.hologram.Hologram;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.utils.ExecutorUtils;
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
    this.hologram = hologram;
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
    this.releaseVideoPlayer();
    ExecutorUtils.shutdownExecutorGracefully(this.service);
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
   * main thread accepts no more tasks; they are then left alone and a warning is logged.
   *
   * @throws IllegalStateException if the thread is interrupted while waiting, or releasing on the main thread fails
   */
  public void releaseVideoPlayer() {
    final VideoPlayerMultiplexer oldPlayer = this.player;
    final FunctionalVideoFilter oldFilter = this.filter;
    final Hologram oldHologram = this.hologram;

    this.player = null;
    this.filter = null;
    this.hologram = null;

    this.provider.releaseAudioFilter();
    if (oldPlayer != null) {
      oldPlayer.release();
    }
    final Runnable worldTask = () -> {
      if (oldFilter != null) {
        oldFilter.release();
      }
      if (oldHologram != null) {
        oldHologram.kill();
      }
    };
    this.runOnMainThreadAndWait(worldTask);
  }

  private void runOnMainThreadAndWait(final Runnable task) {
    final boolean mainThread = Bukkit.isPrimaryThread();
    if (mainThread) {
      task.run();
      return;
    }

    final Future<@Nullable Void> result = this.scheduleOnMainThread(task);
    if (result != null) {
      awaitMainThread(result);
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
      LOGGER.warn("The plugin is disabled, so the display and the hologram of the video were not released", exception);
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
    this.player = player;
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
    this.filter = filter;
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
