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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.media.Polling;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link PlaybackClock}.
 */
final class PlaybackClockTest {

  @Test
  void anchorsOnTheFirstTimestamp() {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    final long before = System.nanoTime();
    final long first = clock.dueAt(5_000_000L);
    final long second = clock.dueAt(5_500_000L);
    final long after = System.nanoTime();
    assertTrue(first >= before);
    assertTrue(first <= after, "the first timestamp is due now");
    assertEquals(first + 500_000_000L, second, "later media is due correspondingly later");
  }

  @Test
  void reanchorsAfterLargeJumps() {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    clock.dueAt(0L);
    final long beforeJump = System.nanoTime();
    final long afterForwardJump = clock.dueAt(60_000_000L);
    final long afterBackwardJump = clock.dueAt(0L);
    assertTrue(afterForwardJump - beforeJump < 1_000_000_000L, "a jump ahead does not wait a minute");
    assertTrue(afterBackwardJump >= afterForwardJump, "a jump back does not rush through the media");
  }

  @Test
  void resetForgetsTheAnchor() {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    clock.dueAt(0L);
    clock.reset();
    final long before = System.nanoTime();
    final long due = clock.dueAt(1_000_000L);
    final long after = System.nanoTime();
    assertTrue(due >= before, "the next timestamp is due now");
    assertTrue(due <= after, "and not one second after the forgotten anchor");
  }

  @Test
  void pausingShiftsTheScheduleByThePausedTime() throws InterruptedException {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    final long anchored = clock.dueAt(0L);
    final long beforePause = System.nanoTime();
    clock.pause();
    Thread.sleep(20);
    clock.pause();
    final boolean paused = clock.isPaused();
    Thread.sleep(50);
    clock.resume();
    final long afterResume = System.nanoTime();
    Thread.sleep(50);
    clock.resume();
    final boolean resumed = !clock.isPaused();
    final long due = clock.dueAt(100_000L);
    final long shift = due - anchored - 100_000_000L;
    assertTrue(paused);
    assertTrue(resumed);
    assertTrue(shift >= 65_000_000L, "the whole pause of 70 ms is added to the schedule, shift " + shift);
    assertTrue(shift <= afterResume - beforePause, "only the paused time is added, shift " + shift);
  }

  @Test
  void waitsUntilResumed() throws InterruptedException {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    clock.pause();
    final CountDownLatch released = new CountDownLatch(1);
    final Thread waiter = new Thread(() -> {
      try {
        clock.awaitResume(10_000L);
        released.countDown();
      } catch (final InterruptedException exception) {
        final Thread currentThread = Thread.currentThread();
        currentThread.interrupt();
      }
    });
    waiter.start();

    final Duration timeout = Duration.ofSeconds(5);
    Polling.pollUntil(timeout, () -> waiter.getState() == Thread.State.TIMED_WAITING);
    final Thread.State state = waiter.getState();
    final boolean stillWaiting = released.getCount() == 1;
    assertEquals(Thread.State.TIMED_WAITING, state, "the waiter waits for the resume");
    clock.resume();
    final boolean woke = released.await(5, TimeUnit.SECONDS);
    assertTrue(stillWaiting);
    assertTrue(woke);
  }

  @Test
  void doesNotWaitWhileRunningAndWaitsAtMostTheTimeout() throws InterruptedException {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    final long start = System.nanoTime();
    clock.awaitResume(10_000L);
    final long running = System.nanoTime() - start;
    clock.pause();
    final long pausedStart = System.nanoTime();
    clock.awaitResume(30L);
    final long paused = System.nanoTime() - pausedStart;
    assertTrue(running < 1_000_000_000L);
    final boolean stillPaused = clock.isPaused();
    assertTrue(paused >= 25_000_000L, "a paused clock waits the timeout, waited " + paused);
    assertTrue(paused < 2_000_000_000L, "but not much longer, waited " + paused);
    assertTrue(stillPaused);
  }

  @Test
  void keepsTheScheduleForJumpsBelowTwoSeconds() {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    final long first = clock.dueAt(0L);
    final long nearJump = clock.dueAt(1_900_000L);
    assertEquals(first + 1_900_000_000L, nearJump, "media 1.9 seconds ahead is still scheduled normally");
  }
}
