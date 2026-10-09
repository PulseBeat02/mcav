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

/**
 * Counts the frames the video renderer of a session shows and the frames it drops for being late, and reports a stretch
 * of video in which it dropped most of them. A source that decodes, or a pipeline that runs, slower than the video plays
 * freezes the picture without a word otherwise, which looks like a hang.
 *
 * <p>A stretch is ten seconds of video by its own timestamps, so a pause, which hands over no frames, does not count.
 * It is reported when at least ten of its frames and more than half of them were dropped, and at most once a minute of
 * video. A still picture keeps its frames on time and is never reported. Instances belong to the video renderer of one
 * session and are not thread-safe.
 */
final class LateFrameCounter {

  /**
   * How much video one count covers, in microseconds.
   */
  private static final long WINDOW_MICROS = 10_000_000L;

  /**
   * How many frames of a stretch must have been dropped at least to report it.
   */
  private static final int MIN_DROPPED = 10;

  /**
   * How much video passes at least between two reports, in microseconds.
   */
  private static final long REPORT_INTERVAL_MICROS = 60_000_000L;

  private final PlaybackSession.LagListener listener;

  private boolean counting;
  private long windowStartMicros;
  private int shown;
  private int dropped;
  private boolean reported;
  private long lastReportMicros;

  /**
   * Creates a counter.
   *
   * @param listener hears of every stretch reported
   */
  LateFrameCounter(final PlaybackSession.LagListener listener) {
    this.listener = listener;
  }

  /**
   * Counts a frame the renderer showed or dropped.
   *
   * @param timestampMicros the timestamp of the frame
   * @param late            whether the frame came too late and was dropped
   */
  void count(final long timestampMicros, final boolean late) {
    if (!this.counting || timestampMicros < this.windowStartMicros) {
      this.counting = true;
      this.reported = false;
      this.begin(timestampMicros);
    }
    if (late) {
      this.dropped++;
    } else {
      this.shown++;
    }
    if (timestampMicros - this.windowStartMicros < WINDOW_MICROS) {
      return;
    }
    final boolean behind = this.dropped >= MIN_DROPPED && this.dropped > this.shown;
    final boolean quiet = !this.reported || timestampMicros - this.lastReportMicros >= REPORT_INTERVAL_MICROS;
    if (behind && quiet) {
      this.reported = true;
      this.lastReportMicros = timestampMicros;
      final int frames = this.shown + this.dropped;
      this.listener.fellBehind(this.dropped, frames);
    }
    this.begin(timestampMicros);
  }

  private void begin(final long timestampMicros) {
    this.windowStartMicros = timestampMicros;
    this.shown = 0;
    this.dropped = 0;
  }
}
