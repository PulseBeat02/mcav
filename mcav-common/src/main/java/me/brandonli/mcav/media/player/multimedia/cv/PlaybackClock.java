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

// Maps media timestamps onto the wall clock, so video and audio can be delivered at the right moment. The clock is
// anchored the first time a timestamp is scheduled: that timestamp is considered "now". Later timestamps are due when
// the same amount of media time has passed on the wall clock. The clock can be paused, which freezes it, and resumed,
// which shifts the anchor by the paused duration so playback continues where it stopped. A large jump of the media
// timestamps, such as after a stream discontinuity, re-anchors the clock. The clock can run faster or slower than the
// wall clock, which makes later media due correspondingly sooner or later. All methods are thread-safe.
final class PlaybackClock {

  static final double NORMAL_SPEED = 1.0;

  private static final long NANOSECONDS_PER_MICROSECOND = 1_000L;
  private static final long RESYNC_THRESHOLD_NANOSECONDS = 2_000_000_000L;

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

  long dueAt(final long mediaMicroseconds) {
    synchronized (this.lock) {
      final long now = this.paused ? this.pausedAtNanoseconds : this.nanosecondClock.getAsLong();
      if (!this.anchored) {
        this.anchor(mediaMicroseconds, now);
        return now;
      }

      final long elapsedMedia = (mediaMicroseconds - this.anchorMediaMicroseconds) * NANOSECONDS_PER_MICROSECOND;
      final long due = this.anchorWallNanoseconds + Math.round(elapsedMedia / this.speed);
      final long lag = now - due;
      final boolean discontinuity = Math.abs(lag) > RESYNC_THRESHOLD_NANOSECONDS;
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

  // Sets how fast media passes: at 2, a second of media is due every half second of wall time. The media due now stays
  // due now, so the change applies to what follows; while paused, to what follows the pause.
  void setSpeed(final double newSpeed) {
    Preconditions.checkArgument(newSpeed > 0 && Double.isFinite(newSpeed), "Speed %s must be positive!", newSpeed);

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

  // Waits while the clock is paused, until it is resumed or the timeout elapses, whichever comes first. A spurious
  // wakeup keeps waiting for the rest of the timeout. The timeout is measured on the real clock, not on the wall clock
  // of the constructor, because the thread really sleeps.
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

  boolean isPaused() {
    synchronized (this.lock) {
      return this.paused;
    }
  }

  // Forgets the anchor, so the next scheduled timestamp becomes "now".
  void reset() {
    synchronized (this.lock) {
      this.anchored = false;
    }
  }
}
