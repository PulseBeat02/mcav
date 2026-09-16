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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;

/**
 * Tests {@link SharedMediaPlayerFactory}.
 */
final class SharedMediaPlayerFactoryTest {

  private static final int THREAD_COUNT = 8;

  private final AtomicInteger creations = new AtomicInteger();
  private final List<MediaPlayerFactory> created = new ArrayList<>();

  private synchronized MediaPlayerFactory create() {
    this.creations.incrementAndGet();
    final MediaPlayerFactory factory = mock(MediaPlayerFactory.class);
    this.created.add(factory);
    return factory;
  }

  @Test
  void sharesOneInstanceAndReleasesItWithTheLastReference() {
    final SharedMediaPlayerFactory shared = new SharedMediaPlayerFactory(this::create);
    final MediaPlayerFactory first = shared.acquire();
    final MediaPlayerFactory second = shared.acquire();
    final int creationCount = this.creations.get();
    final int referencesWhileShared = shared.getReferences();
    assertSame(first, second);
    assertEquals(1, creationCount);
    assertEquals(2, referencesWhileShared);

    shared.release();
    final int referencesAfterOneRelease = shared.getReferences();
    verify(first, never()).release();
    assertEquals(1, referencesAfterOneRelease);

    shared.release();
    final int referencesAfterLastRelease = shared.getReferences();
    verify(first).release();
    assertEquals(0, referencesAfterLastRelease);
  }

  @Test
  void createsANewInstanceAfterTheLastRelease() {
    final SharedMediaPlayerFactory shared = new SharedMediaPlayerFactory(this::create);
    final MediaPlayerFactory first = shared.acquire();
    shared.release();
    final MediaPlayerFactory second = shared.acquire();
    final int creationCount = this.creations.get();
    assertNotSame(first, second);
    assertEquals(2, creationCount);
    shared.release();
    verify(second).release();
  }

  @Test
  void ignoresReleasesWithoutAcquisition() {
    final SharedMediaPlayerFactory shared = new SharedMediaPlayerFactory(this::create);
    shared.release();
    final int referencesBeforeAcquisition = shared.getReferences();
    final MediaPlayerFactory factory = shared.acquire();
    shared.release();
    shared.release();
    final int referencesAfterReleases = shared.getReferences();
    assertEquals(0, referencesBeforeAcquisition);
    assertEquals(0, referencesAfterReleases);
    verify(factory, times(1)).release();
  }

  @Test
  void acquiresNoReferenceWhenTheInstanceCannotBeCreated() {
    final AtomicInteger attempts = new AtomicInteger();
    final Supplier<MediaPlayerFactory> failing = () -> {
      attempts.incrementAndGet();
      throw new IllegalStateException("libvlc not found");
    };
    final SharedMediaPlayerFactory shared = new SharedMediaPlayerFactory(failing);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, shared::acquire);
    final String message = exception.getMessage();
    final int references = shared.getReferences();
    assertThrows(IllegalStateException.class, shared::acquire);
    final int attemptCount = attempts.get();
    assertEquals("libvlc not found", message);
    assertEquals(0, references);
    assertEquals(2, attemptCount, "every acquisition tries again");
  }

  private static void startAcquiring(
    final SharedMediaPlayerFactory shared,
    final CyclicBarrier barrier,
    final CountDownLatch done,
    final List<MediaPlayerFactory> acquired
  ) {
    final Thread thread = new Thread(() -> {
      try {
        barrier.await();
        final MediaPlayerFactory factory = shared.acquire();
        synchronized (acquired) {
          acquired.add(factory);
        }
      } catch (final Exception exception) {
        throw new IllegalStateException(exception);
      } finally {
        done.countDown();
      }
    });
    thread.start();
  }

  @Test
  void createsOneInstanceForConcurrentAcquisitions() throws Exception {
    final SharedMediaPlayerFactory shared = new SharedMediaPlayerFactory(this::create);
    final CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
    final CountDownLatch done = new CountDownLatch(THREAD_COUNT);
    final List<MediaPlayerFactory> acquired = new ArrayList<>();
    for (int threadIndex = 0; threadIndex < THREAD_COUNT; threadIndex++) {
      startAcquiring(shared, barrier, done, acquired);
    }

    final boolean finished = done.await(10, TimeUnit.SECONDS);
    final int creationCount = this.creations.get();
    final int acquiredCount = acquired.size();
    final int references = shared.getReferences();
    assertTrue(finished);
    assertEquals(1, creationCount);
    assertEquals(THREAD_COUNT, acquiredCount);
    assertEquals(THREAD_COUNT, references);

    for (int releaseIndex = 0; releaseIndex < THREAD_COUNT; releaseIndex++) {
      shared.release();
    }
    final MediaPlayerFactory factory = this.created.getFirst();
    verify(factory, times(1)).release();
  }

  @Test
  void theSharedInstanceIsASingleton() {
    final SharedMediaPlayerFactory first = SharedMediaPlayerFactory.getInstance();
    final SharedMediaPlayerFactory second = SharedMediaPlayerFactory.getInstance();
    assertSame(first, second);
  }

  @Test
  void rejectsANullCreator() {
    assertThrows(NullPointerException.class, () -> new SharedMediaPlayerFactory(null));
  }
}
