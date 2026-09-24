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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.bukkit.hologram.Hologram;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.testing.TestServer;
import org.bukkit.Location;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/**
 * Tests {@link VideoPlayerManager}. The test thread is the main thread of the server; the worker thread of the
 * manager is a real thread, so releasing from it shows whether the world is only changed on the main thread.
 */
final class VideoPlayerManagerTest {

  private final List<Thread> worldThreads = new CopyOnWriteArrayList<>();
  private final List<Thread> playerThreads = new CopyOnWriteArrayList<>();

  private MCAVApi api;
  private AudioProvider provider;
  private VideoPlayerManager manager;
  private VideoPlayerMultiplexer player;
  private FunctionalVideoFilter filter;
  private Hologram hologram;

  @BeforeEach
  void createManager() {
    TestServer.resetWithDeferredTasks();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.api = mock(MCAVApi.class);
    this.provider = mock(AudioProvider.class);
    when(plugin.getMCAV()).thenReturn(this.api);
    when(plugin.getAudioProvider()).thenReturn(this.provider);
    this.manager = new VideoPlayerManager(plugin);

    this.player = mock(VideoPlayerMultiplexer.class);
    this.filter = mock(FunctionalVideoFilter.class);
    this.hologram = mock(Hologram.class);
    final Answer<Void> recordPlayerThread = recordThread(this.playerThreads);
    final Answer<Void> recordWorldThread = recordThread(this.worldThreads);
    doAnswer(recordPlayerThread).when(this.player).release();
    doAnswer(recordWorldThread).when(this.filter).release();
    doAnswer(recordWorldThread).when(this.hologram).kill();
  }

  @AfterEach
  void shutDown() {
    TestServer.runPendingTasks();
    this.manager.shutdown();
  }

  private static Answer<Void> recordThread(final List<Thread> threads) {
    return _ -> {
      final Thread current = Thread.currentThread();
      threads.add(current);
      return null;
    };
  }

  private void fill() {
    this.manager.setPlayer(this.player);
    this.manager.setFilter(this.filter);
    this.manager.setHologram(this.hologram);
  }

  private Future<?> releaseOnTheWorker() {
    final ExecutorService service = this.manager.getService();
    return service.submit(this.manager::releaseVideoPlayer);
  }

  /**
   * Makes the scheduler hand every task for the main thread to the returned queue, so the test can wait for the
   * worker to schedule one and decide when it runs.
   */
  private static BlockingQueue<Runnable> interceptMainThreadTasks() {
    final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    final BukkitScheduler scheduler = TestServer.scheduler();
    when(scheduler.callSyncMethod(any(Plugin.class), any())).thenAnswer(invocation -> {
      final Callable<?> call = invocation.getArgument(1);
      final FutureTask<?> task = new FutureTask<>(call);
      tasks.add(task);
      return task;
    });
    return tasks;
  }

  private static Runnable awaitMainThreadTask(final BlockingQueue<Runnable> tasks) throws InterruptedException {
    final Runnable task = tasks.poll(10, TimeUnit.SECONDS);
    assertNotNull(task, "No task was scheduled on the main thread");
    return task;
  }

  private void assertWorldReleasedOnTheMainThreadAndPlayerOnTheWorker() {
    final Thread main = Thread.currentThread();
    final List<Thread> expectedWorldThreads = List.of(main, main);
    assertEquals(expectedWorldThreads, this.worldThreads);
    final Thread playerThread = this.playerThreads.getFirst();
    final boolean releasedOnWorker = !playerThread.equals(main);
    assertTrue(releasedOnWorker);
  }

  @Test
  void refusesANullPlugin() {
    assertThrows(NullPointerException.class, () -> new VideoPlayerManager(null));
  }

  @Test
  void startsEmpty() {
    final VideoPlayerMultiplexer currentPlayer = this.manager.getPlayer();
    final FunctionalVideoFilter currentFilter = this.manager.getFilter();
    final Hologram currentHologram = this.manager.getHologram();
    final Location location = this.manager.getHologramLocation();
    final AtomicBoolean status = this.manager.getStatus();
    final boolean starting = status.get();

    assertNull(currentPlayer);
    assertNull(currentFilter);
    assertNull(currentHologram);
    assertNull(location);
    assertFalse(starting);
  }

