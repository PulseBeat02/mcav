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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.frame.FrameSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Exercises release from callbacks and while callbacks are waiting. */
@Timeout(15)
final class ImagePlayerShutdownTest {

  private static FrameSource source() {
    return FrameSource.supplier(() -> new int[] { 0xFF102030 }, 1, 1, 0.1f);
  }

  private static void attach(final ImagePlayer player, final VideoFilter filter) {
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    final VideoPipelineStep step = VideoPipelineStep.of(filter);
    callback.attach(step);
  }

  @Test
  void callbackReleaseDoesNotJoinItsOwnThread() throws Exception {
    final ImagePlayer player = ImagePlayer.player();
    final CountDownLatch finished = new CountDownLatch(1);
    final AtomicReference<Thread> worker = new AtomicReference<>();
    final AtomicReference<Boolean> released = new AtomicReference<>();
    final AtomicReference<Long> elapsed = new AtomicReference<>();
    attach(player, (_, _) -> {
      final Thread current = Thread.currentThread();
      worker.set(current);
      final long before = System.nanoTime();
      released.set(player.release());
      elapsed.set(System.nanoTime() - before);
      finished.countDown();
      return false;
    });
    try {
      final boolean started = player.start(source());
      final boolean completed = finished.await(10, TimeUnit.SECONDS);
      assertTrue(started);
      assertTrue(completed);
      final Long releaseNanos = elapsed.get();
      final Boolean firstRelease = released.get();
      assertNotNull(releaseNanos);
      assertTrue(releaseNanos < 1_000_000_000L, "self release must not spend two seconds joining itself");
      assertEquals(true, firstRelease);
      final Thread thread = worker.get();
      assertNotNull(thread);
      thread.join(1_000L);
      final boolean alive = thread.isAlive();
      assertFalse(alive, "the self-released worker exits and releases its frame");
    } finally {
      player.release();
    }
  }

  @Test
  void externalReleaseAllowsAnInterruptedCallbackToReenterThePlayer() throws Exception {
    final ImagePlayer player = ImagePlayer.player();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch escape = new CountDownLatch(1);
    final CountDownLatch completed = new CountDownLatch(1);
    final AtomicReference<Thread> worker = new AtomicReference<>();
    final AtomicReference<Boolean> repeatedRelease = new AtomicReference<>();
    attach(player, (_, _) -> {
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
    });
    final AtomicReference<Boolean> firstRelease = new AtomicReference<>();
    final Thread releaser = new Thread(() -> firstRelease.set(player.release()), "image-external-release");
    releaser.setDaemon(true);
    try {
      final boolean started = player.start(source());
      final boolean processing = entered.await(10, TimeUnit.SECONDS);
      assertTrue(started);
      assertTrue(processing);
      releaser.start();
      releaser.join(1_000L);
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
}
