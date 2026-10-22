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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import me.brandonli.mcav.media.Polling;
import org.junit.jupiter.api.Test;

final class PlaybackClockTest {

  private static final long LONG_RESUME_TIMEOUT_MILLISECONDS = 10_000L;
  private static final long PROMPT_NANOSECONDS = 1_000_000_000L;

  private static final double FASTER_SPEED = 2;
  private static final double SLOWER_SPEED = 0.5;

  @Test
  void shouldAnchorOnTheFirstTimestamp() {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    final long before = System.nanoTime();
    final long first = clock.dueAt(5_000_000L);
    final long second = clock.dueAt(5_500_000L);
    final long after = System.nanoTime();
    final boolean notEarly = first >= before;
    assertTrue(notEarly);

    final boolean dueNow = first <= after;
    assertTrue(dueNow, "the first timestamp is due now");
    assertEquals(first + 500_000_000L, second, "later media is due correspondingly later");
  }

  @Test
  void shouldReanchorAfterLargeJumps() {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    clock.dueAt(0L);
    final long beforeJump = System.nanoTime();
    final long afterForwardJump = clock.dueAt(60_000_000L);
    final long afterBackwardJump = clock.dueAt(0L);
    final boolean forwardJumpIsDueSoon = afterForwardJump - beforeJump < PROMPT_NANOSECONDS;
    assertTrue(forwardJumpIsDueSoon, "a jump ahead does not wait a minute");

    final boolean backwardJumpIsNotRushed = afterBackwardJump >= afterForwardJump;
    assertTrue(backwardJumpIsNotRushed, "a jump back does not rush through the media");
  }

  @Test
  void shouldForgetTheAnchorOnReset() {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    clock.dueAt(0L);
    clock.reset();
    final long before = System.nanoTime();
    final long due = clock.dueAt(1_000_000L);
    final long after = System.nanoTime();
    final boolean notEarly = due >= before;
    assertTrue(notEarly, "the next timestamp is due now");

    final boolean dueNow = due <= after;
    assertTrue(dueNow, "and not one second after the forgotten anchor");
  }

  @Test
  void shouldShiftTheScheduleByThePausedTimeWhenPausing() throws InterruptedException {
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

    final boolean wholePauseAdded = shift >= 65_000_000L;
    assertTrue(wholePauseAdded, "the whole pause of 70 ms is added to the schedule, shift " + shift);

    final boolean onlyPauseAdded = shift <= afterResume - beforePause;
    assertTrue(onlyPauseAdded, "only the paused time is added, shift " + shift);
  }

  @Test
  void shouldPreserveTheRemainingMediaDelayWhenSchedulingDuringAPause() {
    final AtomicLong now = new AtomicLong();
    final PlaybackClock clock = new PlaybackClock(now::get);
    final long anchor = clock.dueAt(0L);
    assertEquals(0L, anchor);

    now.set(100_000_000L);
    clock.pause();
    now.set(3_100_000_000L);
    clock.dueAt(500_000L);
    final boolean paused = clock.isPaused();
    assertTrue(paused);

    now.set(3_200_000_000L);
    clock.resume();
    final long due = clock.dueAt(500_000L);
    assertEquals(3_600_000_000L, due, "the pending frame keeps its remaining 400ms");
  }

  @Test
  void shouldMakeTheFirstTimestampScheduledDuringAPauseDueAtResume() {
    final AtomicLong now = new AtomicLong();
    final PlaybackClock clock = new PlaybackClock(now::get);
    clock.pause();
    now.set(1_000_000_000L);
    clock.dueAt(0L);
    final boolean paused = clock.isPaused();
    assertTrue(paused);

    now.set(1_500_000_000L);
    clock.resume();
    final long due = clock.dueAt(0L);
    assertEquals(1_500_000_000L, due, "time before the first frame is not added twice");
  }

