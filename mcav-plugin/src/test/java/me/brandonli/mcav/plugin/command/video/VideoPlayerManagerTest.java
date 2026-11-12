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
package me.brandonli.mcav.plugin.command.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import me.brandonli.mcav.media.player.multimedia.cv.AbstractVideoPlayerCv;
import me.brandonli.mcav.media.player.pipeline.filter.audio.VolumeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.PluginException;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.CaptureDevices;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

// the test thread is the main thread; the manager's real worker shows the world changes only there
final class VideoPlayerManagerTest {

  private static final int TIMEOUT_SECONDS = 10;

  private static final String CANCELLED_STARTUP_MESSAGE = "The video startup was cancelled!";

  private static final String PLUGIN_DISABLED_MESSAGE = "disabled!";

  private static final String DISPLAY_RELEASE_FAILURE_MESSAGE = "display release!";

  private static final String NATIVE_START_FAILURE_MESSAGE = "native start!";

  private static final String FATAL_FAILURE_MESSAGE = "fatal sentinel!";

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
    final Answer<Void> recordPlayerThread = this.recordThread(this.playerThreads);
    final Answer<Void> recordWorldThread = this.recordThread(this.worldThreads);
    doAnswer(recordPlayerThread).when(this.player).release();
    doAnswer(recordWorldThread).when(this.filter).release();
    doAnswer(recordWorldThread).when(this.hologram).kill();
  }

  @AfterEach
  void shutDown() {
    TestServer.runPendingTasks();
    this.manager.shutdown();
  }

  private Answer<Void> recordThread(final List<Thread> threads) {
    return _ -> this.recordCurrentThread(threads);
  }

  private @Nullable Void recordCurrentThread(final List<Thread> threads) {
    final Thread current = Thread.currentThread();
    threads.add(current);

    return null;
  }

  @Test
  void shouldRefuseANullPlugin() {
    assertThrows(NullPointerException.class, () -> new VideoPlayerManager(null));
  }

  @Test
  void shouldStartEmpty() {
    final Optional<VideoPlayerMultiplexer> currentPlayer = this.manager.getPlayer();
    final Optional<FunctionalVideoFilter> currentFilter = this.manager.getFilter();
    final Optional<Hologram> currentHologram = this.manager.getHologram();
    final Optional<Location> location = this.manager.getHologramLocation();
    final AtomicBoolean startingFlag = this.manager.getStarting();
    final boolean starting = startingFlag.get();

    assertEquals(Optional.empty(), currentPlayer);
    assertEquals(Optional.empty(), currentFilter);
    assertEquals(Optional.empty(), currentHologram);
    assertEquals(Optional.empty(), location);
    assertFalse(starting);
  }

  @Test
  void shouldRememberWhatItIsGiven() {
    this.fill();
    final Location location = new Location(null, 1.0, 2.0, 3.0);
    this.manager.setHologramLocation(location);

    final VideoPlayerMultiplexer currentPlayer = this.manager.getPlayer().orElseThrow();
    final FunctionalVideoFilter currentFilter = this.manager.getFilter().orElseThrow();
    final Hologram currentHologram = this.manager.getHologram().orElseThrow();
    final Location currentLocation = this.manager.getHologramLocation().orElseThrow();

    assertSame(this.player, currentPlayer);
    assertSame(this.filter, currentFilter);
    assertSame(this.hologram, currentHologram);
    assertSame(location, currentLocation);
  }

  private void fill() {
    this.manager.setPlayer(this.player);
    this.manager.setFilter(this.filter);
    this.manager.setHologram(this.hologram);
  }

  @Test
  void shouldAskTheLibraryWhetherVlcIsAvailable() {
    when(this.api.hasCapability(Capability.VLC)).thenReturn(true);
    final boolean supported = this.manager.isVlcSupported();
    assertTrue(supported);

    when(this.api.hasCapability(Capability.VLC)).thenReturn(false);
    final boolean unsupported = this.manager.isVlcSupported();
    assertFalse(unsupported);
  }

  @Test
  void shouldAskTheLibraryWhetherACapabilityIsStillBeingPrepared() {
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
  void shouldReleaseEverythingAtOnceOnTheMainThread() {
    this.fill();

    this.manager.releaseVideoPlayer();

    verify(this.provider).releaseAudioFilter();
    verify(this.player).release();
    verify(this.filter).release();
    verify(this.hologram).kill();

    final Thread main = Thread.currentThread();
    final List<Thread> expectedWorldThreads = List.of(main, main);
    assertEquals(expectedWorldThreads, this.worldThreads);

    final Optional<VideoPlayerMultiplexer> currentPlayer = this.manager.getPlayer();
    final Optional<FunctionalVideoFilter> currentFilter = this.manager.getFilter();
    final Optional<Hologram> currentHologram = this.manager.getHologram();
    assertEquals(Optional.empty(), currentPlayer);
    assertEquals(Optional.empty(), currentFilter);
    assertEquals(Optional.empty(), currentHologram);

    final int pending = TestServer.runPendingTasks();
    assertEquals(0, pending);
  }

  @Test
  void shouldReleaseTheWorldOnTheMainThreadAndWaitForItWhenCalledFromTheWorker()
    throws InterruptedException, ExecutionException, TimeoutException {
    this.fill();
    final BlockingQueue<Runnable> mainThreadTasks = this.interceptMainThreadTasks();

    final Future<?> release = this.releaseOnTheWorker();
    final Runnable worldTask = this.awaitMainThreadTask(mainThreadTasks);

    final boolean doneBeforeTick = release.isDone();
    assertFalse(doneBeforeTick);
    verify(this.player).release();
    verify(this.filter, never()).release();
    verify(this.hologram, never()).kill();

    final Optional<VideoPlayerMultiplexer> currentPlayer = this.manager.getPlayer();
    assertEquals(Optional.empty(), currentPlayer);

    worldTask.run();
    release.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean onlyOneTask = mainThreadTasks.isEmpty();
    assertTrue(onlyOneTask);
    this.assertWorldReleasedOnTheMainThreadAndPlayerOnTheWorker();
  }

  private Future<?> releaseOnTheWorker() {
    final ExecutorService service = this.manager.getService();
    return service.submit(this.manager::releaseVideoPlayer);
  }

  private BlockingQueue<Runnable> interceptMainThreadTasks() {
    final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    final BukkitScheduler scheduler = TestServer.getScheduler();
    when(scheduler.callSyncMethod(any(Plugin.class), any())).thenAnswer(invocation -> this.queueMainThreadTask(invocation, tasks));

    return tasks;
  }

  private FutureTask<?> queueMainThreadTask(final InvocationOnMock invocation, final BlockingQueue<Runnable> tasks) {
    final Callable<?> call = invocation.getArgument(1);
    final FutureTask<?> task = new FutureTask<>(call);
    tasks.add(task);

    return task;
  }

  private Runnable awaitMainThreadTask(final BlockingQueue<Runnable> tasks) throws InterruptedException {
    final Runnable task = tasks.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
  void shouldLeaveTheWorldToTheShutdownWhenThePluginIsAlreadyDisabled() throws InterruptedException, ExecutionException, TimeoutException {
    this.fill();
    final BukkitScheduler scheduler = TestServer.getScheduler();
    when(scheduler.callSyncMethod(any(Plugin.class), any())).thenThrow(new IllegalPluginAccessException(PLUGIN_DISABLED_MESSAGE));

    final Future<?> release = this.releaseOnTheWorker();
    release.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    verify(this.player).release();
    verify(this.provider).releaseAudioFilter();

    final boolean worldUntouched = this.worldThreads.isEmpty();
    assertTrue(worldUntouched);

    final Optional<Hologram> currentHologram = this.manager.getHologram();
    assertEquals(Optional.empty(), currentHologram);

    this.manager.shutdown();
    verify(this.filter).release();
    verify(this.hologram).kill();
    this.assertWorldReleasedOnTheMainThreadAndPlayerOnTheWorker();
  }

  @Test
  void shouldFailWhenInterruptedWhileWaitingForTheMainThread() throws InterruptedException {
    this.fill();
    final BlockingQueue<Runnable> mainThreadTasks = this.interceptMainThreadTasks();
    final Future<?> release = this.releaseOnTheWorker();
    this.awaitMainThreadTask(mainThreadTasks);

    final ExecutorService service = this.manager.getService();
    service.shutdownNow();

    final ExecutionException exception = assertThrows(ExecutionException.class, () -> release.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    final Throwable cause = exception.getCause();
    final PluginException failure = assertInstanceOf(PluginException.class, cause);

    final String message = failure.getMessage();
    assertEquals("Interrupted while releasing the video on the main thread!", message);

    final Throwable interruption = failure.getCause();
    assertInstanceOf(InterruptedException.class, interruption);
  }

  @Test
  void shouldKeepTheInterruptWhenInterruptedWhileWaitingForTheMainThread()
    throws InterruptedException, ExecutionException, TimeoutException {
    this.fill();
    final BlockingQueue<Runnable> mainThreadTasks = this.interceptMainThreadTasks();
    final AtomicBoolean keptInterrupt = new AtomicBoolean();
    final ExecutorService service = this.manager.getService();
    final Future<?> release = service.submit(() -> this.releaseAndRecordInterrupt(keptInterrupt));
    this.awaitMainThreadTask(mainThreadTasks);

    service.shutdownNow();

    release.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean restored = keptInterrupt.get();
    assertTrue(restored, "the interrupt is restored for the caller of the release");
  }

  private void releaseAndRecordInterrupt(final AtomicBoolean keptInterrupt) {
    try {
      this.manager.releaseVideoPlayer();
    } catch (final PluginException exception) {
      final boolean interrupted = Thread.interrupted();
      keptInterrupt.set(interrupted);
    }
  }

  @Test
  void shouldFailWhenReleasingOnTheMainThreadFails() {
    TestServer.reset();
    this.fill();
    final IllegalStateException broken = new IllegalStateException("entity already removed!");
    doThrow(broken).when(this.hologram).kill();

    final Future<?> release = this.releaseOnTheWorker();

    final ExecutionException exception = assertThrows(ExecutionException.class, () -> release.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    final Throwable cause = exception.getCause();
    final PluginException failure = assertInstanceOf(PluginException.class, cause);

    final String message = failure.getMessage();
    assertEquals("Failed to release the video on the main thread!", message);

    final Throwable original = failure.getCause();
    assertSame(broken, original);
  }

  @Test
  void shouldReleaseTheAudioEvenWithoutAVideo() {
    this.manager.releaseVideoPlayer();
    this.manager.releaseVideoPlayer();

    verify(this.provider, times(2)).releaseAudioFilter();
  }

  @Test
  void shouldNeverReleaseAPartTwiceAfterAFailedRelease() {
    this.fill();
    final IllegalStateException failure = new IllegalStateException("player release!");
    doThrow(failure).when(this.player).release();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, this.manager::releaseVideoPlayer);
    assertSame(failure, thrown);

    this.manager.releaseVideoPlayer();
    verify(this.player, times(1)).release();
    verify(this.filter, times(1)).release();
    verify(this.hologram, times(1)).kill();
  }

  @Test
  void shouldRunTasksOnItsWorkerThread() throws ExecutionException, InterruptedException, TimeoutException {
    final ExecutorService service = this.manager.getService();
    final Thread caller = Thread.currentThread();

    final Future<Thread> worker = service.submit(Thread::currentThread);

    final Thread thread = worker.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean differentThread = !thread.equals(caller);
    assertTrue(differentThread);
  }

  @Test
  void shouldDrainOnShutdownTheWorldCleanupWhoseScheduledTaskNeverRuns() throws InterruptedException, ExecutionException, TimeoutException {
    this.fill();
    final BlockingQueue<Runnable> tasks = this.interceptMainThreadTasks();
    final Future<?> release = this.releaseOnTheWorker();
    final Runnable accepted = this.awaitMainThreadTask(tasks);
    this.manager.shutdown();
    release.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    verify(this.filter, times(1)).release();
    verify(this.hologram, times(1)).kill();

    accepted.run();
    verify(this.filter, times(1)).release();
    verify(this.hologram, times(1)).kill();
    this.assertWorldReleasedOnTheMainThreadAndPlayerOnTheWorker();
  }

  @Test
  void shouldReleaseTheOwnedDisplayWhenItsSchedulerRejectsStartup() {
    this.manager.beginStart();
    final BukkitScheduler scheduler = TestServer.getScheduler();
    final IllegalPluginAccessException failure = new IllegalPluginAccessException(PLUGIN_DISABLED_MESSAGE);
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(failure);
    final IllegalPluginAccessException thrown = assertThrows(IllegalPluginAccessException.class, () ->
      this.manager.startFilter(this.filter)
    );
    assertSame(failure, thrown);
    verify(this.filter, times(1)).release();

    final Optional<FunctionalVideoFilter> current = this.manager.getFilter();
    assertEquals(Optional.empty(), current);
  }

  @Test
  void shouldNeverStartAnAcceptedDisplayTaskAfterRelease() {
    TestServer.resetWithDeferredTasks();
    this.manager.beginStart();
    this.manager.startFilter(this.filter);
    this.manager.releaseVideoPlayer();
    TestServer.runPendingTasks();
    verify(this.filter, never()).start();
    verify(this.filter, times(1)).release();
  }

  @Test
  void shouldRejectAResolvedStartupAfterShutdown() {
    final long generation = this.manager.beginStart();
    this.manager.shutdown();
    assertThrows(CancellationException.class, this.manager::checkStart);
    assertThrows(CancellationException.class, this.manager::beginStart);

    final boolean current = this.manager.isCurrent(generation);
    assertFalse(current);
  }

  @Test
  void shouldCloseANativePlayerOnlyAfterCancelledStartupReturns() {
    this.manager.beginStart();
    this.manager.setPlayer(this.player);
    assertThrows(CancellationException.class, this::startNativeReleasedDuringStartup);
    verify(this.player, times(1)).release();

    final Optional<VideoPlayerMultiplexer> current = this.manager.getPlayer();
    assertEquals(Optional.empty(), current);
  }

  private void startNativeReleasedDuringStartup() {
    this.manager.startNative(this::releaseDuringStartup);
  }

  private boolean releaseDuringStartup() {
    this.manager.releaseVideoPlayer();
    verify(this.player, never()).release();

    return true;
  }

  @Test
  void shouldDisposeAPlayerWhoseConstructionCompletedAfterCancellation() {
    this.manager.beginStart();
    final AtomicBoolean starting = this.manager.getStarting();
    starting.set(true);
    this.manager.cancelStart();
    assertThrows(CancellationException.class, () -> this.manager.setPlayer(this.player));
    verify(this.player, times(1)).release();

    final Optional<VideoPlayerMultiplexer> current = this.manager.getPlayer();
    assertEquals(Optional.empty(), current);
  }

  @Test
  void shouldAttemptEveryRecoverableCleanupAndStopTheWorker() {
    this.fill();
    final IllegalStateException audioFailure = new IllegalStateException("audio!");
    final IllegalArgumentException playerFailure = new IllegalArgumentException("player!");
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
  void shouldStopItsWorkerAndReleaseWhenShutDown() {
    this.fill();

    this.manager.shutdown();

    verify(this.player).release();
    verify(this.hologram).kill();

    final ExecutorService service = this.manager.getService();
    final boolean isShutdown = service.isShutdown();
    assertTrue(isShutdown);
  }

  @Test
  void shouldNeverReviveAnEarlierStartupTokenWhenCancellingAndRestarting() {
    final long oldest = this.manager.beginStart();
    final long replaced = this.manager.beginStart();
    this.manager.cancelStart();

    final boolean managerCurrent = this.manager.isCurrent(oldest);
    assertFalse(managerCurrent);

    final boolean secondCurrent = this.manager.isCurrent(replaced);
    assertFalse(secondCurrent);

    final long current = this.manager.beginStart();

    final boolean thirdCurrent = this.manager.isCurrent(oldest);
    assertFalse(thirdCurrent);

    final boolean fourthCurrent = this.manager.isCurrent(replaced);
    assertFalse(fourthCurrent);

    final boolean fifthCurrent = this.manager.isCurrent(current);
    assertTrue(fifthCurrent);

    this.manager.checkStart();
  }

  @Test
  void shouldStartOnlyTheCurrentOwnedDisplay() {
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
  void shouldReleaseAFailedDisplayAndPreserveItsFailureOverCleanup() {
    this.manager.beginStart();
    final IllegalStateException failure = new IllegalStateException("display start!");
    final IllegalArgumentException cleanup = new IllegalArgumentException(DISPLAY_RELEASE_FAILURE_MESSAGE);
    doThrow(failure).when(this.filter).start();
    doThrow(cleanup).when(this.filter).release();
    this.manager.startFilter(this.filter);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, TestServer::runPendingTasks);
    assertSame(failure, thrown);

    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);
    verify(this.filter).release();

    final Optional<FunctionalVideoFilter> current = this.manager.getFilter();
    assertEquals(Optional.empty(), current);
  }

  @Test
  void shouldPreserveRejectedSchedulingFailureOverDisplayCleanup() {
    this.manager.beginStart();
    final IllegalPluginAccessException failure = new IllegalPluginAccessException(PLUGIN_DISABLED_MESSAGE);
    final IllegalStateException cleanup = new IllegalStateException(DISPLAY_RELEASE_FAILURE_MESSAGE);
    final BukkitScheduler scheduler = TestServer.getScheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(failure);
    doThrow(cleanup).when(this.filter).release();
    final IllegalPluginAccessException thrown = assertThrows(IllegalPluginAccessException.class, () ->
      this.manager.startFilter(this.filter)
    );
    assertSame(failure, thrown);

    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);

    final Optional<FunctionalVideoFilter> current = this.manager.getFilter();
    assertEquals(Optional.empty(), current);
  }

  @Test
  void shouldRetainANativePlayerAfterCompletedStartupUntilExplicitRelease() {
    this.manager.beginStart();
    final AtomicBoolean starting = this.manager.getStarting();
    starting.set(true);
    this.manager.setPlayer(this.player);
    final boolean accepted = this.manager.startNative(() -> true);
    final boolean refused = this.manager.startNative(() -> false);
    assertTrue(accepted);
    assertFalse(refused);
    verify(this.player, never()).release();

    final VideoPlayerMultiplexer current = this.manager.getPlayer().orElseThrow();
    assertSame(this.player, current);

    this.manager.releaseVideoPlayer();
    verify(this.player).release();

    starting.set(false);
  }

  @Test
  void shouldEndNativeOwnershipAfterARecoverableStartFailure() {
    this.manager.beginStart();
    this.manager.setPlayer(this.player);
    final IllegalStateException failure = new IllegalStateException(NATIVE_START_FAILURE_MESSAGE);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.startFailingNative(failure));
    assertSame(failure, thrown);
    verify(this.player, never()).release();

    this.manager.releaseVideoPlayer();
    verify(this.player).release();
  }

  private void startFailingNative(final IllegalStateException failure) {
    this.manager.startNative(() -> this.throwFailure(failure));
  }

  private boolean throwFailure(final IllegalStateException failure) {
    throw failure;
  }

  @Test
  void shouldPreserveDetachedNativeStartFailureAndSuppressCleanupFailure() {
    this.manager.beginStart();
    this.manager.setPlayer(this.player);
    final IllegalStateException failure = new IllegalStateException(NATIVE_START_FAILURE_MESSAGE);
    final IllegalArgumentException cleanup = new IllegalArgumentException("native release!");
    doThrow(cleanup).when(this.player).release();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.startNativeReleasedMidStart(failure));
    assertSame(failure, thrown);

    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);
    verify(this.player).release();
  }

  private void startNativeReleasedMidStart(final IllegalStateException failure) {
    this.manager.startNative(() -> this.releaseAndThrow(failure));
  }

  private boolean releaseAndThrow(final IllegalStateException failure) {
    this.manager.releaseVideoPlayer();
    throw failure;
  }

  @Test
  void shouldNotSuppressANativeFailureOntoItself() {
    this.manager.beginStart();
    this.manager.setPlayer(this.player);
    final IllegalStateException failure = new IllegalStateException("same native failure!");
    doThrow(failure).when(this.player).release();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.startNativeReleasedMidStart(failure));
    assertSame(failure, thrown);

    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(0, suppressed.length);
  }

  @Test
  void shouldPropagateFatalDetachedNativeCleanupImmediately() {
    this.manager.beginStart();
    this.manager.setPlayer(this.player);
    final IllegalStateException failure = new IllegalStateException(NATIVE_START_FAILURE_MESSAGE);
    final InternalError fatal = new InternalError("fatal native release!");
    doThrow(fatal).when(this.player).release();
    final InternalError thrown = assertThrows(InternalError.class, () -> this.startNativeReleasedMidStart(failure));
    assertSame(fatal, thrown);

    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals(0, suppressed.length);
  }

  @Test
  void shouldRejectAndReleaseAPlayerPublishedAfterShutdown() {
    this.manager.shutdown();
    final CancellationException thrown = assertThrows(CancellationException.class, () -> this.manager.setPlayer(this.player));

    final String message = thrown.getMessage();
    assertEquals(CANCELLED_STARTUP_MESSAGE, message);
    verify(this.player).release();

    this.manager.setPlayer(null);
    final Optional<VideoPlayerMultiplexer> current = this.manager.getPlayer();
    assertEquals(Optional.empty(), current);
  }

  @Test
  void shouldUseWorldCleanupAlreadyCompletedByMainThreadShutdown() throws Exception {
    this.fill();
    final CountDownLatch releasingAudio = new CountDownLatch(1);
    final CountDownLatch continueRelease = new CountDownLatch(1);
    final AtomicBoolean first = new AtomicBoolean(true);
    doAnswer(_ -> this.pauseFirstAudioRelease(first, releasingAudio, continueRelease))
      .when(this.provider)
      .releaseAudioFilter();
    final CompletableFuture<Void> finished = new CompletableFuture<>();
    final Thread.Builder.OfVirtual builder = Thread.ofVirtual();
    final Thread worker = builder.start(() -> this.releaseAndComplete(finished));
    try {
      final boolean entered = releasingAudio.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(entered);

      this.manager.shutdown();
      verify(this.filter).release();
      verify(this.hologram).kill();
    } finally {
      continueRelease.countDown();
    }
    finished.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    worker.join(10_000);
    final boolean alive = worker.isAlive();
    assertFalse(alive);

    final BukkitScheduler scheduler = TestServer.getScheduler();
    verify(scheduler, never()).callSyncMethod(any(Plugin.class), any());
    verify(this.filter, times(1)).release();
    verify(this.hologram, times(1)).kill();
  }

  private @Nullable Object pauseFirstAudioRelease(
    final AtomicBoolean first,
    final CountDownLatch releasingAudio,
    final CountDownLatch continueRelease
  ) throws InterruptedException {
    if (first.compareAndSet(true, false)) {
      releasingAudio.countDown();
      final boolean ready = continueRelease.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(ready);
    }
    return null;
  }

  private void releaseAndComplete(final CompletableFuture<Void> finished) {
    try {
      this.manager.releaseVideoPlayer();
      finished.complete(null);
    } catch (final RuntimeException | Error exception) {
      finished.completeExceptionally(exception);
    }
  }

  @Test
  void shouldPropagateAFatalMainThreadCleanupCauseWithoutWrappingIt() throws Exception {
    this.fill();
    final InternalError fatal = new InternalError("fatal display cleanup!");
    doThrow(fatal).when(this.filter).release();
    final BlockingQueue<Runnable> tasks = this.interceptMainThreadTasks();
    final Future<?> release = this.releaseOnTheWorker();
    final Runnable accepted = this.awaitMainThreadTask(tasks);
    accepted.run();
    final ExecutionException thrown = assertThrows(ExecutionException.class, () -> release.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    final Throwable cause = thrown.getCause();
    assertSame(fatal, cause, "the worker must propagate the fatal cause, not convert it to a recoverable wrapper");
    verify(this.filter).release();
    verify(this.hologram, never()).kill();
  }

  @Test
  void shouldPropagateFatalDisplayStartFailureWithoutReleasingVideoPlayer() {
    this.manager.beginStart();
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    doThrow(fatal).when(this.filter).start();
    this.manager.startFilter(this.filter);
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, TestServer::runPendingTasks);
    assertSame(fatal, thrown);
    verify(this.filter, never()).release();

    final FunctionalVideoFilter filter = this.manager.getFilter().orElseThrow();
    assertSame(this.filter, filter);
  }

  @Test
  void shouldPropagateFatalSchedulingFailureWithoutClearingCurrentVideo() {
    this.manager.beginStart();
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    final BukkitScheduler scheduler = TestServer.getScheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(fatal);

    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.manager.startFilter(this.filter));
    assertSame(fatal, thrown);
    verify(this.filter, never()).release();

    final FunctionalVideoFilter filter = this.manager.getFilter().orElseThrow();
    assertSame(this.filter, filter);
  }

  @Test
  void shouldPropagateFatalNativeStartFailureWithoutFinishingNativeCandidate() {
    this.manager.beginStart();
    this.manager.setPlayer(this.player);
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.startNativeReleasedMidStartFatally(fatal));
    assertSame(fatal, thrown);
    verify(this.player, never()).release();
  }

  private void startNativeReleasedMidStartFatally(final OutOfMemoryError fatal) {
    this.manager.startNative(() -> this.releaseAndThrowFatal(fatal));
  }

  private boolean releaseAndThrowFatal(final OutOfMemoryError fatal) {
    this.manager.releaseVideoPlayer();
    throw fatal;
  }

  @Test
  void shouldRejectStartNativeWithoutAPlayer() {
    this.manager.beginStart();
    final BooleanSupplier start = mock(BooleanSupplier.class);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.manager.startNative(start));

    final String message = thrown.getMessage();
    assertEquals("No video player was configured!", message);

    verify(start, never()).getAsBoolean();
  }

  @Test
  void shouldRejectFilterStartAfterCancelledStartupWithoutNewBeginStart() {
    this.manager.beginStart();
    this.manager.cancelStart();
    final BukkitScheduler scheduler = TestServer.getScheduler();
    final CancellationException thrown = assertThrows(CancellationException.class, () -> this.manager.startFilter(this.filter));

    final String message = thrown.getMessage();
    assertEquals(CANCELLED_STARTUP_MESSAGE, message);

    verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
    verify(this.filter, never()).start();
  }

  @Test
  void shouldRejectNativeStartAfterCancelledStartupWithoutNewBeginStart() {
    this.manager.beginStart();
    this.manager.cancelStart();
    this.manager.setPlayer(this.player);
    final BooleanSupplier start = mock(BooleanSupplier.class);
    final CancellationException thrown = assertThrows(CancellationException.class, () -> this.manager.startNative(start));

    final String message = thrown.getMessage();
    assertEquals(CANCELLED_STARTUP_MESSAGE, message);

    verify(start, never()).getAsBoolean();
  }

  @Test
  void shouldKeepOneVolumeForEveryVideo() {
    final VolumeFilter volume = this.manager.getVolume();

    final VolumeFilter managerVolume = this.manager.getVolume();
    assertSame(volume, managerVolume);

    final double volumeVolume = volume.getVolume();
    assertEquals(VolumeFilter.UNCHANGED, volumeVolume);
  }

  @Test
  void shouldWatchForTheEndOnlyWhileLooping() {
    final BukkitTask task = this.stubLoopWatcher();
    final BukkitScheduler scheduler = Bukkit.getScheduler();

    final boolean looping = this.manager.isLooping();
    assertFalse(looping);

    this.manager.setLooping(true);
    this.manager.setLooping(true);

    final boolean managerLooping = this.manager.isLooping();
    assertTrue(managerLooping);

    verify(scheduler, times(1)).runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), eq(10L), eq(10L));

    this.manager.setLooping(false);
    this.manager.setLooping(false);

    final boolean secondLooping = this.manager.isLooping();
    assertFalse(secondLooping);

    verify(task, times(1)).cancel();

    this.manager.setLooping(true);
    this.manager.shutdown();
    verify(task, times(2)).cancel();
  }

  private BukkitTask stubLoopWatcher() {
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    final BukkitTask task = mock(BukkitTask.class);
    when(scheduler.runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), anyLong())).thenReturn(task);

    return task;
  }

  @Test
  void shouldPlayAgainOnlyADecodedVideoThatEndedWhileLooping() {
    this.stubLoopWatcher();
    final AbstractVideoPlayerCv decoded = mock(AbstractVideoPlayerCv.class);
    this.manager.setPlayer(decoded);
    this.manager.restartEnded();
    this.manager.setLooping(true);
    when(decoded.isPlaying()).thenReturn(true);
    this.manager.restartEnded();
    verify(decoded, never()).seek(anyLong());

    when(decoded.isPlaying()).thenReturn(false);
    this.manager.restartEnded();
    verify(decoded).seek(0L);

    this.manager.setPlayer(this.player);
    this.manager.restartEnded();
    verify(this.player, never()).seek(anyLong());

    this.manager.setPlayer(null);
    this.manager.restartEnded();
  }

  @Test
  void shouldRememberTheDevicesListedLast() {
    final List<CaptureDevices.Device> devices = this.manager.getDevices();
    assertEquals(List.of(), devices);

    final List<CaptureDevices.Device> listed = new ArrayList<>(List.of(CaptureDevices.Device.of(0, "camera")));
    this.manager.setDevices(listed);
    listed.clear();

    final List<CaptureDevices.Device> expected = List.of(CaptureDevices.Device.of(0, "camera"));
    final List<CaptureDevices.Device> managerDevices = this.manager.getDevices();
    assertEquals(expected, managerDevices);
  }
}
