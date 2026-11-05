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

// Reports a stretch of media time in which most frames were dropped, which would otherwise look like a hang; not thread-safe.
final class LateFrameCounter {

  private static final long WINDOW_MICROSECONDS = 10_000_000L;

  private static final int MINIMUM_DROPPED_FRAMES = 10;

  private static final long REPORT_INTERVAL_MICROSECONDS = 60_000_000L;

  private final PlaybackSession.LagListener listener;

  private boolean counting;
  private long windowStartMicroseconds;
  private int shown;
  private int dropped;
  private boolean reported;
  private long lastReportMicroseconds;

  LateFrameCounter(final PlaybackSession.LagListener listener) {
    this.listener = listener;
  }

  void countShown(final long timestampMicroseconds) {
    this.beginOnFirstOrRewoundFrame(timestampMicroseconds);
    this.shown++;
    this.closeFinishedStretch(timestampMicroseconds);
  }

  void countDropped(final long timestampMicroseconds) {
    this.beginOnFirstOrRewoundFrame(timestampMicroseconds);
    this.dropped++;
    this.closeFinishedStretch(timestampMicroseconds);
  }

  private void beginOnFirstOrRewoundFrame(final long timestampMicroseconds) {
    if (this.counting && timestampMicroseconds >= this.windowStartMicroseconds) {
      return;
    }

    this.counting = true;
    this.reported = false;
    this.begin(timestampMicroseconds);
  }

  private void closeFinishedStretch(final long timestampMicroseconds) {
    if (timestampMicroseconds - this.windowStartMicroseconds < WINDOW_MICROSECONDS) {
      return;
    }

    final boolean behind = this.dropped >= MINIMUM_DROPPED_FRAMES && this.dropped > this.shown;
    final boolean quiet = !this.reported || timestampMicroseconds - this.lastReportMicroseconds >= REPORT_INTERVAL_MICROSECONDS;
    if (behind && quiet) {
      this.reported = true;
      this.lastReportMicroseconds = timestampMicroseconds;
      final int frames = this.shown + this.dropped;
      this.listener.fellBehind(this.dropped, frames);
    }

    this.begin(timestampMicroseconds);
  }

  private void begin(final long timestampMicroseconds) {
    this.windowStartMicroseconds = timestampMicroseconds;
    this.shown = 0;
    this.dropped = 0;
  }
}
