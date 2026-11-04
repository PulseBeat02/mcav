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

import java.util.function.LongSupplier;

// Gives every decoded video frame a timestamp the playback clock can schedule. FFmpeg stamps every frame, but the video
// reader of OpenCV stamps every frame with zero. Without real timestamps every frame would be due at once, so the
// frames of a file would race by or be dropped as late. Whenever a frame does not come after the previous one, its
// timestamp is derived instead: media of a known length advances by one frame period of the reported frame rate, and
// live sources, such as cameras, use the time that passed since their first frame, because they deliver frames in real
// time anyway. Instances belong to one decoding thread.
final class VideoTimestamps {

  private static final double MICROSECONDS_PER_SECOND = 1_000_000.0;
  private static final long NANOSECONDS_PER_MICROSECOND = 1_000L;

  private static final long NO_FRAME_MICROSECONDS = 0L;

  private final long frameMicroseconds;
  private final LongSupplier nanosecondClock;

  private boolean started;
  private long firstTimestamp;
  private long firstNanoseconds;
  private long lastTimestamp;

  private VideoTimestamps(final long frameMicroseconds, final LongSupplier nanosecondClock) {
    this.frameMicroseconds = frameMicroseconds;
    this.nanosecondClock = nanosecondClock;
  }

  static VideoTimestamps recorded(final double frameRate, final LongSupplier nanosecondClock) {
    final boolean usableRate = frameRate > 0 && Double.isFinite(frameRate);
    final long frameMicroseconds = usableRate ? Math.round(MICROSECONDS_PER_SECOND / frameRate) : NO_FRAME_MICROSECONDS;
    return new VideoTimestamps(frameMicroseconds, nanosecondClock);
  }

  static VideoTimestamps live(final LongSupplier nanosecondClock) {
    return new VideoTimestamps(NO_FRAME_MICROSECONDS, nanosecondClock);
  }

  long next(final long reported) {
    final long now = this.nanosecondClock.getAsLong();
    if (!this.started) {
      this.started = true;
      this.firstTimestamp = reported;
      this.firstNanoseconds = now;
      this.lastTimestamp = reported;

      return reported;
    }

    final long timestamp = reported > this.lastTimestamp ? reported : this.derive(now);
    this.lastTimestamp = timestamp;

    return timestamp;
  }

  private long derive(final long now) {
    if (this.frameMicroseconds > 0) {
      return this.lastTimestamp + this.frameMicroseconds;
    }

    final long elapsedMicroseconds = (now - this.firstNanoseconds) / NANOSECONDS_PER_MICROSECOND;
    final long arrival = this.firstTimestamp + elapsedMicroseconds;
    // two frames of a live source can arrive within the same microsecond, and time must still move forward
    return Math.max(arrival, this.lastTimestamp + 1);
  }
}
