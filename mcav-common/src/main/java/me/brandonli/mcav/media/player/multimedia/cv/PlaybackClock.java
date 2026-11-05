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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

// Maps media timestamps onto the wall clock from the first one, scaled by the speed; a large jump re-anchors it; thread-safe.
final class PlaybackClock {

  static final double NORMAL_SPEED = 1.0;

  private static final long NANOSECONDS_PER_MICROSECOND = 1_000L;

  private static final long DISCONTINUITY_THRESHOLD_NANOSECONDS = 2_000_000_000L;

  private final Object lock;
  private final LongSupplier nanosecondClock;

  private boolean anchored;
  private long anchorMediaMicroseconds;
  private long anchorWallNanoseconds;
  private boolean paused;
  private long pausedAtNanoseconds;
  private double speed;

  PlaybackClock(final LongSupplier nanosecondClock) {
    this.lock = new Object();
    this.nanosecondClock = nanosecondClock;
    this.speed = NORMAL_SPEED;
  }

  long schedule(final long mediaMicroseconds) {
    synchronized (this.lock) {
      final long now = this.paused ? this.pausedAtNanoseconds : this.nanosecondClock.getAsLong();
      if (!this.anchored) {
        this.anchor(mediaMicroseconds, now);
        return now;
      }

      final long elapsedMedia = (mediaMicroseconds - this.anchorMediaMicroseconds) * NANOSECONDS_PER_MICROSECOND;
      final long due = this.anchorWallNanoseconds + Math.round(elapsedMedia / this.speed);
      final long lag = now - due;
      final boolean discontinuity = Math.abs(lag) > DISCONTINUITY_THRESHOLD_NANOSECONDS;
      if (discontinuity) {
        this.anchor(mediaMicroseconds, now);
        return now;
      }

      return due;
    }
  }

  private void anchor(final long mediaMicroseconds, final long wallNanoseconds) {
    this.anchored = true;
    this.anchorMediaMicroseconds = mediaMicroseconds;
    this.anchorWallNanoseconds = wallNanoseconds;
  }

  // Freezes the clock. Media scheduled while the clock is paused keeps its relative timing once the clock resumes.
  void pause() {
    synchronized (this.lock) {
      if (this.paused) {
        return;
      }

      this.paused = true;
      this.pausedAtNanoseconds = this.nanosecondClock.getAsLong();
    }
  }

  void resume() {
    synchronized (this.lock) {
      if (!this.paused) {
        return;
      }

      this.paused = false;
      final long now = this.nanosecondClock.getAsLong();
      final long pausedFor = now - this.pausedAtNanoseconds;
      this.anchorWallNanoseconds += pausedFor;
      this.lock.notifyAll();
    }
  }

  // Waits while paused, up to the timeout, on the real clock rather than the constructor's, because the thread really sleeps.
  void awaitResume(final long timeoutMilliseconds) throws InterruptedException {
    this.awaitResume(timeoutMilliseconds, System::nanoTime);
  }

  // Takes the real clock, so a test can stop it, which kills the mutant that moves the deadline into the past.
  @VisibleForTesting
  void awaitResume(final long timeoutMilliseconds, final LongSupplier realClock) throws InterruptedException {
    final long timeoutNanoseconds = TimeUnit.MILLISECONDS.toNanos(timeoutMilliseconds);
    final long start = realClock.getAsLong();
    final long deadline = start + timeoutNanoseconds;
    synchronized (this.lock) {
      long remainingNanoseconds = timeoutNanoseconds;
      while (this.paused && remainingNanoseconds > 0) {
        TimeUnit.NANOSECONDS.timedWait(this.lock, remainingNanoseconds);
        final long now = realClock.getAsLong();
        remainingNanoseconds = deadline - now;
      }
    }
  }

  // Forgets the anchor, so the next scheduled timestamp becomes "now".
  void reset() {
    synchronized (this.lock) {
      this.anchored = false;
    }
  }

  // The media due now stays due now, so a new speed applies to what follows, or to what follows the pause while paused.
  void setSpeed(final double newSpeed) {
    Preconditions.checkArgument(newSpeed > 0 && Double.isFinite(newSpeed), "Speed %s must be positive and finite!", newSpeed);

    synchronized (this.lock) {
      if (this.anchored) {
        final long now = this.paused ? this.pausedAtNanoseconds : this.nanosecondClock.getAsLong();
        this.anchorMediaMicroseconds += Math.round(((now - this.anchorWallNanoseconds) * this.speed) / NANOSECONDS_PER_MICROSECOND);
        this.anchorWallNanoseconds = now;
      }

      this.speed = newSpeed;
    }
  }

  double getSpeed() {
    synchronized (this.lock) {
      return this.speed;
    }
  }

  boolean isPaused() {
    synchronized (this.lock) {
      return this.paused;
    }
  }
}
