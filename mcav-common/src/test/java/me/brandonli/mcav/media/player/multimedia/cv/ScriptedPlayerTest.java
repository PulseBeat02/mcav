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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.bytedeco.javacv.FrameGrabber;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

final class ScriptedPlayerTest {

  private static final String VIDEO_FILE_NAME = "video.mp4";
  private static final String AUDIO_FILE_NAME = "audio.m4a";

  private static final Source VIDEO = file(VIDEO_FILE_NAME);
  private static final Source AUDIO = file(AUDIO_FILE_NAME);
  private static final long TIMEOUT_MILLISECONDS = 10_000L;
  private static final Duration TIMEOUT = Duration.ofMillis(TIMEOUT_MILLISECONDS);
  private static final long FUTURE_TIMEOUT_SECONDS = 5L;
  private static final String EXECUTOR_THREAD = "the-given-executor";

  private static final int FRAME_WIDTH = 4;
  private static final int FRAME_HEIGHT = 2;

  private static final long FRAME_INTERVAL_MICROSECONDS = 33_333L;

  private static final long SEEK_POSITION_MILLISECONDS = 1_000L;

  private static Source file(final String name) {
    final Path path = Path.of(name);
    return FileSource.path(path);
  }

  private static ScriptedFrameGrabber endlessVideo() {
    final List<Object> script = new ArrayList<>();
    for (int index = 0; index < 300; index++) {
      final Object frame = ScriptedFrameGrabber.video(index * FRAME_INTERVAL_MICROSECONDS);
      script.add(frame);
    }

    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
    grabber.setLength(10_000_000L);
    return grabber;
  }

  private static ScriptedFrameGrabber sound() {
    final List<Object> script = new ArrayList<>();
    for (int index = 0; index < 3_000; index++) {
      final Object chunk = ScriptedFrameGrabber.audio(index * 10_000L);
      script.add(chunk);
    }

    final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
    grabber.setLength(30_000_000L);
    return grabber;
  }

  private static ScriptedFrameGrabber singleFrame() {
    final Object frame = ScriptedFrameGrabber.video(0L);
    return ScriptedFrameGrabber.of(frame);
  }

  private static void await(final String description, final BooleanSupplier condition) throws InterruptedException {
    Polling.awaitCondition(description, TIMEOUT, condition);
  }

  private static void awaitEnd(final AbstractVideoPlayerCv player) throws InterruptedException {
    await("playback finished", () -> !player.isPlaying());
  }

