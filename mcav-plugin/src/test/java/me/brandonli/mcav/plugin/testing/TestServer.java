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
package me.brandonli.mcav.plugin.testing;

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
import org.bukkit.plugin.messaging.StandardMessenger;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

// Bukkit accepts its server once per JVM, so every test shares one mock
public final class TestServer {

  private static final Server SERVER = install();
  private static final List<Runnable> PENDING_TASKS = Collections.synchronizedList(new ArrayList<>());

  private static final String LOGGER_NAME = "TestServer";

  private TestServer() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  private static Server install() {
    final Server server = mock(Server.class);
    final Logger logger = Logger.getLogger(LOGGER_NAME);
    when(server.getLogger()).thenReturn(logger);
    final ServerBuildInfo buildInformation = mock(ServerBuildInfo.class);
    try (final MockedStatic<ServerBuildInfo> mocked = Mockito.mockStatic(ServerBuildInfo.class)) {
      mocked.when(ServerBuildInfo::buildInfo).thenReturn(buildInformation);
      Bukkit.setServer(server);
    }
    return server;
  }

  public static Server reset() {
    Mockito.reset(SERVER);
    PENDING_TASKS.clear();
    final Thread mainThread = Thread.currentThread();
    when(SERVER.isPrimaryThread()).thenAnswer(_ -> TestServer.isMainThread(mainThread));
    final Logger logger = Logger.getLogger(LOGGER_NAME);
    when(SERVER.getLogger()).thenReturn(logger);
    final PluginManager pluginManager = mock(PluginManager.class);
    when(SERVER.getPluginManager()).thenReturn(pluginManager);
    when(SERVER.getMessenger()).thenReturn(new StandardMessenger());
    final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(TestServer::runTask);
    when(scheduler.callSyncMethod(any(Plugin.class), any())).thenAnswer(TestServer::runCallable);
    when(SERVER.getScheduler()).thenReturn(scheduler);

    return SERVER;
  }

  private static boolean isMainThread(final Thread mainThread) {
    final Thread current = Thread.currentThread();
    return mainThread.equals(current);
  }

  private static @Nullable Object runTask(final InvocationOnMock invocation) {
    final Runnable task = invocation.getArgument(1);
    task.run();

    return null;
  }

  private static FutureTask<?> runCallable(final InvocationOnMock invocation) {
    final Callable<?> call = invocation.getArgument(1);
    final FutureTask<?> task = new FutureTask<>(call);
    task.run();
    return task;
  }

  public static Server resetWithDeferredTasks() {
    final Server server = reset();
    final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(TestServer::deferTask);
    when(scheduler.callSyncMethod(any(Plugin.class), any())).thenAnswer(TestServer::deferCallable);
    when(server.getScheduler()).thenReturn(scheduler);

    return server;
  }

  private static @Nullable Object deferTask(final InvocationOnMock invocation) {
    final Runnable task = invocation.getArgument(1);
    PENDING_TASKS.add(task);

    return null;
  }

  private static FutureTask<?> deferCallable(final InvocationOnMock invocation) {
    final Callable<?> call = invocation.getArgument(1);
    final FutureTask<?> task = new FutureTask<>(call);
    PENDING_TASKS.add(task);

    return task;
  }

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

  // installed when this class loads, for code that needs Bukkit before any test runs
  public static void ensureInstalled() {
    final Server installed = Bukkit.getServer();
    final boolean testServerInstalled = Objects.equals(installed, SERVER);
    Preconditions.checkState(testServerInstalled, "Bukkit has a server that is not the test server!");
  }

  public static Server server() {
    return SERVER;
  }

  public static BukkitScheduler scheduler() {
    return SERVER.getScheduler();
  }

  public static PluginManager pluginManager() {
    return SERVER.getPluginManager();
  }
}