  @Test
  void remembersWhatItIsGiven() {
    this.fill();
    final Location location = new Location(null, 1.0, 2.0, 3.0);
    this.manager.setHologramLocation(location);

    final VideoPlayerMultiplexer currentPlayer = this.manager.getPlayer();
    final FunctionalVideoFilter currentFilter = this.manager.getFilter();
    final Hologram currentHologram = this.manager.getHologram();
    final Location currentLocation = this.manager.getHologramLocation();

    assertSame(this.player, currentPlayer);
    assertSame(this.filter, currentFilter);
    assertSame(this.hologram, currentHologram);
    assertSame(location, currentLocation);
  }

  @Test
  void asksTheLibraryWhetherVlcIsAvailable() {
    when(this.api.hasCapability(Capability.VLC)).thenReturn(true);
    final boolean supported = this.manager.isVLCSupported();
    assertTrue(supported);

    when(this.api.hasCapability(Capability.VLC)).thenReturn(false);
    final boolean unsupported = this.manager.isVLCSupported();
    assertFalse(unsupported);
  }

  @Test
  void asksTheLibraryWhetherACapabilityIsStillBeingPrepared() {
    final CompletableFuture<Boolean> pending = new CompletableFuture<>();
    final CompletableFuture<Boolean> failed = CompletableFuture.completedFuture(false);
    final CompletableFuture<Boolean> ready = CompletableFuture.completedFuture(true);
    when(this.api.whenCapabilityReady(Capability.VLC)).thenReturn(pending);
    when(this.api.whenCapabilityReady(Capability.YT_DLP)).thenReturn(failed);
    when(this.api.whenCapabilityReady(Capability.FFMPEG)).thenReturn(ready);

    final boolean vlcPreparing = this.manager.isPreparing(Capability.VLC);
    final boolean ytdlpPreparing = this.manager.isPreparing(Capability.YT_DLP);
    final boolean ffmpegPreparing = this.manager.isPreparing(Capability.FFMPEG);

    assertTrue(vlcPreparing);
    assertFalse(ytdlpPreparing, "a failed preparation is over");
    assertFalse(ffmpegPreparing);
    assertThrows(NullPointerException.class, () -> this.manager.isPreparing(null));
  }

  @Test
  void releasesEverythingAtOnceOnTheMainThread() {
    this.fill();

    this.manager.releaseVideoPlayer();

    verify(this.provider).releaseAudioFilter();
    verify(this.player).release();
    verify(this.filter).release();
    verify(this.hologram).kill();
    final Thread main = Thread.currentThread();
    final List<Thread> expectedWorldThreads = List.of(main, main);
    assertEquals(expectedWorldThreads, this.worldThreads);
    final VideoPlayerMultiplexer currentPlayer = this.manager.getPlayer();
    final FunctionalVideoFilter currentFilter = this.manager.getFilter();
    final Hologram currentHologram = this.manager.getHologram();
    assertNull(currentPlayer);
    assertNull(currentFilter);
    assertNull(currentHologram);
    final int pending = TestServer.runPendingTasks();
    assertEquals(0, pending);
  }

  @Test
  void releasesTheWorldOnTheMainThreadAndWaitsForItWhenCalledFromTheWorker()
    throws InterruptedException, ExecutionException, TimeoutException {
    this.fill();
    final BlockingQueue<Runnable> mainThreadTasks = interceptMainThreadTasks();

    final Future<?> release = this.releaseOnTheWorker();
    final Runnable worldTask = awaitMainThreadTask(mainThreadTasks);

    final boolean doneBeforeTick = release.isDone();
    assertFalse(doneBeforeTick);
    verify(this.player).release();
    verify(this.filter, never()).release();
    verify(this.hologram, never()).kill();
    final VideoPlayerMultiplexer currentPlayer = this.manager.getPlayer();
    assertNull(currentPlayer);

    worldTask.run();
    release.get(10, TimeUnit.SECONDS);
    final boolean onlyOneTask = mainThreadTasks.isEmpty();
    assertTrue(onlyOneTask);
    this.assertWorldReleasedOnTheMainThreadAndPlayerOnTheWorker();
  }

