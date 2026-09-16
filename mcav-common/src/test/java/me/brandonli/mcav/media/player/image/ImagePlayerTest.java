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
package me.brandonli.mcav.media.player.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.frame.FrameSource;
import me.brandonli.mcav.media.source.frame.SampleSupplier;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ImagePlayer} and {@link ImagePlayerImpl}.
 */
final class ImagePlayerTest {

  private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration RESTART_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);

  private static FrameSource solidFrames(final int width, final int height, final float frameRate) {
    return FrameSource.supplier(
      () -> {
        final int[] pixels = new int[width * height];
        Arrays.fill(pixels, 0xFF102030);
        return pixels;
      },
      width,
      height,
      frameRate
    );
  }

  private static void attach(final ImagePlayer player, final VideoFilter filter) {
    final VideoPipelineStep step = VideoPipelineStep.of(filter);
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(step);
  }

  private static void awaitCount(final AtomicInteger counter, final int expected) throws InterruptedException {
    Polling.pollUntil(FRAME_TIMEOUT, () -> counter.get() >= expected);
    final int count = counter.get();
    assertTrue(count >= expected, "expected " + expected + " frames but got " + count);
  }

  private static double rate(final List<Long> times) {
    final List<Long> recorded = List.copyOf(times);
    final long first = recorded.getFirst();
    final long last = recorded.getLast();
    final double seconds = (last - first) / 1e9;
    final int gaps = recorded.size() - 1;
    return gaps / seconds;
  }

  private static long shortestGap(final List<Long> times) {
    final List<Long> recorded = List.copyOf(times);
    long shortest = Long.MAX_VALUE;
    for (int index = 1; index < recorded.size(); index++) {
      final long previous = recorded.get(index - 1);
      final long current = recorded.get(index);
      final long gap = current - previous;
      shortest = Math.min(shortest, gap);
    }
    return shortest;
  }

  /**
   * Takes 60 ms, longer than the frame period of a source with 100 frames per second, like a slow filter.
   */
  private static void workSlowly() {
    try {
      Thread.sleep(60);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    }
  }

  /**
   * Creates a filter that checks the size and frame rate of every frame, records when it arrived, and fails on the
   * fifth frame.
   */
  private static VideoFilter checkingFramesAndFailingOnce(final List<Long> times, final AtomicInteger consumed) {
    return (image, metadata) -> {
      final int width = image.getWidth();
      final float frameRate = metadata.getVideoFrameRate();
      assertEquals(16, width);
      assertEquals(50.0f, frameRate);
      final long now = System.nanoTime();
      times.add(now);
      final int count = consumed.incrementAndGet();
      if (count == 5) {
        throw new IllegalStateException("a failing filter is reported, not fatal");
      }
      return true;
    };
  }

  /**
   * Creates a source of 16 by 8 frames at 50 frames per second whose third frame has the wrong size.
   */
  private static FrameSource framesWithOneOfTheWrongSize() {
    final AtomicInteger produced = new AtomicInteger();
    return FrameSource.supplier(
      () -> {
        final int count = produced.incrementAndGet();
        return count == 3 ? new int[1] : new int[16 * 8];
      },
      16,
      8,
      50.0f
    );
  }

  /**
   * Creates a player that records the messages of its failures and runs {@link #checkingFramesAndFailingOnce}.
   */
  private static ImagePlayer checkingPlayer(final List<String> failures, final List<Long> times, final AtomicInteger consumed) {
    final ImagePlayer player = ImagePlayer.player();
    player.setExceptionHandler((message, _) -> failures.add(message));
    final VideoFilter checking = checkingFramesAndFailingOnce(times, consumed);
    attach(player, checking);
    return player;
  }

  @Test
  void deliversFramesAtTheFrameRateOfTheSource() throws InterruptedException {
    final AtomicInteger consumed = new AtomicInteger();
    final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    final FrameSource source = framesWithOneOfTheWrongSize();
    final List<Long> times = Collections.synchronizedList(new ArrayList<>());
    final ImagePlayer player = checkingPlayer(failures, times, consumed);

    final boolean started = player.start(source);
    final boolean startedTwice = player.start(source);
    awaitCount(consumed, 30);
    final boolean released = player.release();
    final boolean releasedTwice = player.release();
    final boolean startedAfterRelease = player.start(source);

    final int total = consumed.get();
    final double rate = rate(times);
    final List<String> expectedFailures = List.of("Failed to render a frame");
    assertTrue(started);
    assertFalse(startedTwice);
    assertTrue(released);
    assertFalse(releasedTwice);
    assertFalse(startedAfterRelease);
    assertTrue(total >= 30, "consumed " + total);
    assertTrue(rate > 35.0 && rate < 65.0, "frames are delivered at about 50 per second, got " + rate);
    assertEquals(expectedFailures, failures);
  }

  @Test
  void catchesUpWhenFiltersAreSlowerThanTheFrameRate() throws InterruptedException {
    final AtomicInteger consumed = new AtomicInteger();
    final List<Long> times = Collections.synchronizedList(new ArrayList<>());
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (_, _) -> {
      final long now = System.nanoTime();
      times.add(now);
      consumed.incrementAndGet();
      workSlowly();
      return true;
    });

    final FrameSource fast = solidFrames(4, 4, 100.0f);
    player.start(fast);
    awaitCount(consumed, 6);
    player.release();

    final long shortestGap = shortestGap(times);
    assertTrue(shortestGap >= 50_000_000L, "a slow filter limits the frame rate instead of catching up in bursts, gap " + shortestGap);
  }

  @Test
  void keepsTheInterruptWhenReleasedFromAnInterruptedThread() throws InterruptedException {
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (_, _) -> {
      final long end = System.nanoTime() + 200_000_000L;
      while (System.nanoTime() < end) {
        Thread.onSpinWait();
      }
      return true;
    });
    final FrameSource source = solidFrames(2, 2, 30.0f);
    player.start(source);
    Thread.sleep(50);

    final Thread currentThread = Thread.currentThread();
    currentThread.interrupt();
    final boolean released = player.release();
    final boolean interrupted = Thread.interrupted();
    assertTrue(released);
    assertTrue(interrupted);
  }

  /**
   * Creates a source of solid frames that records the thread asking it for its frame rate first, which is the thread
   * that starts the player.
   */
  private static FrameSource recordingStarter(final AtomicReference<String> starter) {
    final FrameSource delegate = solidFrames(2, 2, 30.0f);
    final FrameSource source = mock(FrameSource.class);
    when(source.getFrameWidth()).thenReturn(2);
    when(source.getFrameHeight()).thenReturn(2);
    when(source.supplyFrameSamples()).thenAnswer(_ -> delegate.supplyFrameSamples());
    when(source.getFrameRate()).thenAnswer(_ -> {
      final Thread current = Thread.currentThread();
      final String name = current.getName();
      starter.compareAndSet(null, name);
      return 30.0f;
    });
    return source;
  }

  /**
   * Checks that both players started, the first on the given executor and the second on the common pool.
   */
  private static void assertStartedOnTheirThreads(
    final boolean startedOnExecutor,
    final boolean startedOnCommonPool,
    final AtomicReference<String> executorStarter,
    final AtomicReference<String> poolStarter
  ) {
    final String executorThread = executorStarter.get();
    final String poolThread = poolStarter.get();
    final boolean poolThreadOfTheCommonPool = poolThread.startsWith("ForkJoinPool.commonPool");
    assertTrue(startedOnExecutor);
    assertTrue(startedOnCommonPool);
    assertEquals("the-given-executor", executorThread);
    assertTrue(poolThreadOfTheCommonPool, poolThread);
  }

  @Test
  void asynchronousStartsRunOnTheGivenExecutor() throws Exception {
    final ImagePlayer first = ImagePlayer.player();
    final ImagePlayer second = ImagePlayer.player();
    final ExecutorService executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "the-given-executor"));
    final AtomicReference<String> executorStarter = new AtomicReference<>();
    final AtomicReference<String> poolStarter = new AtomicReference<>();
    try {
      final FrameSource executorSource = recordingStarter(executorStarter);
      final FrameSource poolSource = recordingStarter(poolStarter);
      final CompletableFuture<Boolean> onExecutor = first.startAsync(executorSource, executor);
      final CompletableFuture<Boolean> onCommonPool = second.startAsync(poolSource);
      final boolean startedOnExecutor = onExecutor.get(5, TimeUnit.SECONDS);
      final boolean startedOnCommonPool = onCommonPool.get(5, TimeUnit.SECONDS);

      final FrameSource tiny = solidFrames(1, 1, 1.0f);
      assertStartedOnTheirThreads(startedOnExecutor, startedOnCommonPool, executorStarter, poolStarter);
      assertThrows(NullPointerException.class, () -> first.startAsync(null, executor));
      assertThrows(NullPointerException.class, () -> first.startAsync(tiny, null));
    } finally {
      first.release();
      second.release();
      executor.shutdownNow();
    }
  }

  @Test
  void exposesItsExceptionHandler() {
    final ImagePlayer player = ImagePlayer.player();
    final BiConsumer<String, Throwable> handler = (_, _) -> {};
    player.setExceptionHandler(handler);
    final BiConsumer<String, Throwable> current = player.getExceptionHandler();
    assertSame(handler, current);
    assertThrows(NullPointerException.class, () -> player.setExceptionHandler(null));
    assertThrows(NullPointerException.class, () -> player.start(null));

    final boolean released = player.release();
    assertTrue(released, "a player that never started can be released");
  }

  @Test
  void rejectsSourcesWithoutAPositiveFiniteFrameRate() {
    final ImagePlayer player = ImagePlayer.player();
    for (final float frameRate : new float[] { 0.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY }) {
      final FrameSource source = mock(FrameSource.class);
      when(source.getFrameRate()).thenReturn(frameRate);
      assertThrows(IllegalArgumentException.class, () -> player.start(source), "frame rate " + frameRate);
    }

    final boolean released = player.release();
    assertTrue(released);
  }

  @Test
  void reportsSourcesThatFailToStartAndCanBeStartedAgain() throws Exception {
    final FrameSource working = solidFrames(2, 2, 30.0f);
    final SampleSupplier supplier = working.supplyFrameSamples();
    final IllegalStateException failure = new IllegalStateException("the source is gone");
    final FrameSource source = mock(FrameSource.class);
    when(source.getFrameWidth()).thenReturn(2);
    when(source.getFrameHeight()).thenReturn(2);
    when(source.getFrameRate()).thenReturn(30.0f);
    when(source.supplyFrameSamples()).thenThrow(failure).thenReturn(supplier);
    final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger delivered = new AtomicInteger();
    final ImagePlayer player = ImagePlayer.player();
    player.setExceptionHandler((message, _) -> failures.add(message));
    attach(player, (_, _) -> delivered.incrementAndGet() > 0);
    try {
      final boolean started = player.start(source);
      final boolean restarted = Polling.pollUntil(RESTART_TIMEOUT, () -> player.start(source));
      awaitCount(delivered, 1);

      final List<String> expectedFailures = List.of("Failed to play the frame source");
      assertTrue(started);
      assertTrue(restarted, "the player stops when its source fails to start, so it can be started again");
      assertEquals(expectedFailures, failures);
    } finally {
      player.release();
    }
  }

  /**
   * Finds the frame thread of a player, which carries the name every player gives its thread and lives as long as the
   * player plays.
   *
   * @return the running frame thread, or null if none is alive
   */
  private static @Nullable Thread frameThread() {
    final Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
    final Set<Thread> threads = stackTraces.keySet();
    for (final Thread thread : threads) {
      final String name = thread.getName();
      final boolean matches = "mcav-image-player".equals(name);
      final boolean alive = thread.isAlive();
      if (matches && alive) {
        return thread;
      }
    }
    return null;
  }

  /**
   * Releases a player on another thread, which only returns once that thread could take the lock of the player.
   */
  private static void releaseOnAnotherThread(final ImagePlayer player) throws InterruptedException {
    final Thread probe = new Thread(player::release, "image-player-lock-probe");
    probe.setDaemon(true);
    probe.start();
    probe.join();
  }

  @Test
  void playsOnADaemonThreadSoItNeverKeepsTheJvmAlive() throws InterruptedException {
    final AtomicInteger consumed = new AtomicInteger();
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (_, _) -> consumed.incrementAndGet() > 0);
    final FrameSource source = solidFrames(2, 2, 50.0f);
    try {
      final boolean started = player.start(source);
      awaitCount(consumed, 1);
      final Thread worker = frameThread();
      assertTrue(started);
      assertNotNull(worker, "the player delivers its frames on a thread of its own");
      final boolean daemon = worker.isDaemon();
      assertTrue(daemon, "the frame thread never keeps the JVM alive");
    } finally {
      player.release();
    }
  }

  @Test
  void releaseWakesTheFrameThreadWaitsForItAndLeavesTheLockFree() throws InterruptedException {
    final AtomicInteger consumed = new AtomicInteger();
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (_, _) -> consumed.incrementAndGet() > 0);
    // one frame every ten seconds, so a release that never wakes the thread leaves it parked far beyond the two
    // seconds a release waits for it
    final FrameSource slow = solidFrames(2, 2, 0.1f);

    final boolean started = player.start(slow);
    awaitCount(consumed, 1);
    final Thread worker = frameThread();
    assertTrue(started);
    assertNotNull(worker);

    final boolean released = player.release();
    final boolean alive = worker.isAlive();
    assertTrue(released);
    assertFalse(alive, "a release wakes the frame thread and returns only once it ended");
    assertTimeoutPreemptively(LOCK_TIMEOUT, () -> releaseOnAnotherThread(player), "a released player leaves its lock free");
  }

  @Test
  void refreshesThePixelsOfTheBufferItReuses() throws InterruptedException {
    final int firstColor = 0xFF102030;
    final int secondColor = 0xFF405060;
    final AtomicInteger produced = new AtomicInteger();
    final FrameSource alternating = FrameSource.supplier(
      () -> {
        final int count = produced.incrementAndGet();
        final boolean even = count % 2 == 0;
        final int color = even ? secondColor : firstColor;
        final int[] pixels = new int[4];
        Arrays.fill(pixels, color);
        return pixels;
      },
      2,
      2,
      50.0f
    );
    final List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (image, _) -> {
      final int[] pixels = image.getPixels();
      final int pixel = pixels[0];
      seen.add(pixel);
      return true;
    });

    try {
      player.start(alternating);
      Polling.pollUntil(FRAME_TIMEOUT, () -> seen.contains(secondColor));
      final boolean sawFirst = seen.contains(firstColor);
      final boolean sawSecond = seen.contains(secondColor);
      assertTrue(sawFirst, "the first frame reaches the filters");
      assertTrue(sawSecond, "the buffer the player reuses is filled with the pixels of every new frame");
    } finally {
      player.release();
    }
  }

  @Test
  void skipsFramesOfTheWrongSizeWithoutDeliveringAnything() throws InterruptedException {
    final CountDownLatch requestedTwice = new CountDownLatch(2);
    final AtomicInteger delivered = new AtomicInteger();
    final FrameSource source = FrameSource.supplier(
      () -> {
        requestedTwice.countDown();
        return new int[1];
      },
      4,
      4,
      100.0f
    );
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (_, _) -> delivered.incrementAndGet() > 0);

    final boolean started = player.start(source);
    final boolean requested = requestedTwice.await(5, TimeUnit.SECONDS);
    final boolean released = player.release();
    final int deliveredFrames = delivered.get();
    assertTrue(started);
    assertTrue(requested, "the player keeps asking the source for frames");
    assertTrue(released);
    assertEquals(0, deliveredFrames, "frames that do not match the size of the source are skipped");
  }
}
