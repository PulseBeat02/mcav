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
package me.brandonli.mcav.media.player.multimedia.cv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.bytedeco.javacv.FrameGrabber;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests the control logic of {@link AbstractVideoPlayerCV} with scripted grabbers.
 */
final class ScriptedPlayerTest {

  private static final Source VIDEO = file("video.mp4");
  private static final Source AUDIO = file("audio.m4a");
  private static final long TIMEOUT_MILLIS = 10_000L;
  private static final Duration TIMEOUT = Duration.ofMillis(TIMEOUT_MILLIS);
  private static final long FUTURE_TIMEOUT_SECONDS = 5L;
  private static final String EXECUTOR_THREAD = "the-given-executor";

  private static Source file(final String name) {
    final Path path = Path.of(name);
    return FileSource.path(path);
  }

  private static ScriptedFrameGrabber endlessVideo() {
    final List<Object> script = new ArrayList<>();
    for (int index = 0; index < 300; index++) {
      final Object frame = ScriptedFrameGrabber.video(index * 33_333L);
      script.add(frame);
    }
    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(4, 2, false, script);
    grabber.setLength(10_000_000L);
    return grabber;
  }

  private static ScriptedFrameGrabber singleFrame() {
    final Object frame = ScriptedFrameGrabber.video(0L);
    return ScriptedFrameGrabber.of(frame);
  }

  private static void await(final String description, final BooleanSupplier condition) throws InterruptedException {
    Polling.awaitCondition(description, TIMEOUT, condition);
  }

  private static void awaitEnd(final AbstractVideoPlayerCV player) throws InterruptedException {
    await("playback finished", () -> !player.isPlaying());
  }

  private static <T> T resultOf(final CompletableFuture<T> future) throws Exception {
    return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void playsAndStopsAtTheEndOfTheMedia() throws Exception {
    final Supplier<ScriptedFrameGrabber> twoFrames = () -> {
      final Object first = ScriptedFrameGrabber.video(0L);
      final Object second = ScriptedFrameGrabber.video(33_333L);
      return ScriptedFrameGrabber.of(first, second);
    };
    final ScriptedPlayer player = new ScriptedPlayer(twoFrames);
    final VideoFilter counter = (_, _) -> true;
    final VideoPipelineStep step = VideoPipelineStep.of(counter);
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(step);

    final boolean started = player.start(VIDEO);
    awaitEnd(player);
    final long position = player.getPositionMillis();
    final boolean released = player.release();
    final long positionAfterRelease = player.getPositionMillis();
    final List<String> opened = player.getOpenedResources();
    final List<String> expected = List.of("video.mp4");
    assertTrue(started);
    assertEquals(33L, position);
    assertTrue(released);
    assertEquals(0L, positionAfterRelease);
    assertEquals(expected, opened);
  }

  @Test
  void reportsSourcesThatCannotBeOpenedAndClosesTheirGrabbers() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedFrameGrabber::failingToStart);
    final List<String> reports = Collections.synchronizedList(new ArrayList<>());
    player.setExceptionHandler((message, _) -> reports.add(message));

