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
package me.brandonli.mcav.capability.installer.vlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link VLCLoadState}.
 */
final class VLCLoadStateTest {

  private static final Path LIBRARY_DIRECTORY = Path.of("vlc", "lib");
  private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(10);

  @Test
  void runsTheLoaderOnlyUntilItSucceeds() throws IOException {
    final VLCLoadState state = new VLCLoadState();
    final AtomicInteger calls = new AtomicInteger();
    final VLCLoadState.Loader loader = () -> {
      calls.incrementAndGet();
      return Optional.of(LIBRARY_DIRECTORY);
    };
    final Optional<Path> first = state.loadOnce(loader);
    final Optional<Path> second = state.loadOnce(loader);
    final int callCount = calls.get();
    final Optional<Path> expected = Optional.of(LIBRARY_DIRECTORY);
    assertEquals(expected, first);
    assertEquals(expected, second);
    assertEquals(1, callCount);
  }

  @Test
  void remembersThatTheLocationIsUnknown() throws IOException {
    final VLCLoadState state = new VLCLoadState();
    final AtomicInteger calls = new AtomicInteger();
    final VLCLoadState.Loader loader = () -> {
      calls.incrementAndGet();
      return Optional.empty();
    };
    final Optional<Path> first = state.loadOnce(loader);
    final Optional<Path> second = state.loadOnce(() -> Optional.of(LIBRARY_DIRECTORY));
    final boolean firstEmpty = first.isEmpty();
    final boolean secondEmpty = second.isEmpty();
    final int callCount = calls.get();
    assertTrue(firstEmpty);
    assertTrue(secondEmpty);
    assertEquals(1, callCount);
  }

  @Test
  void retriesAfterAFailedLoad() throws IOException {
    final VLCLoadState state = new VLCLoadState();
    final IOException failure = new IOException("offline");
    final IOException thrown = assertThrows(IOException.class, () ->
      state.loadOnce(() -> {
        throw failure;
      })
    );
    assertSame(failure, thrown);
    final Optional<Path> retried = state.loadOnce(() -> Optional.of(LIBRARY_DIRECTORY));
    final Optional<Path> expected = Optional.of(LIBRARY_DIRECTORY);
    assertEquals(expected, retried);
  }

  @Test
  void retriesAfterAnUnsupportedSystem() throws IOException {
    final VLCLoadState state = new VLCLoadState();
    final UnsupportedOperatingSystemException failure = new UnsupportedOperatingSystemException("no VLC");
    assertThrows(UnsupportedOperatingSystemException.class, () ->
      state.loadOnce(() -> {
        throw failure;
      })
    );
    final Optional<Path> retried = state.loadOnce(() -> Optional.of(LIBRARY_DIRECTORY));
    final Optional<Path> expected = Optional.of(LIBRARY_DIRECTORY);
    assertEquals(expected, retried);
  }

  @Test
  void serializesConcurrentLoads() throws Exception {
    final VLCLoadState state = new VLCLoadState();
    final AtomicInteger calls = new AtomicInteger();
    final CountDownLatch loaderEntered = new CountDownLatch(1);
    final CountDownLatch releaseLoader = new CountDownLatch(1);
    final VLCLoadState.Loader slowLoader = slowLoader(calls, loaderEntered, releaseLoader);

    try (final ExecutorService executor = Executors.newFixedThreadPool(2)) {
      final Future<Optional<Path>> first = executor.submit(() -> state.loadOnce(slowLoader));
      final boolean entered = loaderEntered.await(5, TimeUnit.SECONDS);
      assertTrue(entered);
      final AtomicReference<Thread> secondThread = new AtomicReference<>();
      final Future<Optional<Path>> second = executor.submit(() -> loadRecordingThread(state, slowLoader, secondThread));
      assertSecondLoadWaits(secondThread, second);
      releaseLoader.countDown();
      assertLoaded(first);
      assertLoaded(second);
    }

    final int callCount = calls.get();
    assertEquals(1, callCount);
  }

  private static VLCLoadState.Loader slowLoader(final AtomicInteger calls, final CountDownLatch entered, final CountDownLatch release) {
    return () -> {
      calls.incrementAndGet();
      entered.countDown();
      awaitInLoader(release);
      return Optional.of(LIBRARY_DIRECTORY);
    };
  }

  private static Optional<Path> loadRecordingThread(
    final VLCLoadState state,
    final VLCLoadState.Loader loader,
    final AtomicReference<Thread> threadReference
  ) throws IOException {
    final Thread currentThread = Thread.currentThread();
    threadReference.set(currentThread);
    return state.loadOnce(loader);
  }

  private static void assertSecondLoadWaits(final AtomicReference<Thread> threadReference, final Future<Optional<Path>> second) {
    final boolean waited = awaitBlocked(threadReference);
    final boolean finishedEarly = second.isDone();
    assertTrue(waited, "the second load waits while the first one runs");
    assertFalse(finishedEarly);
  }

  private static void assertLoaded(final Future<Optional<Path>> load) throws Exception {
    final Optional<Path> result = load.get(5, TimeUnit.SECONDS);
    final Optional<Path> expected = Optional.of(LIBRARY_DIRECTORY);
    assertEquals(expected, result);
  }

  /**
   * Waits until a thread blocks on the lock of the load state. Nothing signals that a thread started to wait for a
   * monitor, so the state of the thread is checked in a spin loop that gives up at a deadline.
   */
  private static boolean awaitBlocked(final AtomicReference<Thread> threadReference) {
    final long start = System.nanoTime();
    final long timeout = BLOCK_TIMEOUT.toNanos();
    final long deadline = start + timeout;
    long now = start;
    while (now < deadline) {
      final boolean blocked = isBlocked(threadReference);
      if (blocked) {
        return true;
      }
      Thread.onSpinWait();
      now = System.nanoTime();
    }
    return false;
  }

  private static boolean isBlocked(final AtomicReference<Thread> threadReference) {
    final Thread thread = threadReference.get();
    if (thread == null) {
      return false;
    }
    final Thread.State state = thread.getState();
    return state == Thread.State.BLOCKED;
  }

  private static void awaitInLoader(final CountDownLatch latch) throws IOException {
    try {
      final boolean released = latch.await(5, TimeUnit.SECONDS);
      assertTrue(released);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new IOException("Interrupted while waiting", exception);
    }
  }
}
