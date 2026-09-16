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
package me.brandonli.mcav.bukkit.utils;

import com.google.common.base.Preconditions;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.bukkit.BukkitModule;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The base class of renderers that must apply frames on the main thread of the server.
 *
 * <p>Most Bukkit objects, such as scoreboards and entities, may only be changed on the main thread, but video
 * frames are decoded on other threads. Subclasses convert frames into a ready-to-apply form on the decoding thread
 * and hand the result to {@link #submit(Object)}. The renderer then calls {@link #apply(Object)} once per server
 * tick on the main thread. Only the most recent frame is kept: if frames arrive faster than the server ticks,
 * older frames are dropped instead of queueing up, so the display never falls behind the video.
 *
 * <p>Work that is handed to the main thread from another thread runs during the next server tick. Once the plugin
 * is disabled, Bukkit no longer runs such work, so it is skipped with a warning. During shutdown, release displays
 * on the main thread, for example in {@code onDisable}, so the original state is restored right away.
 *
 * @param <T> the type of frame that is applied on the main thread
 */
public abstract class MainThreadRenderer<T> {

  private static final Logger LOGGER = LoggerFactory.getLogger(MainThreadRenderer.class);
  private static final long START_DELAY_TICKS = 0L;
  private static final long PERIOD_TICKS = 1L;

  private final AtomicReference<@Nullable T> pendingFrame;

  private @Nullable BukkitTask task;

  /**
   * Constructs a new renderer. Nothing is scheduled until {@link #startRendering()} is called.
   */
  protected MainThreadRenderer() {
    this.pendingFrame = new AtomicReference<>();
  }

  /**
   * Applies a frame. This method is always called on the main thread, at most once per server tick.
   *
   * @param frame the frame to apply
   */
  protected abstract void apply(final T frame);

  /**
   * Called on the main thread once per server tick while the renderer is running, right after the pending frame,
   * if there was one, was applied. Subclasses can override this method for work that does not depend on new
   * frames, such as updating viewers who just started watching. Does nothing by default.
   */
  protected void onTick() {
    // nothing to do by default
  }

  /**
   * Starts applying submitted frames once per server tick. Calling this method again while the renderer is
   * running has no effect. May be called from any thread.
   */
  protected synchronized void startRendering() {
    if (this.task != null) {
      return;
    }
    final Plugin plugin = BukkitModule.getPlugin();
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    this.task = scheduler.runTaskTimer(plugin, this::applyPendingFrame, START_DELAY_TICKS, PERIOD_TICKS);
  }

  /**
   * Submits a frame to be applied on the next server tick, replacing any frame that has not been applied yet.
   * May be called from any thread.
   *
   * @param frame the frame to apply
   */
  protected void submit(final T frame) {
    Preconditions.checkNotNull(frame, "Frame must not be null");
    this.pendingFrame.set(frame);
  }

  private void applyPendingFrame() {
    final T frame = this.pendingFrame.getAndSet(null);
    if (frame != null) {
      this.apply(frame);
    }
    this.onTick();
  }

  /**
   * Stops applying frames and discards the pending frame, if any. May be called from any thread.
   */
  protected synchronized void stopRendering() {
    final BukkitTask currentTask = this.task;
    if (currentTask != null) {
      currentTask.cancel();
      this.task = null;
    }
    this.pendingFrame.set(null);
  }

  /**
   * Runs the task on the main thread. If the caller already is on the main thread, the task runs immediately;
   * otherwise it runs during the next server tick.
   *
   * <p>A disabled plugin cannot schedule tasks anymore, so if the plugin is disabled and the caller is not on the
   * main thread, the task is skipped and a warning is logged. Call this method on the main thread during shutdown.
   *
   * @param task the task to run
   */
  public static void runOnMainThread(final Runnable task) {
    Preconditions.checkNotNull(task, "Task must not be null");
    final boolean onMainThread = Bukkit.isPrimaryThread();
    if (onMainThread) {
      task.run();
      return;
    }
    final Plugin plugin = BukkitModule.getPlugin();
    final boolean enabled = plugin.isEnabled();
    if (!enabled) {
      final String pluginName = plugin.getName();
      LOGGER.warn("Skipped a task because the plugin {} is disabled; release displays on the main thread during shutdown", pluginName);
      return;
    }
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduler.runTask(plugin, task);
  }
}
