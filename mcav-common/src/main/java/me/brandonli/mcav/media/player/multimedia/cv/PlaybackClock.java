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

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Maps media timestamps onto the wall clock, so video and audio can be delivered at the right moment.
 *
 * <p>The clock is anchored the first time a timestamp is scheduled: that timestamp is considered "now". Later
 * timestamps are due when the same amount of media time has passed on the wall clock. The clock can be paused,
 * which freezes it, and resumed, which shifts the anchor by the paused duration so playback continues where it
 * stopped. A large jump of the media timestamps, such as after a stream discontinuity, re-anchors the clock.
 *
 * <p>All methods are thread-safe.
 */
final class PlaybackClock {

  private static final long NANOS_PER_MICRO = 1_000L;
  private static final long RESYNC_THRESHOLD_NANOS = 2_000_000_000L;

  private final Object lock;
  private final LongSupplier nanoClock;

  private boolean anchored;
  private long anchorMediaMicros;
  private long anchorWallNanos;
  private boolean paused;
  private long pausedAtNanos;

  /**
   * Constructs a new clock.
   *
   * @param nanoClock the wall clock in nanoseconds, {@link System#nanoTime()} for playback and a clock the test
   *                  controls in tests
   */
  PlaybackClock(final LongSupplier nanoClock) {
    this.lock = new Object();
    this.nanoClock = nanoClock;
  }

  /**
   * Computes the wall clock time at which media with the given timestamp is due, anchoring the clock if this is
   * the first timestamp, and re-anchoring it if the timestamp is far off the current schedule.
   *
   * @param mediaMicros the media timestamp in microseconds
   * @return the wall clock time in nanoseconds the media is due at
   */
  long dueAt(final long mediaMicros) {
    synchronized (this.lock) {
      final long now = this.nanoClock.getAsLong();
      if (!this.anchored) {
        this.anchor(mediaMicros, now);
        return now;
      }
      final long elapsedMedia = (mediaMicros - this.anchorMediaMicros) * NANOS_PER_MICRO;
      final long due = this.anchorWallNanos + elapsedMedia;
      final long lag = now - due;
      final boolean discontinuity = Math.abs(lag) > RESYNC_THRESHOLD_NANOS;
      if (discontinuity) {
        this.anchor(mediaMicros, now);
        return now;
      }
      return due;
    }
  }

  private void anchor(final long mediaMicros, final long wallNanos) {
    this.anchored = true;
    this.anchorMediaMicros = mediaMicros;
    this.anchorWallNanos = wallNanos;
  }

  /**
   * Freezes the clock. Media scheduled while the clock is paused keeps its relative timing once the clock resumes.
   */
  void pause() {
    synchronized (this.lock) {
      if (this.paused) {
        return;
      }
      this.paused = true;
      this.pausedAtNanos = this.nanoClock.getAsLong();
    }
  }

  /**
   * Resumes the clock, shifting the schedule by the time it was paused.
   */
  void resume() {
    synchronized (this.lock) {
      if (!this.paused) {
        return;
      }
      this.paused = false;
      final long now = this.nanoClock.getAsLong();
      final long pausedFor = now - this.pausedAtNanos;
      this.anchorWallNanos += pausedFor;
      this.lock.notifyAll();
    }
  }

  /**
   * Waits while the clock is paused, until it is resumed or the timeout elapses, whichever comes first. A spurious
   * wakeup keeps waiting for the rest of the timeout. The timeout is measured on the real clock, not on the wall
   * clock of the constructor, because the thread really sleeps.
   *
   * @param timeoutMillis the longest time to wait, in milliseconds; nothing is waited for if it is not positive
   * @throws InterruptedException if the waiting thread is interrupted
   */
  void awaitResume(final long timeoutMillis) throws InterruptedException {
    final long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    final long start = System.nanoTime();
    final long deadline = start + timeoutNanos;
    synchronized (this.lock) {
      long remainingNanos = timeoutNanos;
      while (this.paused && remainingNanos > 0) {
        TimeUnit.NANOSECONDS.timedWait(this.lock, remainingNanos);
        final long now = System.nanoTime();
        remainingNanos = deadline - now;
      }
    }
  }

  /**
   * Checks whether the clock is paused.
   *
   * @return true if paused
   */
  boolean isPaused() {
    synchronized (this.lock) {
      return this.paused;
    }
  }

  /**
   * Forgets the anchor, so the next scheduled timestamp becomes "now".
   */
  void reset() {
    synchronized (this.lock) {
      this.anchored = false;
    }
  }
}