  @Test
  void leavesTheWorldAloneWhenThePluginIsAlreadyDisabled() throws InterruptedException, ExecutionException, TimeoutException {
    this.fill();
    final BukkitScheduler scheduler = TestServer.scheduler();
    when(scheduler.callSyncMethod(any(Plugin.class), any())).thenThrow(new IllegalPluginAccessException("disabled"));

    final Future<?> release = this.releaseOnTheWorker();
    release.get(10, TimeUnit.SECONDS);

    verify(this.player).release();
    verify(this.provider).releaseAudioFilter();
    final boolean worldUntouched = this.worldThreads.isEmpty();
    assertTrue(worldUntouched);
    final Hologram currentHologram = this.manager.getHologram();
    assertNull(currentHologram);
  }

  @Test
  void failsWhenInterruptedWhileWaitingForTheMainThread() throws InterruptedException {
    this.fill();
    final BlockingQueue<Runnable> mainThreadTasks = interceptMainThreadTasks();
    final Future<?> release = this.releaseOnTheWorker();
    awaitMainThreadTask(mainThreadTasks);

    final ExecutorService service = this.manager.getService();
    service.shutdownNow();

    final ExecutionException exception = assertThrows(ExecutionException.class, () -> release.get(10, TimeUnit.SECONDS));
    final Throwable cause = exception.getCause();
    final IllegalStateException failure = assertInstanceOf(IllegalStateException.class, cause);
    final String message = failure.getMessage();
    assertEquals("Interrupted while releasing the video on the main thread", message);
    final Throwable interruption = failure.getCause();
    assertInstanceOf(InterruptedException.class, interruption);
  }

  @Test
  void keepsTheInterruptWhenInterruptedWhileWaitingForTheMainThread() throws InterruptedException, ExecutionException, TimeoutException {
    this.fill();
    final BlockingQueue<Runnable> mainThreadTasks = interceptMainThreadTasks();
    final AtomicBoolean keptInterrupt = new AtomicBoolean();
    final ExecutorService service = this.manager.getService();
    final Future<?> release = service.submit(() -> {
      try {
        this.manager.releaseVideoPlayer();
      } catch (final IllegalStateException expected) {
        final boolean interrupted = Thread.interrupted();
        keptInterrupt.set(interrupted);
      }
    });
    awaitMainThreadTask(mainThreadTasks);

    service.shutdownNow();

    release.get(10, TimeUnit.SECONDS);
    final boolean restored = keptInterrupt.get();
    assertTrue(restored, "the interrupt is restored for the caller of the release");
  }

  @Test
  void failsWhenReleasingOnTheMainThreadFails() {
    TestServer.reset();
    this.fill();
    final IllegalStateException broken = new IllegalStateException("entity already removed");
    doThrow(broken).when(this.hologram).kill();

    final Future<?> release = this.releaseOnTheWorker();

    final ExecutionException exception = assertThrows(ExecutionException.class, () -> release.get(10, TimeUnit.SECONDS));
    final Throwable cause = exception.getCause();
    final IllegalStateException failure = assertInstanceOf(IllegalStateException.class, cause);
    final String message = failure.getMessage();
    assertEquals("Failed to release the video on the main thread", message);
    final Throwable original = failure.getCause();
    assertSame(broken, original);
  }

  @Test
  void releasesTheAudioEvenWithoutAVideo() {
    this.manager.releaseVideoPlayer();
    this.manager.releaseVideoPlayer();

    verify(this.provider, times(2)).releaseAudioFilter();
  }

