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

/**
 * Gives every decoded video frame a timestamp the playback clock can schedule.
 *
 * <p>FFmpeg stamps every frame, but the video reader of OpenCV stamps every frame with zero. Without real timestamps
 * every frame would be due at once, so the frames of a file would race by or be dropped as late. Whenever a frame
 * does not come after the previous one, its timestamp is derived instead: media of a known length advances by one
 * frame period of the reported frame rate, and live sources, such as cameras, use the time that passed since their
 * first frame, because they deliver frames in real time anyway. Instances belong to one decoding thread.
 */
final class VideoTimestamps {

  private static final double MICROS_PER_SECOND = 1_000_000.0;
  private static final long NANOS_PER_MICRO = 1_000L;

  private final long frameMicros;
  private final LongSupplier nanoClock;

  private boolean started;
  private long firstTimestamp;
  private long firstNanos;
  private long lastTimestamp;

  /**
   * Constructs a new timeline.
   *
   * @param frameRate the frame rate the decoder reports, or zero or less if it reports none
   * @param live      whether the source delivers frames in real time, such as a camera or a stream without length
   * @param nanoClock the clock the time between frames of live sources is measured with, usually
   *                  {@link System#nanoTime()}
   */
  VideoTimestamps(final double frameRate, final boolean live, final LongSupplier nanoClock) {
    final boolean usableRate = !live && frameRate > 0 && Double.isFinite(frameRate);
    this.frameMicros = usableRate ? Math.round(MICROS_PER_SECOND / frameRate) : 0L;
    this.nanoClock = nanoClock;
  }

  /**
   * Gets the timestamp of the next video frame.
   *
   * @param reported the timestamp the decoder reported in microseconds
   * @return the reported timestamp if it comes after the previous frame, a derived one otherwise
   */
  long next(final long reported) {
    final long now = this.nanoClock.getAsLong();
    if (!this.started) {
      this.started = true;
      this.firstTimestamp = reported;
      this.firstNanos = now;
      this.lastTimestamp = reported;
      return reported;
    }
    final long timestamp = reported > this.lastTimestamp ? reported : this.derive(now);
    this.lastTimestamp = timestamp;
    return timestamp;
  }

  private long derive(final long now) {
    if (this.frameMicros > 0) {
      return this.lastTimestamp + this.frameMicros;
    }
    final long elapsedMicros = (now - this.firstNanos) / NANOS_PER_MICRO;
    final long arrival = this.firstTimestamp + elapsedMicros;
    // two frames of a live source can arrive within the same microsecond, and time must still move forward
    return Math.max(arrival, this.lastTimestamp + 1);
  }
}
