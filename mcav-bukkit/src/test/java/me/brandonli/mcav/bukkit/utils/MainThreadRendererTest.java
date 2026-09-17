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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.LogCapture;
import org.apache.logging.log4j.Level;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link MainThreadRenderer}.
 */
final class MainThreadRendererTest {

  private FakeServer server;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.injectModule();
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  @Test
  void keepsApplyingFramesForARendererThatDoesNotOverrideOnTick() {
    // every renderer of this module overrides onTick, so without this the default body is never executed
    final PlainRenderer renderer = new PlainRenderer();
    renderer.startRendering();
    renderer.submit("frame");

    this.server.runTasks();
    this.server.runTasks();

    final List<String> applied = renderer.getAppliedFrames();
    final List<String> expected = List.of("frame");
    assertEquals(expected, applied, "a renderer without an onTick of its own still applies its frames");
    renderer.stopRendering();
  }

  @Test
  void startsOneTaskThatRunsEveryTick() {
    final RecordingRenderer renderer = new RecordingRenderer();
    renderer.startRendering();
    renderer.startRendering();
    final BukkitScheduler scheduler = this.server.getScheduler();
    final Plugin plugin = this.server.getPlugin();
    final int taskCount = this.server.getScheduledTaskCount();
    verify(scheduler, times(1)).runTaskTimer(eq(plugin), any(Runnable.class), eq(0L), eq(1L));
    assertEquals(1, taskCount);
  }

  @Test
  void appliesOnlyTheLatestFrameAndOnlyOnce() {
    final RecordingRenderer renderer = new RecordingRenderer();
    renderer.startRendering();
    renderer.submit("first");
    renderer.submit("second");
    this.server.runTasks();
    this.server.runTasks();
    final List<String> applied = renderer.getAppliedFrames();
    final List<String> expected = List.of("second");
    assertEquals(expected, applied);
  }

  @Test
  void acceptsFramesFromOtherThreads() throws InterruptedException {
    final RecordingRenderer renderer = new RecordingRenderer();
    renderer.startRendering();
    final Thread decoder = new Thread(() -> renderer.submit("decoded"));
    decoder.start();
    decoder.join();
    this.server.runTasks();
    final List<String> applied = renderer.getAppliedFrames();
    final List<String> expected = List.of("decoded");
    assertEquals(expected, applied);
  }

  @Test
  void rejectsMissingFrames() {
    final RecordingRenderer renderer = new RecordingRenderer();
    assertThrows(NullPointerException.class, () -> renderer.submit(null));
  }

  @Test
  void stoppingCancelsTheTaskAndDiscardsThePendingFrame() {
    final RecordingRenderer renderer = new RecordingRenderer();
    renderer.startRendering();
    renderer.submit("dropped");
    renderer.stopRendering();
    final int taskCount = this.server.getScheduledTaskCount();
    renderer.startRendering();
    this.server.runTasks();
    final List<String> applied = renderer.getAppliedFrames();
    final BukkitScheduler scheduler = this.server.getScheduler();
    final boolean nothingApplied = applied.isEmpty();
    assertEquals(0, taskCount);
    assertTrue(nothingApplied, "the pending frame was discarded");
    verify(scheduler, times(2)).runTaskTimer(any(Plugin.class), any(Runnable.class), eq(0L), eq(1L));
  }

  @Test
  void stoppingARendererThatNeverStartedOnlyDiscardsThePendingFrame() {
    final RecordingRenderer renderer = new RecordingRenderer();
    renderer.submit("dropped");
    renderer.stopRendering();
    renderer.startRendering();
    this.server.runTasks();
    final List<String> applied = renderer.getAppliedFrames();
    final boolean nothingApplied = applied.isEmpty();
    assertTrue(nothingApplied, "the pending frame was discarded");
  }

  @Test
  void runsTasksImmediatelyOnTheMainThread() {
    final AtomicInteger runs = new AtomicInteger();
    MainThreadRenderer.runOnMainThread(runs::incrementAndGet);
    final int count = runs.get();
    final BukkitScheduler scheduler = this.server.getScheduler();
    assertEquals(1, count);
    verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
  }