  private static <ResultType> ResultType resultOf(final CompletableFuture<ResultType> future) throws Exception {
    return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void shouldPlayAndStopAtTheEndOfTheMedia() throws Exception {
    final Supplier<ScriptedFrameGrabber> twoFrames = this::twoFrameVideo;
    final ScriptedPlayer player = new ScriptedPlayer(twoFrames);
    final VideoFilter counter = (_, _) -> true;
    final VideoPipelineStep step = VideoPipelineStep.of(counter);
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(step);

    final boolean started = player.start(VIDEO);
    awaitEnd(player);
    final long position = player.getPositionMilliseconds();
    final boolean released = player.release();
    final long positionAfterRelease = player.getPositionMilliseconds();
    final List<String> opened = player.getOpenedResources();
    final List<String> expected = List.of(VIDEO_FILE_NAME);
    assertTrue(started);
    assertEquals(33L, position);
    assertTrue(released);
    assertEquals(0L, positionAfterRelease);
    assertEquals(expected, opened);
  }

  private ScriptedFrameGrabber twoFrameVideo() {
    final Object first = ScriptedFrameGrabber.video(0L);
    final Object second = ScriptedFrameGrabber.video(FRAME_INTERVAL_MICROSECONDS);
    return ScriptedFrameGrabber.of(first, second);
  }

  @Test
  void shouldReportSourcesThatCannotBeOpenedAndCloseTheirGrabbers() {
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
  void shouldReportTheStartFailureEvenWhenTheGrabberCannotBeClosed() {
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
  void shouldCloseGrabbersThatCannotBeConfigured() {
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
    assertEquals("invalid option!", message);
  }

  @Test
  void shouldKeepPlayingTheCurrentSourceWhenTheNewOneCannotBeOpened() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    final List<String> reports = Collections.synchronizedList(new ArrayList<>());
    player.setExceptionHandler((message, _) -> reports.add(message));
    try {
      final boolean started = player.start(VIDEO);
      final ScriptedFrameGrabber playing = player.getLatestGrabber();
      player.failNextOpen(new IllegalStateException("unknown option!"));
      final boolean runtimeFailure = player.start(AUDIO);
      player.failNextOpen(new UnsatisfiedLinkError("natives are missing!"));
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
  void shouldOpenSeparateAudioOnlyForDifferentSources() throws Exception {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::singleFrame);
    player.start(VIDEO, VIDEO);
    awaitEnd(player);
    final List<String> openedForSameSource = player.getOpenedResources();
    final List<String> sameSource = List.copyOf(openedForSameSource);

    player.start(VIDEO, AUDIO);
    awaitEnd(player);
    final List<String> bothSources = player.getOpenedResources();
    player.release();

    final List<String> expectedSameSource = List.of(VIDEO_FILE_NAME);
    final List<String> expectedBothSources = List.of(VIDEO_FILE_NAME, VIDEO_FILE_NAME, AUDIO_FILE_NAME);
    assertEquals(expectedSameSource, sameSource);
    assertEquals(expectedBothSources, bothSources);
  }

  @Test
  void shouldPauseAndResumeOnlyWhenThatChangesSomething() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    final boolean pauseWithoutSession = player.pause();
    final boolean resumeWithoutSession = player.resume();
    final boolean seekWithoutSession = player.seek(SEEK_POSITION_MILLISECONDS);

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
  void shouldNeitherResumeNorPausePlaybackThatHasEnded() throws Exception {
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
  void shouldSeekByRestartingAtThePositionAndKeepThePausedState() throws Exception {
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
  void shouldRefuseToSeekMediaWithoutALength() {
    final Supplier<ScriptedFrameGrabber> endless = ScriptedPlayerTest::endlessVideo;
    final Supplier<ScriptedFrameGrabber> live = () -> this.removeLength(endless.get());
    final ScriptedPlayer player = new ScriptedPlayer(live);
    try {
      player.start(VIDEO);
      final boolean seeked = player.seek(SEEK_POSITION_MILLISECONDS);
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

  private ScriptedFrameGrabber removeLength(final ScriptedFrameGrabber grabber) {
    grabber.setLength(0L);
    return grabber;
  }

  @Test
  void shouldPlayFasterWithItsSoundResampledAndKeepTheSpeedWhenSeeking() throws Exception {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::sound);
    final List<Integer> chunks = new CopyOnWriteArrayList<>();
    final AudioFilter sizes = (samples, _) -> chunks.add(samples.remaining());
    player.getAudioAttachableCallback().attach(AudioPipelineStep.of(sizes));
    try {
      final boolean idle = player.setSpeed(2);
      player.start(VIDEO);
      await("sound at normal speed", () -> chunks.contains(1_920));
      final boolean faster = player.setSpeed(2);
      chunks.clear();
      await("sound twice as fast", () -> chunks.contains(960));
      final boolean seeked = player.seek(5_000L);
      final ScriptedFrameGrabber latest = player.getLatestGrabber();
      await("the decoder seeked", () -> latest.getRequestedTimestamp() == 5_000_000L);
      chunks.clear();
      await("sound after the seek", () -> !chunks.isEmpty());
      final double afterSeek = player.getSpeed();
      final boolean allFaster = chunks.stream().allMatch(size -> size == 960);
      player.start(VIDEO);
      final double newMedia = player.getSpeed();
      assertFalse(idle, "nothing plays yet");
      assertTrue(faster);
      assertTrue(seeked);
      assertEquals(2, afterSeek, "a seek keeps the speed");
      assertTrue(allFaster, "and the new session plays at it: " + chunks);
      assertEquals(1, newMedia, "new media plays at normal speed");
      assertThrows(IllegalArgumentException.class, () -> player.setSpeed(AbstractVideoPlayerCv.MINIMUM_SPEED - 0.01));
      assertThrows(IllegalArgumentException.class, () -> player.setSpeed(AbstractVideoPlayerCv.MAXIMUM_SPEED + 0.01));
      assertThrows(IllegalArgumentException.class, () -> player.setSpeed(Double.NaN));
    } finally {
      player.release();
    }
  }

  @Test
  void shouldLetAnotherThreadPauseThePlayerAfterTheSpeedChanged() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    try {
      final boolean idle = player.setSpeed(2);
      player.start(VIDEO);
      final boolean faster = player.setSpeed(2);
      final Duration timeout = Duration.ofSeconds(5);
      final boolean paused = assertTimeoutPreemptively(timeout, player::pause, "setting the speed lets go of the lock of the player");
      assertFalse(idle, "nothing plays yet");
      assertTrue(faster);
      assertTrue(paused, "the endless video still plays");
    } finally {
      player.release();
    }
  }

  @Test
  void shouldRefuseToChangeTheSpeedOfMediaWithoutALength() {
    final Supplier<ScriptedFrameGrabber> live = () -> this.removeLength(endlessVideo());
    final ScriptedPlayer player = new ScriptedPlayer(live);
    try {
      player.start(VIDEO);
      final boolean changed = player.setSpeed(AbstractVideoPlayerCv.MAXIMUM_SPEED);
      assertFalse(changed, "a live stream cannot be played ahead of itself");

      final double speed = player.getSpeed();
      assertEquals(1, speed);
    } finally {
      player.release();
    }
  }

  @Test
  void shouldScaleTheDecodedPicture() throws Exception {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::singleFrame);
    final Dimension size = Dimension.of(160, 90);
    final DimensionAttachableCallback dimension = player.getDimensionAttachableCallback();
    dimension.attach(size);
    final List<Dimension> delivered = new CopyOnWriteArrayList<>();
    final VideoFilter recording = (samples, _) -> this.recordSize(samples, delivered);
    final VideoPipelineStep step = VideoPipelineStep.of(recording);
    final VideoAttachableCallback video = player.getVideoAttachableCallback();
    video.attach(step);
    player.start(VIDEO);
    awaitEnd(player);

    final ScriptedFrameGrabber grabber = player.getLatestGrabber();
    final int width = grabber.getImageWidth();
    final int height = grabber.getImageHeight();
    player.release();
    assertEquals(160, width);
    assertEquals(90, height);
    assertEquals(List.of(size), delivered, "the pipeline must receive a scaled frame, not just decoder configuration");
  }

  private boolean recordSize(final ImageBuffer samples, final List<Dimension> delivered) {
    final int deliveredWidth = samples.getWidth();
    final int deliveredHeight = samples.getHeight();
    delivered.add(Dimension.of(deliveredWidth, deliveredHeight));

    return false;
  }

  @Test
  void shouldExposeItsCallbacksAndExceptionHandler() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    final BiConsumer<String, Throwable> handler = (_, _) -> {};
    final BiConsumer<String, Throwable> defaultHandler = player.getExceptionHandler();
    final IllegalStateException expected = new IllegalStateException("expected in this test!");
    defaultHandler.accept("logged by the default handler", expected);

    player.setExceptionHandler(handler);
    final BiConsumer<String, Throwable> current = player.getExceptionHandler();
    assertSame(handler, current);
    assertThrows(NullPointerException.class, () -> player.setExceptionHandler(null));
    assertThrows(NullPointerException.class, () -> player.start(null));
    assertThrows(NullPointerException.class, () -> player.start(null, AUDIO));
    assertThrows(NullPointerException.class, () -> player.start(VIDEO, null));
  }

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
  void shouldRunAsynchronousVariantsOnTheGivenExecutor() throws Exception {
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
  void shouldRunAsynchronousVariantsWithoutAnExecutorOnTheCommonPool() throws Exception {
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
  void shouldPreserveBothSuccessfulAndRejectedStartsOfEqualSources() {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    try {
      final boolean started = player.start(VIDEO, VIDEO);
      assertTrue(started);

      player.release();
      final boolean restarted = player.start(VIDEO, VIDEO);
      assertFalse(restarted);
    } finally {
      player.release();
    }
  }

  @Test
  void shouldAllowControlFromAnotherThreadAfterACompletedStart() throws Exception {
    final ScriptedPlayer player = new ScriptedPlayer(ScriptedPlayerTest::endlessVideo);
    try {
      final boolean started = player.start(VIDEO);
      assertTrue(started);

      final CompletableFuture<Boolean> pause = CompletableFuture.supplyAsync(player::pause);
      final boolean paused = pause.get(5L, TimeUnit.SECONDS);
      assertTrue(paused, "completed start must relinquish its reservation lock");
    } finally {
      player.release();
    }
  }

  @Test
  void shouldRejectNullArgumentsOfAsynchronousVariants() {
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

  private static ScriptedFrameGrabber stuckDecoder() {
    final Object delay = new ScriptedFrameGrabber.Delay(1_500L);
    return ScriptedFrameGrabber.of(delay);
  }

  private static Thread releasingThread(final ScriptedPlayer player, final AtomicLong releaseEnd) {
    return new Thread(() -> releaseAndRecordEnd(player, releaseEnd));
  }

  private static void releaseAndRecordEnd(final ScriptedPlayer player, final AtomicLong releaseEnd) {
    player.release();
    final long end = System.nanoTime();
    releaseEnd.set(end);
  }

  @Test
  void shouldWaitForTheThreadsWithoutBlockingOtherCallsOnRelease() throws Exception {
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
    releasing.join(TIMEOUT_MILLISECONDS);

    final long releasedAt = releaseEnd.get();
    final long releaseNanoseconds = releasedAt - releaseStart;
    final long pauseNanoseconds = pauseEnd - pauseStart;
    assertFalse(paused);

    final boolean releaseWaited = releaseNanoseconds > 1_000_000_000L;
    assertTrue(releaseWaited, "release waits for the stuck decoder, took " + releaseNanoseconds);

    final boolean pauseWasQuick = pauseNanoseconds < 500_000_000L;
    assertTrue(pauseWasQuick, "other calls do not wait for the release, pause took " + pauseNanoseconds);

    final boolean pausedDuringRelease = pauseEnd < releasedAt;
    assertTrue(pausedDuringRelease, "the pause ran while the release was still waiting");
  }

  @Test
  void shouldNotBeStartedAgainOnceReleased() {
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

  private static final class ScriptedPlayer extends AbstractVideoPlayerCv {

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
        throw new IllegalArgumentException("invalid option!");
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

    private void failNextOpen(final Throwable failure) {
      this.nextOpenFailure = failure;
    }

    private void failConfiguration() {
      this.configurationFails = true;
    }

    private void clearCallingThreads() {
      this.callingThreads.clear();
    }

    private ScriptedFrameGrabber getLatestGrabber() {
      synchronized (this.created) {
        return this.created.getLast();
      }
    }

    private List<String> getOpenedResources() {
      return this.openedResources;
    }

    private List<String> getCallingThreads() {
      return this.callingThreads;
    }
  }
}
