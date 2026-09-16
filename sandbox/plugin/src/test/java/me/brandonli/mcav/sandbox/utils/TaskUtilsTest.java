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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.testing.StandardErrorCapture;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.testing.UtilityClassAssertions;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link TaskUtils}.
 */
final class TaskUtilsTest {

  @BeforeEach
  void resetServer() {
    TestServer.resetWithDeferredTasks();
  }

  @Test
  void schedulesTheTaskOnTheMainThreadOnlyWhenRun() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final AtomicInteger runs = new AtomicInteger();
    final Runnable task = runs::incrementAndGet;
    final Runnable handover = TaskUtils.handleAsyncTask(plugin, task);
    final int pendingBefore = TestServer.runPendingTasks();
    assertEquals(0, pendingBefore);
    handover.run();
    final BukkitScheduler scheduler = TestServer.scheduler();
    verify(scheduler).runTask(plugin, task);
    final int runsBeforeTick = runs.get();
    assertEquals(0, runsBeforeTick);
    final int pending = TestServer.runPendingTasks();
    assertEquals(1, pending);
    final int runsAfterTick = runs.get();
    assertEquals(1, runsAfterTick);
  }

  @Test
  void isNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(TaskUtils.class);
  }

  @Test
  void schedulesATaskOnTheMainThread() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final AtomicInteger runs = new AtomicInteger();
    final boolean scheduled = TaskUtils.runOnMainThread(plugin, runs::incrementAndGet);
    assertTrue(scheduled);
    final int runsBeforeTick = runs.get();
    assertEquals(0, runsBeforeTick);
    final int pending = TestServer.runPendingTasks();
    assertEquals(1, pending);
    final int runsAfterTick = runs.get();
    assertEquals(1, runsAfterTick);
  }

  @Test
  void dropsTheTaskWhenThePluginIsDisabled() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final BukkitScheduler scheduler = TestServer.scheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(new IllegalPluginAccessException("disabled"));
    final AtomicInteger runs = new AtomicInteger();
    final boolean scheduled = TaskUtils.runOnMainThread(plugin, runs::incrementAndGet);
    assertFalse(scheduled);
    final Runnable handover = TaskUtils.handleAsyncTask(plugin, runs::incrementAndGet);
    handover.run();
    final int count = runs.get();
    assertEquals(0, count);
  }

  @Test
  void passesTheResultOfTheFutureToTheCallback() {
    final CompletableFuture<String> future = CompletableFuture.completedFuture("done");
    final AtomicReference<String> result = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();

    TaskUtils.whenComplete(future, (value, error) -> {
      calls.incrementAndGet();
      result.set(value);
      failure.set(error);
    });

    final int callCount = calls.get();
    final String received = result.get();
    final Throwable receivedFailure = failure.get();
    assertEquals(1, callCount);
    assertEquals("done", received);
    assertNull(receivedFailure);
  }

  @Test
  void passesTheFailureOfTheFutureToTheCallback() {
    final IllegalStateException boom = new IllegalStateException("boom");
    final CompletableFuture<String> future = CompletableFuture.failedFuture(boom);
    final AtomicReference<String> result = new AtomicReference<>("not called");
    final AtomicReference<Throwable> failure = new AtomicReference<>();

    TaskUtils.whenComplete(future, (value, error) -> {
      result.set(value);
      failure.set(error);
    });

    final String received = result.get();
    final Throwable receivedFailure = failure.get();
    assertNull(received);
    assertSame(boom, receivedFailure);
  }

  @Test
  void runsTheCallbackOnceTheFutureCompletesLater() {
    final CompletableFuture<String> future = new CompletableFuture<>();
    final AtomicReference<String> result = new AtomicReference<>();
    TaskUtils.whenComplete(future, (value, _) -> result.set(value));
    final String beforeCompletion = result.get();

    future.complete("later");

    final String afterCompletion = result.get();
    assertNull(beforeCompletion);
    assertEquals("later", afterCompletion);
  }

  @Test
  void logsACallbackThatFailsInsteadOfLosingIt() {
    final CompletableFuture<String> future = CompletableFuture.completedFuture("done");
    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      TaskUtils.whenComplete(future, (_, _) -> {
        throw new IllegalStateException("the callback failed");
      });
      output = errors.getOutput();
    }

    final boolean logged = output.contains("A callback of a background task failed");
    final boolean withCause = output.contains("the callback failed");
    assertTrue(logged, output);
    assertTrue(withCause, output);
  }

  @Test
  void refusesNullArguments() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final Runnable task = () -> {};
    final CompletableFuture<String> future = CompletableFuture.completedFuture("done");
    final BiConsumer<String, Throwable> callback = (_, _) -> {};
    assertThrows(NullPointerException.class, () -> TaskUtils.runOnMainThread(null, task));
    assertThrows(NullPointerException.class, () -> TaskUtils.runOnMainThread(plugin, null));
    assertThrows(NullPointerException.class, () -> TaskUtils.handleAsyncTask(null, task));
    assertThrows(NullPointerException.class, () -> TaskUtils.handleAsyncTask(plugin, null));
    assertThrows(NullPointerException.class, () -> TaskUtils.whenComplete(null, callback));
    assertThrows(NullPointerException.class, () -> TaskUtils.whenComplete(future, null));
  }
}
