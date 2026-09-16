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
package me.brandonli.mcav.sandbox.utils;

import com.google.common.base.Preconditions;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands work from worker threads over to the main thread of the server, and reacts to work that finishes in the
 * background.
 */
public final class TaskUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaskUtils.class);

  private TaskUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Creates a task that, when run on any thread, schedules another task on the main thread.
   *
   * @param plugin the plugin that owns the task
   * @param task   the task to run on the main thread
   * @return the task that schedules it; it drops the task when the plugin is disabled, see
   * {@link #runOnMainThread(MCAVSandbox, Runnable)}
   */
  public static Runnable handleAsyncTask(final MCAVSandbox plugin, final Runnable task) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    Preconditions.checkNotNull(task, "Task must not be null");
    return () -> runOnMainThread(plugin, task);
  }

  /**
   * Schedules a task on the main thread. The scheduler refuses tasks once the plugin is disabled; the task is then
   * dropped and a warning is logged.
   *
   * @param plugin the plugin that owns the task
   * @param task   the task to run on the main thread
   * @return true if the task was scheduled, false if it was dropped because the plugin is disabled
   */
  public static boolean runOnMainThread(final MCAVSandbox plugin, final Runnable task) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    Preconditions.checkNotNull(task, "Task must not be null");
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    try {
      scheduler.runTask(plugin, task);
      return true;
    } catch (final IllegalPluginAccessException exception) {
      LOGGER.warn("Dropped a task for the main thread because the plugin is disabled", exception);
      return false;
    }
  }

  /**
   * Runs a callback once a future completes, with its result or its failure, like
   * {@link CompletableFuture#whenComplete}. Unlike that method, nothing is lost when the callback itself fails: the
   * failure is logged, instead of completing a future that nobody reads.
   *
   * <p>The callback runs on the thread that completes the future, or at once on the calling thread if the future has
   * already completed.
   *
   * @param future   the future to wait for
   * @param callback receives the result, which is null if the future failed, and the failure, which is null if the
   *                 future succeeded
   * @param <T>      the type of the result
   */
  public static <T> void whenComplete(
    final CompletableFuture<T> future,
    final BiConsumer<? super T, ? super @Nullable Throwable> callback
  ) {
    Preconditions.checkNotNull(future, "Future must not be null");
    Preconditions.checkNotNull(callback, "Callback must not be null");
    // the handled future carries no value of its own, so nothing can read a result that only exists to satisfy the
    // signatures of handle and exceptionally
    final CompletableFuture<@Nullable Void> handled = future.handle((result, error) -> runCallback(callback, result, error));
    // exceptionally observes a failure of the callback; the future it returns only repeats the logged outcome
    handled.exceptionally(TaskUtils::logCallbackFailure);
  }

  private static <T> @Nullable Void runCallback(
    final BiConsumer<? super T, ? super @Nullable Throwable> callback,
    final T result,
    final @Nullable Throwable error
  ) {
    callback.accept(result, error);
    return null;
  }

  private static @Nullable Void logCallbackFailure(final Throwable failure) {
    LOGGER.error("A callback of a background task failed", failure);
    return null;
  }
}
