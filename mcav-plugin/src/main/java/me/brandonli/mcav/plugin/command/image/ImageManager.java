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
package me.brandonli.mcav.plugin.command.image;

import com.google.common.base.Preconditions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.image.Image;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.image.ImagePlayer;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.utils.CleanupUtils;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Holds the image that is shown, together with the thread that loads images.
 *
 * <p>Display ownership is confined to the server main thread: {@link #shutdown()}, all setters and
 * {@link #releaseImage(ReleaseThread)} calls with {@link ReleaseThread#CALLING_THREAD} must run there. A release with
 * {@link ReleaseThread#MAIN_THREAD} schedules its display cleanup there. Pending-load ownership and generation
 * invalidation are synchronized separately so loader callbacks may safely retain or discard their native image from a
 * worker thread.
 *
 * @since 1.0.0
 */
public final class ImageManager {

  private static final String PLUGIN_NULL_MESSAGE = "Plugin must not be null!";

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
   * @since 1.0.0
   */
  public ImageManager(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, PLUGIN_NULL_MESSAGE);

    this.plugin = plugin;
    this.service = Executors.newSingleThreadExecutor();
    this.loadingLock = new Object();
  }

  ImageManager(final MCAVSandbox plugin, final ExecutorService service) {
    Preconditions.checkNotNull(plugin, PLUGIN_NULL_MESSAGE);
    Preconditions.checkNotNull(service, "Executor must not be null!");

    this.plugin = plugin;
    this.service = service;
    this.loadingLock = new Object();
  }

  /**
   * Removes the image and stops the loading thread. Must be called on the server main thread.
   *
   * @since 1.0.0
   */
  public void shutdown() {
    synchronized (this.loadingLock) {
      this.closed = true;
    }
    CleanupUtils.runAll(
      () -> this.releaseImage(ReleaseThread.CALLING_THREAD),
      () -> ExecutorUtils.shutdownExecutorGracefully(this.service)
    );
  }

  // pending images stay the manager's until a main-thread task takes them, so cancelling cannot leak them
  long beginLoad() {
    final ImageBuffer previous;
    final long generation;
    synchronized (this.loadingLock) {
      if (this.closed) {
        final String message = "The image manager is shut down!";
        throw new RejectedExecutionException(message);
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

  /**
   * Removes the image that is shown and frees its memory.
   *
   * @param thread the thread that frees the image
   * @throws NullPointerException if {@code thread} is null
   * @since 1.0.0
   */
  public void releaseImage(final ReleaseThread thread) {
    Preconditions.checkNotNull(thread, "Release thread must not be null!");

    final Runnable release = thread == ReleaseThread.CALLING_THREAD ? this::releaseNow : this::scheduleRelease;
    CleanupUtils.runAll(this::cancelPendingLoads, release);
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

  private void scheduleRelease() {
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduler.runTask(this.plugin, this::releaseNow);
  }

  private void releaseNow() {
    final DisplayableImage display = this.image;
    final Image shown = this.currentImage;
    final ImagePlayer imagePlayer = this.player;
    this.image = null;
    this.currentImage = null;
    this.player = null;
    CleanupUtils.runAll(() -> this.releaseOldDisplay(display), () -> this.closeShownImage(shown), () -> this.releaseOldPlayer(imagePlayer));
  }

  private void releaseOldDisplay(final @Nullable DisplayableImage display) {
    if (display != null) {
      display.release();
    }
  }

  private void closeShownImage(final @Nullable Image shown) {
    if (shown != null) {
      shown.close();
    }
  }

  private void releaseOldPlayer(final @Nullable ImagePlayer imagePlayer) {
    if (imagePlayer != null) {
      imagePlayer.release();
    }
  }

  /**
   * Sets the player of an animated image. Must be called on the server main thread.
   *
   * @param player the player, or {@code null} for none
   * @since 1.0.0
   */
  public void setPlayer(final @Nullable ImagePlayer player) {
    this.player = player;
  }

  /**
   * Sets the display that shows the image. Must be called on the server main thread.
   *
   * @param image the display, or {@code null} for none
   * @since 1.0.0
   */
  public void setImage(final @Nullable DisplayableImage image) {
    this.image = image;
  }

  /**
   * Returns the thread that loads images.
   *
   * @return the executor
   * @since 1.0.0
   */
  public ExecutorService getService() {
    return this.service;
  }

  /**
   * Sets the image that is shown, which is closed when it is released. Must be called on the server main thread.
   *
   * @param currentImage the image, or {@code null} for none
   * @since 1.0.0
   */
  public void setCurrentImage(final @Nullable ImageBuffer currentImage) {
    this.currentImage = currentImage;
  }
}