  @Test
  void shouldWaitUntilResumed() throws InterruptedException {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    clock.pause();
    final CountDownLatch released = new CountDownLatch(1);
    final Thread waiter = new Thread(() -> this.awaitResumeAndRelease(clock, released));
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
  void shouldKeepWaitingAfterAWakeupWhileStillPaused() throws InterruptedException {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    clock.pause();
    final CountDownLatch released = new CountDownLatch(1);
    final Thread waiter = new Thread(() -> this.awaitResumeAndRelease(clock, released));
    waiter.start();
    final Duration timeout = Duration.ofSeconds(5);
    Polling.pollUntil(timeout, () -> waiter.getState() == Thread.State.TIMED_WAITING);
    clock.wakeWaiters();

    final boolean returnedEarly = released.await(1, TimeUnit.SECONDS);
    assertFalse(returnedEarly, "a wakeup while paused waits for the rest of the timeout");

    clock.resume();
    final boolean woke = released.await(5, TimeUnit.SECONDS);
    assertTrue(woke);
  }

  private void awaitResumeAndRelease(final PlaybackClock clock, final CountDownLatch released) {
    try {
      clock.awaitResume(LONG_RESUME_TIMEOUT_MILLISECONDS);
      released.countDown();
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    }
  }

  @Test
  void shouldNotWaitWhileRunningAndWaitsAtMostTheTimeout() throws InterruptedException {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    final long start = System.nanoTime();
    clock.awaitResume(LONG_RESUME_TIMEOUT_MILLISECONDS);
    final long running = System.nanoTime() - start;
    clock.pause();
    final long pausedStart = System.nanoTime();
    clock.awaitResume(30L);
    final long paused = System.nanoTime() - pausedStart;
    final boolean runningDidNotWait = running < PROMPT_NANOSECONDS;
    assertTrue(runningDidNotWait);

    final boolean stillPaused = clock.isPaused();
    final boolean waitedTheTimeout = paused >= 25_000_000L;
    assertTrue(waitedTheTimeout, "a paused clock waits the timeout, waited " + paused);

    final boolean waitedNotMuchLonger = paused < 2_000_000_000L;
    assertTrue(waitedNotMuchLonger, "but not much longer, waited " + paused);
    assertTrue(stillPaused);
  }

  @Test
  void shouldReanchorOnlyBeyondTheExactTwoSecondBoundary() {
    final AtomicLong now = new AtomicLong(7_000_000_000L);
    final PlaybackClock clock = new PlaybackClock(now::get);
    final long anchor = clock.dueAt(0L);
    final long boundary = clock.dueAt(2_000_000L);
    assertEquals(7_000_000_000L, anchor);
    assertEquals(9_000_000_000L, boundary);

    now.set(8_000_000_000L);
    final long reanchored = clock.dueAt(4_000_000L);
    assertEquals(8_000_000_000L, reanchored);
  }

  @Test
  void shouldPlayFasterAndSlowerFromTheMediaDueNow() {
    final AtomicLong now = new AtomicLong(1_000_000_000L);
    final PlaybackClock clock = new PlaybackClock(now::get);
    final double initialSpeed = clock.getSpeed();
    assertEquals(PlaybackClock.NORMAL_SPEED, initialSpeed);

    clock.setSpeed(FASTER_SPEED);
    final double doubledSpeed = clock.getSpeed();
    assertEquals(FASTER_SPEED, doubledSpeed);

    final long anchor = clock.dueAt(0L);
    assertEquals(1_000_000_000L, anchor);

    final long fasterDue = clock.dueAt(1_000_000L);
    assertEquals(1_500_000_000L, fasterDue);

    now.set(1_250_000_000L);
    clock.setSpeed(SLOWER_SPEED);
    final long changedDue = clock.dueAt(500_000L);
    assertEquals(1_250_000_000L, changedDue);

    final long slowerDue = clock.dueAt(600_000L);
    assertEquals(1_450_000_000L, slowerDue);
    assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(0));
    assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(-1));
    assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(Double.POSITIVE_INFINITY));

    final double halvedSpeed = clock.getSpeed();
    assertEquals(SLOWER_SPEED, halvedSpeed);
  }

  @Test
  void shouldKeepTheMediaDueNowWhenTheSpeedChangesWhilePlaying() {
    final AtomicLong now = new AtomicLong(100_000_000L);
    final PlaybackClock clock = new PlaybackClock(now::get);
    clock.setSpeed(FASTER_SPEED);
    final long anchor = clock.dueAt(0L);
    assertEquals(100_000_000L, anchor);

    now.set(350_000_000L);
    clock.setSpeed(SLOWER_SPEED);
    final long slowerDue = clock.dueAt(600_000L);
    assertEquals(550_000_000L, slowerDue);
  }

  @Test
  void shouldChangeSpeedWhilePausedFromWhereThePauseBegan() {
    final AtomicLong now = new AtomicLong(0L);
    final PlaybackClock clock = new PlaybackClock(now::get);
    clock.dueAt(0L);
    now.set(400_000_000L);
    clock.pause();
    now.set(900_000_000L);
    clock.setSpeed(FASTER_SPEED);
    now.set(1_000_000_000L);
    clock.resume();
    final long resumedDue = clock.dueAt(400_000L);
    assertEquals(1_000_000_000L, resumedDue);

    final long fasterDue = clock.dueAt(600_000L);
    assertEquals(1_100_000_000L, fasterDue);
  }

  @Test
  void shouldKeepTheScheduleForJumpsBelowTwoSeconds() {
    final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    final long first = clock.dueAt(0L);
    final long nearJump = clock.dueAt(1_900_000L);
    assertEquals(first + 1_900_000_000L, nearJump, "media 1.9 seconds ahead is still scheduled normally");
  }
}