  @Test
  void schedulesTasksForTheNextTickOffTheMainThread() {
    this.server.setPrimaryThread(false);
    final AtomicInteger runs = new AtomicInteger();
    final Runnable task = runs::incrementAndGet;
    MainThreadRenderer.runOnMainThread(task);
    final int before = runs.get();
    this.server.runTasks();
    final int after = runs.get();
    final BukkitScheduler scheduler = this.server.getScheduler();
    final Plugin plugin = this.server.getPlugin();
    assertEquals(0, before);
    assertEquals(1, after);
    verify(scheduler).runTask(plugin, task);
    assertThrows(NullPointerException.class, () -> MainThreadRenderer.runOnMainThread(null));
  }

  @Test
  void callsTheTickHookEveryTickWithOrWithoutAFrame() {
    final RecordingRenderer renderer = new RecordingRenderer();
    renderer.startRendering();
    renderer.submit("frame");
    this.server.runTasks();
    this.server.runTasks();
    final List<String> applied = renderer.getAppliedFrames();
    final int ticks = renderer.getTicks();
    final List<String> expected = List.of("frame");
    assertEquals(expected, applied);
    assertEquals(2, ticks);
  }

  @Test
  void skipsTasksFromOtherThreadsWithAWarningOnceThePluginIsDisabled() {
    this.server.setPrimaryThread(false);
    final Plugin plugin = this.server.getPlugin();
    when(plugin.isEnabled()).thenReturn(false);
    when(plugin.getName()).thenReturn("MCAV");
    final AtomicInteger runs = new AtomicInteger();
    final List<LogCapture.RecordedEvent> events;
    try (final LogCapture logs = LogCapture.capture(MainThreadRenderer.class)) {
      MainThreadRenderer.runOnMainThread(runs::incrementAndGet);
      this.server.runTasks();
      events = logs.getEvents();
    }
    final int count = runs.get();
    final BukkitScheduler scheduler = this.server.getScheduler();
    final LogCapture.RecordedEvent event = events.getFirst();
    final Level level = event.getLevel();
    final String message = event.getMessage();
    final int eventCount = events.size();
    assertEquals(0, count, "Bukkit refuses tasks of disabled plugins, so nothing is scheduled");
    verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
    assertEquals(1, eventCount);
    assertEquals(Level.WARN, level);
    assertEquals("Skipped a task because the plugin MCAV is disabled; release displays on the main thread during shutdown", message);
  }

  @Test
  void stillRunsTasksOnTheMainThreadOnceThePluginIsDisabled() {
    final Plugin plugin = this.server.getPlugin();
    when(plugin.isEnabled()).thenReturn(false);
    final AtomicInteger runs = new AtomicInteger();
    MainThreadRenderer.runOnMainThread(runs::incrementAndGet);
    final int count = runs.get();
    assertEquals(1, count);
  }

  /**
   * A renderer that overrides nothing but {@link MainThreadRenderer#apply}, so the default {@code onTick} runs.
   */
  private static final class PlainRenderer extends MainThreadRenderer<String> {

    private final List<String> appliedFrames = new CopyOnWriteArrayList<>();

    @Override
    protected void apply(final String frame) {
      this.appliedFrames.add(frame);
    }

    List<String> getAppliedFrames() {
      return List.copyOf(this.appliedFrames);
    }
  }

  /**
   * Records every frame it applies and counts the ticks.
   */
  private static final class RecordingRenderer extends MainThreadRenderer<String> {

    private final List<String> appliedFrames = new CopyOnWriteArrayList<>();
    private final AtomicInteger ticks = new AtomicInteger();

    @Override
    protected void apply(final String frame) {
      this.appliedFrames.add(frame);
    }

    @Override
    protected void onTick() {
      this.ticks.incrementAndGet();
    }

    List<String> getAppliedFrames() {
      return List.copyOf(this.appliedFrames);
    }

    int getTicks() {
      return this.ticks.get();
    }
  }
}