  @Test
  void runsTasksOnItsWorkerThread() throws ExecutionException, InterruptedException, TimeoutException {
    final ExecutorService service = this.manager.getService();
    final Thread caller = Thread.currentThread();

    final Future<Thread> worker = service.submit(Thread::currentThread);

    final Thread thread = worker.get(10, TimeUnit.SECONDS);
    final boolean differentThread = !thread.equals(caller);
    assertTrue(differentThread);
  }

  @Test
  void shutdownDrainsWorldCleanupWhoseScheduledTaskNeverRuns() throws InterruptedException, ExecutionException, TimeoutException {
    this.fill();
    final BlockingQueue<Runnable> tasks = interceptMainThreadTasks();
    final Future<?> release = this.releaseOnTheWorker();
    final Runnable accepted = awaitMainThreadTask(tasks);
    // Models Paper cancelling the accepted task during disable. The manager must retain and complete its cleanup.
    this.manager.shutdown();
    release.get(10, TimeUnit.SECONDS);
    verify(this.filter, times(1)).release();
    verify(this.hologram, times(1)).kill();
    accepted.run();
    verify(this.filter, times(1)).release();
    verify(this.hologram, times(1)).kill();
    this.assertWorldReleasedOnTheMainThreadAndPlayerOnTheWorker();
  }

  @Test
  void releasesTheOwnedDisplayWhenItsSchedulerRejectsStartup() {
    this.manager.beginStart();
    final BukkitScheduler scheduler = TestServer.scheduler();
    final IllegalPluginAccessException failure = new IllegalPluginAccessException("disabled");
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(failure);
    final IllegalPluginAccessException thrown = assertThrows(IllegalPluginAccessException.class, () -> this.manager.startFilter(this.filter)
    );
    assertSame(failure, thrown);
    verify(this.filter, times(1)).release();
    final FunctionalVideoFilter current = this.manager.getFilter();
    assertNull(current);
  }

  @Test
  void neverStartsAnAcceptedDisplayTaskAfterRelease() {
    TestServer.resetWithDeferredTasks();
    this.manager.beginStart();
    this.manager.startFilter(this.filter);
    this.manager.releaseVideoPlayer();
    TestServer.runPendingTasks();
    verify(this.filter, never()).start();
    verify(this.filter, times(1)).release();
  }

  @Test
  void rejectsAResolvedStartupAfterShutdown() {
    final long generation = this.manager.beginStart();
    this.manager.shutdown();
    assertThrows(CancellationException.class, this.manager::checkStart);
    assertThrows(CancellationException.class, this.manager::beginStart);
    final boolean current = this.manager.isCurrent(generation);
    assertFalse(current);
  }

  @Test
  void closesANativePlayerOnlyAfterCancelledStartupReturns() {
    this.manager.beginStart();
    this.manager.setPlayer(this.player);
    assertThrows(CancellationException.class, () ->
      this.manager.startNative(() -> {
          this.manager.releaseVideoPlayer();
          verify(this.player, never()).release();
          return true;
        })
    );
    verify(this.player, times(1)).release();
    final VideoPlayerMultiplexer current = this.manager.getPlayer();
    assertNull(current);
  }

  @Test
  void disposesAPlayerWhoseConstructionCompletedAfterCancellation() {
    this.manager.beginStart();
    final AtomicBoolean status = this.manager.getStatus();
    status.set(true);
    this.manager.cancelStart();
    assertThrows(CancellationException.class, () -> this.manager.setPlayer(this.player));
    verify(this.player, times(1)).release();
    final VideoPlayerMultiplexer current = this.manager.getPlayer();
    assertNull(current);
  }

