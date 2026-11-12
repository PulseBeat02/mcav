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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.frame.FrameSource;
import me.brandonli.mcav.media.source.frame.SampleSupplier;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

final class ImagePlayerTest {

  private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration RESTART_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);

  private static final long TIMEOUT_SECONDS = 5L;
  private static final long RENDER_TIMEOUT_SECONDS = 10L;

  private static final int SMALL_FRAME_SIZE = 2;
  private static final int LARGE_FRAME_SIZE = 4;

  private static final float FRAME_RATE = 30.0f;
  private static final float QUICK_FRAME_RATE = 50.0f;
  private static final float FAST_FRAME_RATE = 100.0f;

  private static FrameSource solidFrames(final int width, final int height, final float frameRate) {
    return FrameSource.supplier(() -> solidPixels(width, height), width, height, frameRate);
  }

  private static int[] solidPixels(final int width, final int height) {
    final int[] pixels = new int[width * height];
    Arrays.fill(pixels, 0xFF102030);
    return pixels;
  }

  private static void attach(final ImagePlayer player, final VideoFilter filter) {
    final VideoPipelineStep step = VideoPipelineStep.of(filter);
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(step);
  }

  private static void awaitCount(final AtomicInteger counter, final int expected) throws InterruptedException {
    Polling.pollUntil(FRAME_TIMEOUT, () -> counter.get() >= expected);
    final int count = counter.get();
    final boolean reached = count >= expected;
    assertTrue(reached, "expected %s frames but got %s".formatted(expected, count));
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

  private static void workSlowly() {
    try {
      Thread.sleep(60);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  private static VideoFilter checkingFramesAndFailingOnce(final List<Long> times, final AtomicInteger consumed) {
    return (image, metadata) -> checkFrameAndFailOnce(image, metadata, times, consumed);
  }

  private static boolean checkFrameAndFailOnce(
    final ImageBuffer image,
    final OriginalVideoMetadata metadata,
    final List<Long> times,
    final AtomicInteger consumed
  ) {
    final int width = image.getWidth();
    final float frameRate = metadata.getVideoFrameRate();
    assertEquals(16, width);
    assertEquals(QUICK_FRAME_RATE, frameRate);

    final long now = System.nanoTime();
    times.add(now);
    final int count = consumed.incrementAndGet();
    if (count == 5) {
      final String message = "a failing filter is reported, not fatal!";
      throw new IllegalStateException(message);
    }

    return true;
  }

  private static FrameSource framesWithOneOfTheWrongSize() {
    final AtomicInteger produced = new AtomicInteger();
    return FrameSource.supplier(() -> produceFrameOfTheWrongSizeThird(produced), 16, 8, QUICK_FRAME_RATE);
  }

  private static int[] produceFrameOfTheWrongSizeThird(final AtomicInteger produced) {
    final int count = produced.incrementAndGet();
    return count == 3 ? new int[1] : new int[16 * 8];
  }

  private static ImagePlayer checkingPlayer(final List<String> failures, final List<Long> times, final AtomicInteger consumed) {
    final ImagePlayer player = ImagePlayer.player();
    player.setExceptionHandler((message, _) -> failures.add(message));
    final VideoFilter checking = checkingFramesAndFailingOnce(times, consumed);
    attach(player, checking);
    return player;
  }

  @Test
  void shouldInvalidateTheLastDeliveredFrameOnRelease() throws Exception {
    final ImagePlayer player = ImagePlayer.player();
    final BlockingQueue<ImageBuffer> delivered = new LinkedBlockingQueue<>();
    attach(player, (image, _) -> this.offerFrame(delivered, image));
    final FrameSource source = solidFrames(SMALL_FRAME_SIZE, SMALL_FRAME_SIZE, FRAME_RATE);
    try {
      final boolean started = player.start(source);
      assertTrue(started);

      final ImageBuffer image = delivered.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertNotNull(image);

      player.release();
      assertThrows(IllegalStateException.class, image::getPixels);
    } finally {
      player.release();
    }
  }

  private boolean offerFrame(final BlockingQueue<ImageBuffer> delivered, final ImageBuffer image) {
    delivered.offer(image);

    return false;
  }

  @Test
  void shouldDeliverFramesAtTheFrameRateOfTheSource() throws InterruptedException {
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
    final List<String> expectedFailures = List.of("Failed to render a frame.");
    assertTrue(started);
    assertFalse(startedTwice);
    assertTrue(released);
    assertFalse(releasedTwice);
    assertFalse(startedAfterRelease);

    final boolean consumedEnough = total >= 30;
    assertTrue(consumedEnough, "consumed %s".formatted(total));

    final boolean rateNearFifty = rate > 35.0 && rate < 65.0;
    assertTrue(rateNearFifty, "frames are delivered at about 50 per second, got %s".formatted(rate));
    assertEquals(expectedFailures, failures);
  }

  @Test
  void shouldCatchUpWhenFiltersAreSlowerThanTheFrameRate() throws InterruptedException {
    final AtomicInteger consumed = new AtomicInteger();
    final List<Long> times = Collections.synchronizedList(new ArrayList<>());
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (_, _) -> this.recordSlowFrame(times, consumed));

    final FrameSource fast = solidFrames(LARGE_FRAME_SIZE, LARGE_FRAME_SIZE, FAST_FRAME_RATE);
    player.start(fast);
    awaitCount(consumed, 6);
    player.release();

    final long shortestGap = shortestGap(times);
    final boolean limited = shortestGap >= 50_000_000L;
    assertTrue(limited, "a slow filter limits the frame rate instead of catching up in bursts, gap %s".formatted(shortestGap));
  }

  private boolean recordSlowFrame(final List<Long> times, final AtomicInteger consumed) {
    final long now = System.nanoTime();
    times.add(now);
    consumed.incrementAndGet();
    workSlowly();

    return true;
  }

  @Test
  void shouldResetTheDeadlineBeforeTheNextFastFrameAfterOneSlowFrame() throws InterruptedException {
    final AtomicLong clock = new AtomicLong();
    final List<Long> starts = new ArrayList<>();
    final CountDownLatch finished = new CountDownLatch(1);
    final ImagePlayer player = new ThreadedImagePlayer(clock::get, clock::addAndGet);
    attach(player, (_, _) -> this.slowFirstFrameAndReleaseOnThird(clock, starts, player, finished));
    try {
      final boolean started = player.start(solidFrames(LARGE_FRAME_SIZE, LARGE_FRAME_SIZE, FAST_FRAME_RATE));
      assertTrue(started);

      final boolean rendered = finished.await(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(rendered, "three controlled frames are rendered");
      final List<Long> expected = List.of(0L, 60_000_000L, 70_000_000L);
      assertEquals(expected, starts);
    } finally {
      player.release();
    }
  }

  private boolean slowFirstFrameAndReleaseOnThird(
    final AtomicLong clock,
    final List<Long> starts,
    final ImagePlayer player,
    final CountDownLatch finished
  ) {
    starts.add(clock.get());

    if (starts.size() == 1) {
      clock.addAndGet(60_000_000L);
    } else if (starts.size() == 3) {
      player.release();
      finished.countDown();
    }

    return true;
  }

  @Test
  void shouldRenderTheNextFrameAtOnceWhenAFrameTakesItsWholePeriod() throws InterruptedException {
    final AtomicLong clock = new AtomicLong();
    final List<Long> parks = Collections.synchronizedList(new ArrayList<>());
    final LongConsumer recordPark = parks::add;
    final LongConsumer park = recordPark.andThen(clock::addAndGet);
    final AtomicInteger frames = new AtomicInteger();
    final CountDownLatch finished = new CountDownLatch(1);
    final ImagePlayer player = new ThreadedImagePlayer(clock::get, park);
    attach(player, (_, _) -> this.useWholePeriodOnceThenRelease(frames, clock, player, finished));
    try {
      final boolean started = player.start(solidFrames(LARGE_FRAME_SIZE, LARGE_FRAME_SIZE, FAST_FRAME_RATE));
      assertTrue(started);

      final boolean rendered = finished.await(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(rendered, "two controlled frames are rendered");

      player.release();

      final boolean parkedForNothing = parks.contains(0L);
      assertFalse(parkedForNothing, "a frame that used its whole period is followed without parking, parks %s".formatted(parks));
    } finally {
      player.release();
    }
  }

  private boolean useWholePeriodOnceThenRelease(
    final AtomicInteger frames,
    final AtomicLong clock,
    final ImagePlayer player,
    final CountDownLatch finished
  ) {
    if (frames.incrementAndGet() == 1) {
      clock.addAndGet(10_000_000L);
    } else {
      player.release();
      finished.countDown();
    }

    return true;
  }

  @Test
  void shouldKeepTheInterruptWhenReleasedFromAnInterruptedThread() throws InterruptedException {
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (_, _) -> this.spinForTwoHundredMilliseconds());
    final FrameSource source = solidFrames(SMALL_FRAME_SIZE, SMALL_FRAME_SIZE, FRAME_RATE);
    player.start(source);
    Thread.sleep(50);

    final Thread current = Thread.currentThread();
    current.interrupt();
    final boolean released = player.release();
    final boolean interrupted = Thread.interrupted();
    assertTrue(released);
    assertTrue(interrupted);
  }

  private boolean spinForTwoHundredMilliseconds() {
    final long end = System.nanoTime() + 200_000_000L;
    while (System.nanoTime() < end) {
      Thread.onSpinWait();
    }

    return true;
  }

  private static FrameSource recordingStarter(final AtomicReference<String> starter) {
    final FrameSource delegate = solidFrames(SMALL_FRAME_SIZE, SMALL_FRAME_SIZE, FRAME_RATE);
    final FrameSource source = mock(FrameSource.class);
    when(source.getFrameWidth()).thenReturn(SMALL_FRAME_SIZE);
    when(source.getFrameHeight()).thenReturn(SMALL_FRAME_SIZE);
    when(source.getSampleSupplier()).thenAnswer(_ -> delegate.getSampleSupplier());
    when(source.getFrameRate()).thenAnswer(_ -> recordStarterAndAnswerFrameRate(starter));
    return source;
  }

  private static float recordStarterAndAnswerFrameRate(final AtomicReference<String> starter) {
    final Thread current = Thread.currentThread();
    final String name = current.getName();
    starter.compareAndSet(null, name);

    return FRAME_RATE;
  }

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
  void shouldRunAsynchronousStartsOnTheGivenExecutor() throws Exception {
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
      final boolean startedOnExecutor = onExecutor.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      final boolean startedOnCommonPool = onCommonPool.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

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
  void shouldExposeItsExceptionHandler() {
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
  void shouldRejectSourcesWithoutAPositiveFiniteFrameRate() {
    final ImagePlayer player = ImagePlayer.player();
    for (final float frameRate : new float[] { 0.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY }) {
      final FrameSource source = mock(FrameSource.class);
      when(source.getFrameRate()).thenReturn(frameRate);
      assertThrows(IllegalArgumentException.class, () -> player.start(source), "frame rate %s".formatted(frameRate));
    }

    final boolean released = player.release();
    assertTrue(released);
  }

  @Test
  void shouldReportSourcesThatFailToStartAndCanBeStartedAgain() throws Exception {
    final FrameSource working = solidFrames(SMALL_FRAME_SIZE, SMALL_FRAME_SIZE, FRAME_RATE);
    final SampleSupplier supplier = working.getSampleSupplier();
    final IllegalStateException failure = new IllegalStateException("the source is gone!");
    final FrameSource source = mock(FrameSource.class);
    when(source.getFrameWidth()).thenReturn(SMALL_FRAME_SIZE);
    when(source.getFrameHeight()).thenReturn(SMALL_FRAME_SIZE);
    when(source.getFrameRate()).thenReturn(FRAME_RATE);
    when(source.getSampleSupplier()).thenThrow(failure).thenReturn(supplier);
    final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger delivered = new AtomicInteger();
    final ImagePlayer player = ImagePlayer.player();
    player.setExceptionHandler((message, _) -> failures.add(message));
    attach(player, (_, _) -> delivered.incrementAndGet() > 0);
    try {
      final boolean started = player.start(source);
      final boolean restarted = Polling.pollUntil(RESTART_TIMEOUT, () -> player.start(source));
      awaitCount(delivered, 1);

      final List<String> expectedFailures = List.of("Failed to play the frame source.");
      assertTrue(started);
      assertTrue(restarted, "the player stops when its source fails to start, so it can be started again");
      assertEquals(expectedFailures, failures);
    } finally {
      player.release();
    }
  }

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

  private static void releaseOnAnotherThread(final ImagePlayer player) throws InterruptedException {
    final Thread probe = new Thread(player::release, "image-player-lock-probe");
    probe.setDaemon(true);
    probe.start();
    probe.join();
  }

  @Test
  void shouldPlayOnADaemonThreadSoItNeverKeepsTheJvmAlive() throws InterruptedException {
    final AtomicInteger consumed = new AtomicInteger();
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (_, _) -> consumed.incrementAndGet() > 0);
    final FrameSource source = solidFrames(SMALL_FRAME_SIZE, SMALL_FRAME_SIZE, QUICK_FRAME_RATE);
    try {
      final boolean started = player.start(source);
      awaitCount(consumed, 1);
      final Thread worker = frameThread();
      assertTrue(started);
      assertNotNull(worker, "the player delivers its frames on a thread of its own");

      final boolean isDaemon = worker.isDaemon();
      assertTrue(isDaemon, "the frame thread never keeps the JVM alive");
    } finally {
      player.release();
    }
  }

  @Test
  void shouldWakeTheFrameThreadWaitForItAndLeaveTheLockFreeOnRelease() throws InterruptedException {
    final AtomicInteger consumed = new AtomicInteger();
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (_, _) -> consumed.incrementAndGet() > 0);
    final FrameSource slow = solidFrames(SMALL_FRAME_SIZE, SMALL_FRAME_SIZE, 0.1f);
    // the frame thread of an earlier test may still be ending, and frameThread() must find this player's thread
    Polling.awaitCondition("the earlier frame threads ended", LOCK_TIMEOUT, () -> frameThread() == null);

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
  void shouldRefreshThePixelsOfTheBufferItReuses() throws InterruptedException {
    final int firstColor = 0xFF102030;
    final int secondColor = 0xFF405060;
    final AtomicInteger produced = new AtomicInteger();
    final FrameSource alternating = FrameSource.supplier(
      () -> this.alternatePixels(produced, firstColor, secondColor),
      SMALL_FRAME_SIZE,
      SMALL_FRAME_SIZE,
      QUICK_FRAME_RATE
    );
    final List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (image, _) -> this.recordFirstPixel(seen, image));

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

  private int[] alternatePixels(final AtomicInteger produced, final int firstColor, final int secondColor) {
    final int count = produced.incrementAndGet();
    final boolean even = count % 2 == 0;
    final int color = even ? secondColor : firstColor;
    final int[] pixels = new int[4];
    Arrays.fill(pixels, color);
    return pixels;
  }

  private boolean recordFirstPixel(final List<Integer> seen, final ImageBuffer image) {
    final int[] pixels = image.getPixels();
    final int pixel = pixels[0];
    seen.add(pixel);

    return true;
  }

  @Test
  void shouldSkipFramesOfTheWrongSizeWithoutDeliveringAnything() throws InterruptedException {
    final CountDownLatch requestedTwice = new CountDownLatch(2);
    final AtomicInteger delivered = new AtomicInteger();
    final FrameSource source = FrameSource.supplier(
      () -> this.requestFrameOfTheWrongSize(requestedTwice),
      LARGE_FRAME_SIZE,
      LARGE_FRAME_SIZE,
      FAST_FRAME_RATE
    );
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (_, _) -> delivered.incrementAndGet() > 0);

    final boolean started = player.start(source);
    final boolean requested = requestedTwice.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean released = player.release();
    final int deliveredFrames = delivered.get();
    assertTrue(started);
    assertTrue(requested, "the player keeps asking the source for frames");
    assertTrue(released);
    assertEquals(0, deliveredFrames, "frames that do not match the size of the source are skipped");
  }

  private int[] requestFrameOfTheWrongSize(final CountDownLatch requestedTwice) {
    requestedTwice.countDown();

    return new int[1];
  }

  @Test
  void shouldReturnFromStartBeforeTheFirstFrameIsSupplied() throws InterruptedException {
    final CountDownLatch requested = new CountDownLatch(1);
    final CountDownLatch permitted = new CountDownLatch(1);
    final FrameSource source = FrameSource.supplier(
      () -> this.recordTheRequestAndAwaitPermission(requested, permitted),
      SMALL_FRAME_SIZE,
      SMALL_FRAME_SIZE,
      FRAME_RATE
    );
    final ImagePlayer player = ImagePlayer.player();
    try {
      final boolean started = assertTimeoutPreemptively(
        LOCK_TIMEOUT,
        () -> player.start(source),
        "start does not wait for the first frame"
      );
      final boolean asked = requested.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(started);
      assertTrue(asked, "the worker asks the source for the first frame on its own");
    } finally {
      permitted.countDown();
      player.release();
    }
  }

  private int[] recordTheRequestAndAwaitPermission(final CountDownLatch requested, final CountDownLatch permitted) {
    requested.countDown();
    try {
      permitted.await();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }

    return solidPixels(SMALL_FRAME_SIZE, SMALL_FRAME_SIZE);
  }

  @Test
  void shouldDeliverTheNextFramesToAPipelineAttachedWhilePlaying() throws InterruptedException {
    final AtomicInteger replacedFrames = new AtomicInteger();
    final AtomicInteger attachedFrames = new AtomicInteger();
    final FrameSource source = solidFrames(SMALL_FRAME_SIZE, SMALL_FRAME_SIZE, QUICK_FRAME_RATE);
    final ImagePlayer player = ImagePlayer.player();
    attach(player, (_, _) -> this.countFrame(replacedFrames));
    try {
      final boolean started = player.start(source);
      awaitCount(replacedFrames, 1);
      assertTrue(started);

      attach(player, (_, _) -> this.countFrame(attachedFrames));
      awaitCount(attachedFrames, 1);
      final int replacedTotal = replacedFrames.get();
      awaitCount(attachedFrames, 3);

      final int replacedTotalLater = replacedFrames.get();
      assertEquals(replacedTotal, replacedTotalLater, "the replaced pipeline receives no further frame");
    } finally {
      player.release();
    }
  }

  private boolean countFrame(final AtomicInteger frames) {
    frames.incrementAndGet();

    return true;
  }
}
