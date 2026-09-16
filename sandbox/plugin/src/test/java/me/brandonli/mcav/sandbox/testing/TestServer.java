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
package me.brandonli.mcav.sandbox.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.base.Preconditions;
import io.papermc.paper.ServerBuildInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * The mocked server behind {@link Bukkit} for all tests of the plugin.
 *
 * <p>Bukkit accepts its server only once per JVM, so every test shares one mock and calls {@link #reset()} before
 * it runs. Unlike a static mock of {@link Bukkit}, the shared server is also reachable from the worker threads of
 * the code under test.
 */
public final class TestServer {

  private static final Server SERVER = install();
  private static final List<Runnable> PENDING_TASKS = Collections.synchronizedList(new ArrayList<>());

  private TestServer() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static Server install() {
    final Server server = mock(Server.class);
    final Logger logger = Logger.getLogger("TestServer");
    when(server.getLogger()).thenReturn(logger);
    final ServerBuildInfo buildInfo = mock(ServerBuildInfo.class);
    try (final MockedStatic<ServerBuildInfo> mocked = Mockito.mockStatic(ServerBuildInfo.class)) {
      mocked.when(ServerBuildInfo::buildInfo).thenReturn(buildInfo);
      Bukkit.setServer(server);
    }
    return server;
  }

  /**
   * Resets the server and gives it a new plugin manager and a scheduler that runs every task at once, as if the
   * caller were the main thread.
   *
   * <p>The thread that calls this method is the main thread of the server: {@link Bukkit#isPrimaryThread()} is true
   * only on it, so code that must not touch the world from other threads can be checked.
   *
   * @return the server
   */
  public static Server reset() {
    Mockito.reset(SERVER);
    PENDING_TASKS.clear();
    final Thread mainThread = Thread.currentThread();
    // threads do not override equals, so this asks whether the caller is the very main thread
    when(SERVER.isPrimaryThread()).thenAnswer(_ -> {
      final Thread current = Thread.currentThread();
      return mainThread.equals(current);
    });
    final Logger logger = Logger.getLogger("TestServer");
    when(SERVER.getLogger()).thenReturn(logger);
    final PluginManager pluginManager = mock(PluginManager.class);
    when(SERVER.getPluginManager()).thenReturn(pluginManager);
    final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
      final Runnable task = invocation.getArgument(1);
      task.run();
      return null;
    });
    when(scheduler.callSyncMethod(any(Plugin.class), any())).thenAnswer(invocation -> {
      final Callable<?> call = invocation.getArgument(1);
      final FutureTask<?> task = new FutureTask<>(call);
      task.run();
      return task;
    });
    when(SERVER.getScheduler()).thenReturn(scheduler);
    return SERVER;
  }

  /**
   * Resets the server like {@link #reset()}, but its scheduler only collects the tasks, which
   * {@link #runPendingTasks()} runs later. Tasks given to {@link BukkitScheduler#callSyncMethod} are collected as
   * well, and their futures complete once they ran.
   *
   * @return the server
   */
  public static Server resetWithDeferredTasks() {
    final Server server = reset();
    final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
      final Runnable task = invocation.getArgument(1);
      PENDING_TASKS.add(task);
      return null;
    });
    when(scheduler.callSyncMethod(any(Plugin.class), any())).thenAnswer(invocation -> {
      final Callable<?> call = invocation.getArgument(1);
      final FutureTask<?> task = new FutureTask<>(call);
      PENDING_TASKS.add(task);
      return task;
    });
    when(server.getScheduler()).thenReturn(scheduler);
    return server;
  }

  /**
   * Runs the tasks collected since {@link #resetWithDeferredTasks()}.
   *
   * @return how many tasks ran
   */
  public static int runPendingTasks() {
    final List<Runnable> tasks;
    synchronized (PENDING_TASKS) {
      tasks = new ArrayList<>(PENDING_TASKS);
      PENDING_TASKS.clear();
    }
    for (final Runnable task : tasks) {
      task.run();
    }
    return tasks.size();
  }

  /**
   * Makes sure the shared server is installed behind {@link Bukkit}. It is installed when this class is loaded, so
   * code that needs Bukkit before any test touched this class only has to call this method.
   *
   * @throws IllegalStateException if Bukkit has a server other than the test server
   */
  public static void ensureInstalled() {
    final Server installed = Bukkit.getServer();
    // the equals of a Mockito mock compares the very instance
    final boolean testServerInstalled = Objects.equals(installed, SERVER);
    Preconditions.checkState(testServerInstalled, "Bukkit has a server that is not the test server");
  }

  /**
   * Gets the server.
   *
   * @return the server
   */
  public static Server server() {
    return SERVER;
  }

  /**
   * Gets the scheduler of the server.
   *
   * @return the scheduler
   */
  public static BukkitScheduler scheduler() {
    return SERVER.getScheduler();
  }

  /**
   * Gets the plugin manager of the server.
   *
   * @return the plugin manager
   */
  public static PluginManager pluginManager() {
    return SERVER.getPluginManager();
  }
}
