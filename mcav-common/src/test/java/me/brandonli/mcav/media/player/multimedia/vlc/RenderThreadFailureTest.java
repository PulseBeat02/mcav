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
package me.brandonli.mcav.media.player.multimedia.vlc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies natural failure exit, without a stop call hiding stale running state. */
final class RenderThreadFailureTest {

  @ParameterizedTest
  @ValueSource(ints = { 0, 1, 2, 3 })
  void everyAbnormalExitStopsAcceptingWorkBeforeCleanup(final int failureSite) throws Exception {
    final AssertionError failure = new AssertionError("render failure");
    final AtomicReference<Throwable> uncaught = new AtomicReference<>();
    final AtomicReference<Thread> worker = new AtomicReference<>();
    final CountDownLatch cleaned = new CountDownLatch(1);
    final AtomicBoolean acceptingDuringCleanup = new AtomicBoolean(true);
    final RenderThread thread = new RenderThread("render-abnormal-exit", _ -> {
      throw failure;
    });
    thread.start(
      () -> {
        final Thread current = Thread.currentThread();
        worker.set(current);
        current.setUncaughtExceptionHandler((_, thrown) -> uncaught.set(thrown));
        if (failureSite == 0) {
          throw failure;
        }
        if (failureSite == 1) {
          throw new IllegalStateException("handler will fail");
        }
        throw new InterruptedException("natural loop exit");
      },
      () -> {
        acceptingDuringCleanup.set(thread.isRunning());
        cleaned.countDown();
        if (failureSite == 3) {
          throw failure;
        }
      }
    );
    try {
      final boolean cleanupReached = cleaned.await(5, TimeUnit.SECONDS);
      assertTrue(cleanupReached);
      final Thread current = worker.get();
      assertNotNull(current);
      current.join(1_000L);
      final boolean alive = current.isAlive();
      final boolean running = thread.isRunning();
      final boolean accepted = acceptingDuringCleanup.get();
      assertFalse(alive);
      assertFalse(running, "a failed renderer must not continue accepting queued work");
      assertFalse(accepted, "running is cleared before cleanup, even when cleanup itself fails");
      if (failureSite != 2) {
        final Throwable escaped = uncaught.get();
        assertSame(failure, escaped);
      }
    } finally {
      thread.stop();
    }
  }
}