    final boolean started = player.start(VIDEO);
    final boolean playing = player.isPlaying();
    final ScriptedFrameGrabber grabber = player.getLatestGrabber();
    final boolean closed = grabber.isClosed();
    final List<String> expected = List.of("Failed to start playback of video.mp4");
    assertFalse(started);
    assertFalse(playing);
    assertEquals(expected, reports);
    assertTrue(closed, "a grabber that failed to start may hold native resources, so it is closed");
  }

  @Test
  void reportsTheStartFailureEvenWhenTheGrabberCannotBeClosed() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedFrameGrabber::failingToStartAndStop);
    final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
    player.setExceptionHandler((_, error) -> errors.add(error));

    final boolean started = player.start(VIDEO);
    final boolean playing = player.isPlaying();
    final int errorCount = errors.size();
    final Throwable error = errors.getFirst();
    final String message = error.getMessage();
    assertFalse(started);
    assertFalse(playing);
    assertEquals(1, errorCount);
    assertEquals("start failed on purpose", message, "the failure to close the grabber does not hide the reason");
  }

  @Test
  void closesGrabbersThatCannotBeConfigured() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
    player.setExceptionHandler((_, error) -> errors.add(error));
    player.failConfiguration();

    final boolean started = player.start(VIDEO);
    final ScriptedFrameGrabber grabber = player.getLatestGrabber();
    final boolean closed = grabber.isClosed();
    final Throwable error = errors.getFirst();
    final String message = error.getMessage();
    assertFalse(started);
    assertTrue(closed);
    assertEquals("invalid option", message);
  }

  @Test
  void keepsPlayingTheCurrentSourceWhenTheNewOneCannotBeOpened() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    final List<String> reports = Collections.synchronizedList(new ArrayList<>());
    player.setExceptionHandler((message, _) -> reports.add(message));
    try {
      final boolean started = player.start(VIDEO);
      final ScriptedFrameGrabber playing = player.getLatestGrabber();
      player.failNextOpen(new IllegalStateException("unknown option"));
      final boolean runtimeFailure = player.start(AUDIO);
      player.failNextOpen(new UnsatisfiedLinkError("natives are missing"));
      final boolean linkageFailure = player.start(AUDIO);

      final boolean stillPlaying = player.isPlaying();
      final boolean currentClosed = playing.isClosed();
      final List<String> expected = List.of("Failed to start playback of audio.m4a", "Failed to start playback of audio.m4a");
      assertTrue(started);
      assertFalse(runtimeFailure);
      assertFalse(linkageFailure);
      assertTrue(stillPlaying, "the current playback is only replaced once the new source is open");
      assertFalse(currentClosed);
      assertEquals(expected, reports);
    } finally {
      player.release();
    }
  }

  @Test
  void opensSeparateAudioOnlyForDifferentSources() throws Exception {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::singleFrame);
    player.start(VIDEO, VIDEO);
    awaitEnd(player);
    final List<String> openedForSameSource = player.getOpenedResources();
    final List<String> sameSource = List.copyOf(openedForSameSource);

    player.start(VIDEO, AUDIO);
    awaitEnd(player);
    final List<String> bothSources = player.getOpenedResources();
    player.release();

    final List<String> expectedSameSource = List.of("video.mp4");
    final List<String> expectedBothSources = List.of("video.mp4", "video.mp4", "audio.m4a");
    assertEquals(expectedSameSource, sameSource);
    assertEquals(expectedBothSources, bothSources);
  }

  @Test
  void pausesAndResumesOnlyWhenThatChangesSomething() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    final boolean pauseWithoutSession = player.pause();
    final boolean resumeWithoutSession = player.resume();
    final boolean seekWithoutSession = player.seek(1_000L);

    player.start(VIDEO);
    final boolean paused = player.pause();
    final boolean pausedAgain = player.pause();
    final boolean resumed = player.resume();
    final boolean resumedAgain = player.resume();
    player.release();

    assertFalse(pauseWithoutSession);
    assertFalse(resumeWithoutSession);
    assertFalse(seekWithoutSession);
    assertTrue(paused);
    assertFalse(pausedAgain);
    assertTrue(resumed);
    assertFalse(resumedAgain);
  }

  @Test
  void neitherResumesNorPausesPlaybackThatHasEnded() throws Exception {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::singleFrame);
    player.start(VIDEO);
    awaitEnd(player);

    final boolean pausedAfterEnd = player.pause();
    final boolean resumedAfterEnd = player.resume();
    final boolean playing = player.isPlaying();
    final List<String> opened = player.getOpenedResources();
    final int openCount = opened.size();
    player.release();

    assertFalse(pausedAfterEnd);
    assertFalse(resumedAfterEnd, "resuming continues a paused playback, it does not start the media over");
    assertFalse(playing);
    assertEquals(1, openCount);
  }

  @Test
  void seeksByRestartingAtThePositionAndKeepsThePausedState() throws Exception {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    player.start(VIDEO);
    player.pause();
    final boolean seeked = player.seek(2_500L);
    final ScriptedFrameGrabber latest = player.getLatestGrabber();
    await("the decoder seeked", () -> latest.getRequestedTimestamp() == 2_500_000L);

    final boolean pausedAfterSeek = player.pause();
    final boolean resumedAfterSeek = player.resume();
    player.release();
    assertTrue(seeked);
    assertFalse(pausedAfterSeek, "the new session is still paused");
    assertTrue(resumedAfterSeek);
    assertThrows(IllegalArgumentException.class, () -> player.seek(-1L));
  }

  @Test
  void refusesToSeekMediaWithoutALength() {
    final Supplier<ScriptedFrameGrabber> endless = ScriptedPlayerTest::endlessVideo;
    final Supplier<ScriptedFrameGrabber> live = () -> {
      final ScriptedFrameGrabber grabber = endless.get();
      grabber.setLength(0L);
      return grabber;
    };
    final ScriptedPlayer player = new ScriptedPlayer(live);
    try {
      player.start(VIDEO);
      final boolean seeked = player.seek(1_000L);
      final boolean playing = player.isPlaying();
      final List<String> opened = player.getOpenedResources();
      final int openCount = opened.size();
      assertFalse(seeked, "live streams and cameras cannot be seeked");
      assertTrue(playing, "the playback goes on");
      assertEquals(1, openCount, "the source is not opened again");
    } finally {
      player.release();
    }
  }

  @Test
  void scalesTheDecodedPicture() throws Exception {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::singleFrame);
    final Dimension size = Dimension.of(160, 90);
    final DimensionAttachableCallback dimension = player.getDimensionAttachableCallback();
    dimension.attach(size);
    player.start(VIDEO);
    awaitEnd(player);

    final ScriptedFrameGrabber grabber = player.getLatestGrabber();
    final int width = grabber.getImageWidth();
    final int height = grabber.getImageHeight();
    player.release();
    assertEquals(160, width);
    assertEquals(90, height);
  }

  @Test
  void exposesItsCallbacksAndExceptionHandler() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    final BiConsumer<String, Throwable> handler = (_, _) -> {};
    final BiConsumer<String, Throwable> defaultHandler = player.getExceptionHandler();
    final IllegalStateException expected = new IllegalStateException("expected in this test");
    defaultHandler.accept("logged by the default handler", expected);

    player.setExceptionHandler(handler);
    final BiConsumer<String, Throwable> current = player.getExceptionHandler();
    assertSame(handler, current);
    assertThrows(NullPointerException.class, () -> player.setExceptionHandler(null));
    assertThrows(NullPointerException.class, () -> player.start(null));
    assertThrows(NullPointerException.class, () -> player.start(null, AUDIO));
    assertThrows(NullPointerException.class, () -> player.start(VIDEO, null));
  }

  /**
   * Starts, pauses, resumes, seeks and restarts the player through the asynchronous variants on the executor, waiting
   * for every call before the next one.
   *
   * @return whether every call succeeded
   */
  private static boolean controlOnTheExecutor(final ScriptedPlayer player, final ExecutorService executor) throws Exception {
    final CompletableFuture<Boolean> startFuture = player.startAsync(VIDEO, executor);
    final boolean started = resultOf(startFuture);
    final CompletableFuture<Boolean> pauseFuture = player.pauseAsync(executor);
    final boolean paused = resultOf(pauseFuture);
    final CompletableFuture<Boolean> resumeFuture = player.resumeAsync(executor);
    final boolean resumed = resultOf(resumeFuture);
    final CompletableFuture<Boolean> seekFuture = player.seekAsync(executor, 100L);
    final boolean seeked = resultOf(seekFuture);
    final CompletableFuture<Boolean> restartFuture = player.startAsync(VIDEO, AUDIO, executor);
    final boolean restarted = resultOf(restartFuture);
    return started && paused && resumed && seeked && restarted;
  }

  @Test
  void asynchronousVariantsRunOnTheGivenExecutor() throws Exception {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    final ExecutorService executor = Executors.newSingleThreadExecutor(task -> new Thread(task, EXECUTOR_THREAD));
    try {
      final boolean everyCallSucceeded = controlOnTheExecutor(player, executor);
      final List<String> recordedThreads = player.getCallingThreads();
      final List<String> executorThreads = List.copyOf(recordedThreads);
      final CompletableFuture<Boolean> releaseFuture = player.releaseAsync(executor);
      final boolean released = resultOf(releaseFuture);
      final CompletableFuture<Boolean> releaseAgainFuture = player.releaseAsync();
      final boolean releasedAgain = resultOf(releaseAgainFuture);

      final boolean anyRecorded = !executorThreads.isEmpty();
      assertTrue(everyCallSucceeded);
      assertTrue(anyRecorded);
      for (final String thread : executorThreads) {
        assertEquals(EXECUTOR_THREAD, thread, "every call ran on the given executor: " + executorThreads);
      }
      assertTrue(released);
      assertFalse(releasedAgain, "a second release has nothing left to release");
    } finally {
      player.release();
      executor.shutdownNow();
    }
  }

  /**
   * Pauses, resumes, seeks and starts the player through the asynchronous variants without an executor, waiting for
   * every call before the next one.
   *
   * @return whether every call succeeded
   */
  private static boolean controlOnTheCommonPool(final ScriptedPlayer player) throws Exception {
    final CompletableFuture<Boolean> pauseFuture = player.pauseAsync();
    final boolean paused = resultOf(pauseFuture);
    final CompletableFuture<Boolean> resumeFuture = player.resumeAsync();
    final boolean resumed = resultOf(resumeFuture);
    final CompletableFuture<Boolean> seekFuture = player.seekAsync(0L);
    final boolean seeked = resultOf(seekFuture);
    final CompletableFuture<Boolean> startFuture = player.startAsync(VIDEO);
    final boolean started = resultOf(startFuture);
    final CompletableFuture<Boolean> startBothFuture = player.startAsync(VIDEO, AUDIO);
    final boolean startedBoth = resultOf(startBothFuture);
    return paused && resumed && seeked && started && startedBoth;
  }

  @Test
  void asynchronousVariantsWithoutAnExecutorRunOnTheCommonPool() throws Exception {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    try {
      player.start(VIDEO);
      player.clearCallingThreads();
      final boolean everyCallSucceeded = controlOnTheCommonPool(player);
      final List<String> recordedThreads = player.getCallingThreads();
      final List<String> commonPoolThreads = List.copyOf(recordedThreads);

      final boolean anyRecorded = !commonPoolThreads.isEmpty();
      assertTrue(everyCallSucceeded);
      assertTrue(anyRecorded);
      for (final String thread : commonPoolThreads) {
        final boolean onCommonPool = thread.startsWith("ForkJoinPool.commonPool");
        assertTrue(onCommonPool, "the calls without an executor ran on the common pool: " + thread);
      }
    } finally {
      player.release();
    }
  }

  @Test
  void asynchronousVariantsRejectNullArguments() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    try (final ExecutorService executor = Executors.newSingleThreadExecutor(task -> new Thread(task, EXECUTOR_THREAD))) {
      assertThrows(NullPointerException.class, () -> player.startAsync(null, executor));
      assertThrows(NullPointerException.class, () -> player.startAsync(VIDEO, (ExecutorService) null));
      assertThrows(NullPointerException.class, () -> player.startAsync(null, AUDIO, executor));
      assertThrows(NullPointerException.class, () -> player.startAsync(VIDEO, null, executor));
      assertThrows(NullPointerException.class, () -> player.startAsync(VIDEO, AUDIO, null));
      assertThrows(NullPointerException.class, () -> player.pauseAsync(null));
      assertThrows(NullPointerException.class, () -> player.resumeAsync(null));
      assertThrows(NullPointerException.class, () -> player.seekAsync(null, 0L));
      assertThrows(NullPointerException.class, () -> player.releaseAsync(null));
    } finally {
      player.release();
    }
  }

  /**
   * Creates a grabber whose decoder is stuck for a second and a half and does not react to interrupts.
   */
  private static ScriptedFrameGrabber stuckDecoder() {
    final Object delay = new ScriptedFrameGrabber.Delay(1_500L);
    return ScriptedFrameGrabber.of(delay);
  }

  /**
   * Creates a thread that releases the player and records when the release returned.
   */
  private static Thread releasingThread(final ScriptedPlayer player, final AtomicLong releaseEnd) {
    return new Thread(() -> {
      player.release();
      final long end = System.nanoTime();
      releaseEnd.set(end);
    });
  }

  @Test
  void releaseWaitsForTheThreadsWithoutBlockingOtherCalls() throws Exception {
    final Supplier<ScriptedFrameGrabber> stuck = ScriptedPlayerTest::stuckDecoder;
    final ScriptedPlayer player = new ScriptedPlayer(stuck);
    player.start(VIDEO);
    final AtomicLong releaseEnd = new AtomicLong();
    final Thread releasing = releasingThread(player, releaseEnd);

    final long releaseStart = System.nanoTime();
    releasing.start();
    await("the release took the playback", () -> !player.isPlaying());
    final long pauseStart = System.nanoTime();
    final boolean paused = player.pause();
    final long pauseEnd = System.nanoTime();
    releasing.join(TIMEOUT_MILLIS);

    final long releasedAt = releaseEnd.get();
    final long releaseNanos = releasedAt - releaseStart;
    final long pauseNanos = pauseEnd - pauseStart;
    assertFalse(paused);
    assertTrue(releaseNanos > 1_000_000_000L, "release waits for the stuck decoder, took " + releaseNanos);
    assertTrue(pauseNanos < 500_000_000L, "other calls do not wait for the release, pause took " + pauseNanos);
    assertTrue(pauseEnd < releasedAt, "the pause ran while the release was still waiting");
  }

  @Test
  void cannotBeStartedAgainOnceReleased() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    final boolean firstRelease = player.release();
    final boolean secondRelease = player.release();
    final boolean started = player.start(VIDEO);
    final boolean startedBoth = player.start(VIDEO, AUDIO);
    final List<String> opened = player.getOpenedResources();
    final boolean nothingOpened = opened.isEmpty();
    final boolean playing = player.isPlaying();
    assertTrue(firstRelease);
    assertFalse(secondRelease, "a second release has nothing left to release");
    assertFalse(started);
    assertFalse(startedBoth);
    assertTrue(nothingOpened, opened::toString);
    assertFalse(playing);
  }

  /**
   * A player whose grabbers come from a supplier and that remembers which resources it opened and on which threads
   * it was called.
   */
  private static final class ScriptedPlayer extends AbstractVideoPlayerCV {

    private final Supplier<ScriptedFrameGrabber> grabbers;
    private final List<String> openedResources;
    private final List<String> callingThreads;
    private final Deque<ScriptedFrameGrabber> created;
    private volatile @Nullable Throwable nextOpenFailure;
    private volatile boolean configurationFails;

    ScriptedPlayer(final Supplier<ScriptedFrameGrabber> grabbers) {
      this.grabbers = grabbers;
      this.openedResources = Collections.synchronizedList(new ArrayList<>());
      this.callingThreads = Collections.synchronizedList(new ArrayList<>());
      this.created = new ArrayDeque<>();
    }

    private void recordCallingThread() {
      final Thread current = Thread.currentThread();
      final String name = current.getName();
      // separate audio sources are opened by the audio decoding thread of the session, not by the caller
      if (!name.startsWith("mcav-")) {
        this.callingThreads.add(name);
      }
    }

    @Override
    protected FrameGrabber createFrameGrabber(final String resource) {
      this.recordCallingThread();
      final Throwable failure = this.nextOpenFailure;
      this.nextOpenFailure = null;
      if (failure instanceof final RuntimeException exception) {
        throw exception;
      }
      if (failure instanceof final Error error) {
        throw error;
      }

      this.openedResources.add(resource);
      final ScriptedFrameGrabber grabber = this.grabbers.get();
      synchronized (this.created) {
        this.created.addLast(grabber);
      }
      return grabber;
    }

    @Override
    protected void configureGrabber(final FrameGrabber grabber, final Source source) {
      super.configureGrabber(grabber, source);
      if (this.configurationFails) {
        throw new IllegalArgumentException("invalid option");
      }
    }

    @Override
    public boolean pause() {
      this.recordCallingThread();
      return super.pause();
    }

    @Override
    public boolean resume() {
      this.recordCallingThread();
      return super.resume();
    }

    void failNextOpen(final Throwable failure) {
      this.nextOpenFailure = failure;
    }

    void failConfiguration() {
      this.configurationFails = true;
    }

    List<String> getOpenedResources() {
      return this.openedResources;
    }

    List<String> getCallingThreads() {
      return this.callingThreads;
    }

    void clearCallingThreads() {
      this.callingThreads.clear();
    }

    ScriptedFrameGrabber getLatestGrabber() {
      synchronized (this.created) {
        return this.created.getLast();
      }
    }
  }
}
