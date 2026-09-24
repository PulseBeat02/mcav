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
package me.brandonli.mcav.sandbox.command.image;

import com.google.common.base.Preconditions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.image.Image;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.image.ImagePlayer;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.utils.CleanupUtils;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Holds the image that is shown, together with the thread that loads images.
 *
 * <p>Display ownership is confined to the server main thread: {@link #shutdown()}, all setters and forced
 * {@link #releaseImage(boolean)} calls must run there. An unforced release schedules its display cleanup there.
 * Pending-load ownership and generation invalidation are synchronized separately so loader callbacks may safely
 * retain or discard their native image from a worker thread.
 */
public final class ImageManager {

  private @Nullable ImagePlayer player;
  private @Nullable DisplayableImage image;
  private @Nullable Image currentImage;

  private final MCAVSandbox plugin;
  private final ExecutorService service;
  private final Object loadingLock;
  private long loadGeneration;
  private boolean closed;
  private @Nullable ImageBuffer pendingImage;

  /**
   * Constructs the manager.
   *
   * @param plugin the plugin
   */
  public ImageManager(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.plugin = plugin;
    this.service = Executors.newSingleThreadExecutor();
    this.loadingLock = new Object();
  }

  ImageManager(final MCAVSandbox plugin, final ExecutorService service) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    Preconditions.checkNotNull(service, "Executor must not be null");
    this.plugin = plugin;
    this.service = service;
    this.loadingLock = new Object();
  }

  /**
   * Starts a new loading generation. Pending native images remain owned by the manager until a main-thread task
   * takes them, so cancellation of an already accepted scheduler task cannot leak them.
   */
  long beginLoad() {
    final ImageBuffer previous;
    final long generation;
    synchronized (this.loadingLock) {
      if (this.closed) {
        throw new RejectedExecutionException("The image manager is shut down");
      }
      generation = ++this.loadGeneration;
      previous = this.pendingImage;
      this.pendingImage = null;
    }
    if (previous != null) {
      previous.release();
    }
    return generation;
  }

  boolean retainLoaded(final long generation, final ImageBuffer loaded) {
    synchronized (this.loadingLock) {
      if (this.closed || generation != this.loadGeneration) {
        return false;
      }
      this.pendingImage = loaded;
      return true;
    }
  }

  @Nullable ImageBuffer takeLoaded(final long generation) {
    synchronized (this.loadingLock) {
      if (this.closed || generation != this.loadGeneration) {
        return null;
      }
      final ImageBuffer loaded = this.pendingImage;
      this.pendingImage = null;
      return loaded;
    }
  }

  void discardLoaded(final long generation) {
    final ImageBuffer loaded = this.takeLoaded(generation);
    if (loaded != null) {
      loaded.release();
    }
  }

  private void cancelPendingLoads() {
    final ImageBuffer pending;
    synchronized (this.loadingLock) {
      this.loadGeneration++;
      pending = this.pendingImage;
      this.pendingImage = null;
    }
    if (pending != null) {
      pending.release();
    }
  }

  /**
   * Removes the image and stops the loading thread. Must be called on the server main thread.
   */
  public void shutdown() {
    synchronized (this.loadingLock) {
      this.closed = true;
    }
    CleanupUtils.runAll(() -> this.releaseImage(true), () -> ExecutorUtils.shutdownExecutorGracefully(this.service));
  }

  /**
   * Removes the image that is shown and frees its memory.
   *
   * @param force true to release at once on the calling thread, false to release on the main thread
   */
  public void releaseImage(final boolean force) {
    final Runnable release = force ? this::releaseNow : this::scheduleRelease;
    CleanupUtils.runAll(this::cancelPendingLoads, release);
  }

  private void scheduleRelease() {
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduler.runTask(this.plugin, this::releaseNow);
  }

  // every part is forgotten before it is released, so a failure never releases it twice
  private void releaseNow() {
    final DisplayableImage display = this.image;
    final Image shown = this.currentImage;
    final ImagePlayer imagePlayer = this.player;
    this.image = null;
    this.currentImage = null;
    this.player = null;
    CleanupUtils.runAll(
      () -> {
        if (display != null) {
          display.release();
        }
      },
      () -> {
        if (shown != null) {
          shown.close();
        }
      },
      () -> {
        if (imagePlayer != null) {
          imagePlayer.release();
        }
      }
    );
  }

  /**
   * Sets the player of an animated image. Must be called on the server main thread.
   *
   * @param player the player, or {@code null} for none
   */
  public void setPlayer(final @Nullable ImagePlayer player) {
    this.player = player;
  }

  /**
   * Sets the display that shows the image. Must be called on the server main thread.
   *
   * @param image the display, or {@code null} for none
   */
  public void setImage(final @Nullable DisplayableImage image) {
    this.image = image;
  }

  /**
   * Gets the thread that loads images.
   *
   * @return the executor
   */
  public ExecutorService getService() {
    return this.service;
  }

  /**
   * Sets the image that is shown, which is closed when it is released. Must be called on the server main thread.
   *
   * @param currentImage the image, or {@code null} for none
   */
  public void setCurrentImage(final @Nullable ImageBuffer currentImage) {
    this.currentImage = currentImage;
  }
}
