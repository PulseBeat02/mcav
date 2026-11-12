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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.util.concurrent.Uninterruptibles;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.frame.FrameSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class ImagePlayerShutdownTest {

  private static final long CALLBACK_TIMEOUT_SECONDS = 10L;
  private static final long JOIN_TIMEOUT_MILLISECONDS = 1_000L;

  private static final long STOP_WAIT_NANOSECONDS = TimeUnit.SECONDS.toNanos(2);

  private static FrameSource source() {
    return FrameSource.supplier(() -> new int[] { 0xFF102030 }, 1, 1, 0.1f);
  }

  private static void attach(final ImagePlayer player, final VideoFilter filter) {
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final VideoPipelineStep step = VideoPipelineStep.of(filter);
    callback.attach(step);
  }

  @Test
  void shouldNotJoinItsOwnThreadOnACallbackRelease() throws Exception {
    final ImagePlayer player = ImagePlayer.player();
    final CountDownLatch finished = new CountDownLatch(1);
    final AtomicReference<Thread> worker = new AtomicReference<>();
    final AtomicReference<Boolean> released = new AtomicReference<>();
    final AtomicReference<Long> elapsed = new AtomicReference<>();
    attach(player, (_, _) -> this.releaseFromTheCallback(player, worker, released, elapsed, finished));
    try {
      final boolean started = player.start(source());
      final boolean completed = finished.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(started);
      assertTrue(completed);

      final Long releaseNanoseconds = elapsed.get();
      final Boolean firstRelease = released.get();
      assertNotNull(releaseNanoseconds);

      final boolean releasedQuickly = releaseNanoseconds < 1_000_000_000L;
      assertTrue(releasedQuickly, "self release must not spend two seconds joining itself");
      assertEquals(true, firstRelease);

      final Thread thread = worker.get();
      assertNotNull(thread);

      thread.join(JOIN_TIMEOUT_MILLISECONDS);
      final boolean alive = thread.isAlive();
      assertFalse(alive, "the self-released worker exits and releases its frame");
    } finally {
      player.release();
    }
  }

  private boolean releaseFromTheCallback(
    final ImagePlayer player,
    final AtomicReference<Thread> worker,
    final AtomicReference<Boolean> released,
    final AtomicReference<Long> elapsed,
    final CountDownLatch finished
  ) {
    final Thread current = Thread.currentThread();
    worker.set(current);
    final long before = System.nanoTime();
    released.set(player.release());
    elapsed.set(System.nanoTime() - before);
    finished.countDown();

    return false;
  }

  @Test
  void shouldLetAnInterruptedCallbackReenterThePlayerOnAnExternalRelease() throws Exception {
    final ImagePlayer player = ImagePlayer.player();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch escape = new CountDownLatch(1);
    final CountDownLatch completed = new CountDownLatch(1);
    final AtomicReference<Thread> worker = new AtomicReference<>();
    final AtomicReference<Boolean> repeatedRelease = new AtomicReference<>();
    attach(player, (_, _) -> this.awaitEscapeAndRelease(player, worker, entered, escape, repeatedRelease, completed));
    final AtomicReference<Boolean> firstRelease = new AtomicReference<>();
    final Thread releaser = new Thread(() -> firstRelease.set(player.release()), "image-external-release");
    releaser.setDaemon(true);
    try {
      final boolean started = player.start(source());
      final boolean processing = entered.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(started);
      assertTrue(processing);

      releaser.start();
      releaser.join(JOIN_TIMEOUT_MILLISECONDS);
      final boolean releaseBlocked = releaser.isAlive();
      final long unfinishedCallbacks = completed.getCount();
      assertFalse(releaseBlocked, "release must interrupt the callback and leave its lifecycle lock available");
      assertEquals(0L, unfinishedCallbacks, "the callback completes before external release returns");

      final Boolean first = firstRelease.get();
      final Boolean repeated = repeatedRelease.get();
      assertEquals(true, first);
      assertEquals(false, repeated);

      final Thread thread = worker.get();
      assertNotNull(thread);

      final boolean alive = thread.isAlive();
      assertFalse(alive);
    } finally {
      escape.countDown();
      player.release();
      releaser.join(3_000L);
      final Thread thread = worker.get();
      if (thread != null) {
        thread.join(3_000L);
      }
    }
  }

  private boolean awaitEscapeAndRelease(
    final ImagePlayer player,
    final AtomicReference<Thread> worker,
    final CountDownLatch entered,
    final CountDownLatch escape,
    final AtomicReference<Boolean> repeatedRelease,
    final CountDownLatch completed
  ) {
    final Thread current = Thread.currentThread();
    worker.set(current);
    entered.countDown();
    try {
      escape.await();
    } catch (final InterruptedException exception) {
      current.interrupt();
    }

    repeatedRelease.set(player.release());
    completed.countDown();

    return false;
  }

  @Test
  void shouldStopWaitingAfterTwoSecondsForACallbackThatIgnoresInterruption() throws Exception {
    final ImagePlayer player = ImagePlayer.player();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch escape = new CountDownLatch(1);
    final AtomicReference<Thread> worker = new AtomicReference<>();
    attach(player, (_, _) -> this.ignoreInterruptionUntilEscape(worker, entered, escape));
    try {
      final boolean started = player.start(source());
      final boolean processing = entered.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(started);
      assertTrue(processing);

      final long before = System.nanoTime();
      final boolean released = player.release();
      final long after = System.nanoTime();
      final long waited = after - before;
      final Thread thread = worker.get();
      assertNotNull(thread);

      final boolean outlived = thread.isAlive();
      assertTrue(released);
      assertTrue(outlived, "a callback that ignores interruption outlives the bounded wait");

      final boolean waitedTwoSeconds = waited >= STOP_WAIT_NANOSECONDS;
      final boolean stoppedWaiting = waited < TimeUnit.SECONDS.toNanos(CALLBACK_TIMEOUT_SECONDS);
      assertTrue(waitedTwoSeconds, "release waits two seconds for the callback, waited %s ns".formatted(waited));
      assertTrue(stoppedWaiting, "release stops waiting after two seconds, waited %s ns".formatted(waited));
    } finally {
      escape.countDown();
      player.release();
      final Thread thread = worker.get();
      if (thread != null) {
        thread.join(JOIN_TIMEOUT_MILLISECONDS);
      }
    }
  }

  private boolean ignoreInterruptionUntilEscape(
    final AtomicReference<Thread> worker,
    final CountDownLatch entered,
    final CountDownLatch escape
  ) {
    final Thread current = Thread.currentThread();
    worker.set(current);
    entered.countDown();
    Uninterruptibles.awaitUninterruptibly(escape);

    return false;
  }
}