  @Test
  void attemptsEveryRecoverableCleanupAndStopsTheWorker() {
    this.fill();
    final IllegalStateException audioFailure = new IllegalStateException("audio");
    final IllegalArgumentException playerFailure = new IllegalArgumentException("player");
    doThrow(audioFailure).when(this.provider).releaseAudioFilter();
    doThrow(playerFailure).when(this.player).release();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, this.manager::shutdown);
    assertSame(audioFailure, thrown);
    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(playerFailure, suppressed[0]);
    verify(this.filter).release();
    verify(this.hologram).kill();
    final ExecutorService executor = this.manager.getService();
    final boolean stopped = executor.isShutdown();
    assertTrue(stopped);
    this.manager.shutdown();
    verify(this.player, times(1)).release();
  }

  @Test
  void stopsItsWorkerAndReleasesWhenShutDown() {
    this.fill();

    this.manager.shutdown();

    verify(this.player).release();
    verify(this.hologram).kill();
    final ExecutorService service = this.manager.getService();
    final boolean shutdown = service.isShutdown();
    assertTrue(shutdown);
  }

  @Test
  void cancellingAndRestartingNeverRevivesAnEarlierStartupToken() {
    final long oldest = this.manager.beginStart();
    final long replaced = this.manager.beginStart();
    this.manager.cancelStart();
    assertFalse(this.manager.isCurrent(oldest));
    assertFalse(this.manager.isCurrent(replaced));
    final long current = this.manager.beginStart();
    assertFalse(this.manager.isCurrent(oldest));
    assertFalse(this.manager.isCurrent(replaced));
    assertTrue(this.manager.isCurrent(current));
    this.manager.checkStart();
  }

  @Test
  void startsOnlyTheCurrentOwnedDisplay() {
    final long generation = this.manager.beginStart();
    this.manager.checkStart();
    this.manager.startFilter(this.filter);
    TestServer.runPendingTasks();
    verify(this.filter).start();
    final boolean current = this.manager.isCurrent(generation);
    assertTrue(current);

    final FunctionalVideoFilter replaced = mock(FunctionalVideoFilter.class);
    this.manager.startFilter(replaced);
    this.manager.setFilter(this.filter);
    TestServer.runPendingTasks();
    verify(replaced, never()).start();
    this.manager.cancelStart();
    final boolean stale = this.manager.isCurrent(generation);
    assertFalse(stale);
  }

  @Test
  void releasesAFailedDisplayAndPreservesItsFailureOverCleanup() {
    this.manager.beginStart();
    final IllegalStateException failure = new IllegalStateException("display start");
    final IllegalArgumentException cleanup = new IllegalArgumentException("display release");
    doThrow(failure).when(this.filter).start();
    doThrow(cleanup).when(this.filter).release();
    this.manager.startFilter(this.filter);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, TestServer::runPendingTasks);
    assertSame(failure, thrown);
    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);
    verify(this.filter).release();
    final FunctionalVideoFilter current = this.manager.getFilter();
    assertNull(current);
  }

  @Test
  void preservesRejectedSchedulingFailureOverDisplayCleanup() {
    this.manager.beginStart();
    final IllegalPluginAccessException failure = new IllegalPluginAccessException("disabled");
    final IllegalStateException cleanup = new IllegalStateException("display release");
    final BukkitScheduler scheduler = TestServer.scheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(failure);
    doThrow(cleanup).when(this.filter).release();
    final IllegalPluginAccessException thrown = assertThrows(IllegalPluginAccessException.class, () -> this.manager.startFilter(this.filter)
    );
    assertSame(failure, thrown);
    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);
    final FunctionalVideoFilter current = this.manager.getFilter();
    assertNull(current);
  }

  @Test
  void retainsANativePlayerAfterCompletedStartupUntilExplicitRelease() {
    this.manager.beginStart();
    final AtomicBoolean status = this.manager.getStatus();
    status.set(true);
    this.manager.setPlayer(this.player);
    final boolean accepted = this.manager.startNative(() -> true);
    final boolean refused = this.manager.startNative(() -> false);
    assertTrue(accepted);
    assertFalse(refused);
    verify(this.player, never()).release();
    final VideoPlayerMultiplexer current = this.manager.getPlayer();
    assertSame(this.player, current);
    this.manager.releaseVideoPlayer();
    verify(this.player).release();
    status.set(false);
  }

  @Test
  void endsNativeOwnershipAfterARecoverableStartFailure() {
    this.manager.beginStart();
    this.manager.setPlayer(this.player);
    final IllegalStateException failure = new IllegalStateException("native start");
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.manager.startNative(() -> {
          throw failure;
        })
    );
    assertSame(failure, thrown);
    verify(this.player, never()).release();
    this.manager.releaseVideoPlayer();
    verify(this.player).release();
  }

  @Test
  void preservesDetachedNativeStartFailureAndSuppressesCleanupFailure() {
    this.manager.beginStart();
    this.manager.setPlayer(this.player);
    final IllegalStateException failure = new IllegalStateException("native start");
    final IllegalArgumentException cleanup = new IllegalArgumentException("native release");
    doThrow(cleanup).when(this.player).release();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.manager.startNative(() -> {
          this.manager.releaseVideoPlayer();
          throw failure;
        })
    );
    assertSame(failure, thrown);
    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);
    verify(this.player).release();
  }

  @Test
  void doesNotSuppressANativeFailureOntoItself() {
    this.manager.beginStart();
    this.manager.setPlayer(this.player);
    final IllegalStateException failure = new IllegalStateException("same native failure");
    doThrow(failure).when(this.player).release();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.manager.startNative(() -> {
          this.manager.releaseVideoPlayer();
          throw failure;
        })
    );
    assertSame(failure, thrown);
    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(0, suppressed.length);
  }

  @Test
  void propagatesFatalDetachedNativeCleanupImmediately() {
    this.manager.beginStart();
    this.manager.setPlayer(this.player);
    final IllegalStateException failure = new IllegalStateException("native start");
    final InternalError fatal = new InternalError("fatal native release");
    doThrow(fatal).when(this.player).release();
    final InternalError thrown = assertThrows(InternalError.class, () ->
      this.manager.startNative(() -> {
          this.manager.releaseVideoPlayer();
          throw failure;
        })
    );
    assertSame(fatal, thrown);
    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals(0, suppressed.length);
  }

  @Test
  void rejectsAndReleasesAPlayerPublishedAfterShutdown() {
    this.manager.shutdown();
    final CancellationException thrown = assertThrows(CancellationException.class, () -> this.manager.setPlayer(this.player));
    final String message = thrown.getMessage();
    assertEquals("The video startup was cancelled", message);
    verify(this.player).release();
    this.manager.setPlayer(null);
    final VideoPlayerMultiplexer current = this.manager.getPlayer();
    assertNull(current);
  }

  @Test
  void usesWorldCleanupAlreadyCompletedByMainThreadShutdown() throws Exception {
    this.fill();
    final CountDownLatch releasingAudio = new CountDownLatch(1);
    final CountDownLatch continueRelease = new CountDownLatch(1);
    final AtomicBoolean first = new AtomicBoolean(true);
    doAnswer(_ -> {
      if (first.compareAndSet(true, false)) {
        releasingAudio.countDown();
        final boolean ready = continueRelease.await(10, TimeUnit.SECONDS);
        assertTrue(ready);
      }
      return null;
    })
      .when(this.provider)
      .releaseAudioFilter();
    final CompletableFuture<Void> finished = new CompletableFuture<>();
    final Thread.Builder.OfVirtual builder = Thread.ofVirtual();
    final Thread worker = builder.start(() -> {
      try {
        this.manager.releaseVideoPlayer();
        finished.complete(null);
      } catch (final RuntimeException | Error failure) {
        finished.completeExceptionally(failure);
      }
    });
    try {
      final boolean entered = releasingAudio.await(10, TimeUnit.SECONDS);
      assertTrue(entered);
      this.manager.shutdown();
      verify(this.filter).release();
      verify(this.hologram).kill();
    } finally {
      continueRelease.countDown();
    }
    finished.get(10, TimeUnit.SECONDS);
    worker.join(10_000);
    final boolean alive = worker.isAlive();
    assertFalse(alive);
    final BukkitScheduler scheduler = TestServer.scheduler();
    verify(scheduler, never()).callSyncMethod(any(Plugin.class), any());
    verify(this.filter, times(1)).release();
    verify(this.hologram, times(1)).kill();
  }

  @Test
  void propagatesAFatalMainThreadCleanupCauseWithoutWrappingIt() throws Exception {
    this.fill();
    final InternalError fatal = new InternalError("fatal display cleanup");
    doThrow(fatal).when(this.filter).release();
    final BlockingQueue<Runnable> tasks = interceptMainThreadTasks();
    final Future<?> release = this.releaseOnTheWorker();
    final Runnable accepted = awaitMainThreadTask(tasks);
    accepted.run();
    final ExecutionException thrown = assertThrows(ExecutionException.class, () -> release.get(10, TimeUnit.SECONDS));
    final Throwable cause = thrown.getCause();
    assertSame(fatal, cause, "the worker must propagate the fatal cause, not convert it to a recoverable wrapper");
    verify(this.filter).release();
    verify(this.hologram, never()).kill();
  }

  @Test
  void propagatesFatalDisplayStartFailureWithoutReleasingVideoPlayer() {
    this.manager.beginStart();
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal sentinel");
    doThrow(fatal).when(this.filter).start();
    this.manager.startFilter(this.filter);
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, TestServer::runPendingTasks);
    assertSame(fatal, thrown);
    verify(this.filter, never()).release();
    assertSame(this.filter, this.manager.getFilter());
  }

  @Test
  void propagatesFatalSchedulingFailureWithoutClearingCurrentVideo() {
    this.manager.beginStart();
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal sentinel");
    final BukkitScheduler scheduler = TestServer.scheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(fatal);

    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.manager.startFilter(this.filter));
    assertSame(fatal, thrown);
    verify(this.filter, never()).release();
    assertSame(this.filter, this.manager.getFilter());
  }

  @Test
  void propagatesFatalNativeStartFailureWithoutFinishingNativeCandidate() {
    this.manager.beginStart();
    this.manager.setPlayer(this.player);
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal sentinel");
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () ->
      this.manager.startNative(() -> {
          this.manager.releaseVideoPlayer();
          throw fatal;
        })
    );
    assertSame(fatal, thrown);
    verify(this.player, never()).release();
  }

  @Test
  void startFilterWithoutBeginStartMustReject() {
    this.manager.cancelStart();
    final BukkitScheduler scheduler = TestServer.scheduler();
    final CancellationException thrown = assertThrows(CancellationException.class, () -> this.manager.startFilter(this.filter));
    assertEquals("The video startup was cancelled", thrown.getMessage());
    verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
    verify(this.filter, never()).start();
  }

  @Test
  void startNativeWithoutBeginStartMustReject() {
    this.manager.cancelStart();
    this.manager.setPlayer(this.player);
    final BooleanSupplier start = mock(BooleanSupplier.class);
    final CancellationException thrown = assertThrows(CancellationException.class, () -> this.manager.startNative(start));
    assertEquals("The video startup was cancelled", thrown.getMessage());
    verify(start, never()).getAsBoolean();
  }

  @Test
  void rejectsFilterStartAfterCancelledStartupWithoutNewBeginStart() {
    this.manager.beginStart();
    this.manager.cancelStart();
    final BukkitScheduler scheduler = TestServer.scheduler();
    final CancellationException thrown = assertThrows(CancellationException.class, () -> this.manager.startFilter(this.filter));
    assertEquals("The video startup was cancelled", thrown.getMessage());
    verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
    verify(this.filter, never()).start();
  }

  @Test
  void rejectsNativeStartAfterCancelledStartupWithoutNewBeginStart() {
    this.manager.beginStart();
    this.manager.cancelStart();
    this.manager.setPlayer(this.player);
    final BooleanSupplier start = mock(BooleanSupplier.class);
    final CancellationException thrown = assertThrows(CancellationException.class, () -> this.manager.startNative(start));
    assertEquals("The video startup was cancelled", thrown.getMessage());
    verify(start, never()).getAsBoolean();
  }
}
