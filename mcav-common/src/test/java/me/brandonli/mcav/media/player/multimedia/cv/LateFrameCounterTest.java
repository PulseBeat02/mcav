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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link LateFrameCounter} with frames whose timestamps the test chooses, so every stretch is exact.
 */
final class LateFrameCounterTest {

  private static final long SECOND_MICROS = 1_000_000L;

  private final List<String> reports = new ArrayList<>();
  private final LateFrameCounter counter = new LateFrameCounter((dropped, frames) -> this.reports.add(dropped + " of " + frames));

  /**
   * Counts one frame a second from the first second to the last, inclusive, all late or all on time.
   */
  private void everySecond(final int firstSecond, final int lastSecond, final boolean late) {
    for (int second = firstSecond; second <= lastSecond; second++) {
      this.counter.count(second * SECOND_MICROS, late);
    }
  }

  @Test
  void reportsAStretchInWhichMostFramesCameTooLate() {
    this.counter.count(0L, false);
    this.everySecond(1, 10, true);

    assertEquals(List.of("10 of 11"), this.reports, "ten seconds of video end with the frame at ten seconds");
  }

  @Test
  void aStretchEndsOnlyOnceItsTenSecondsArePast() {
    this.everySecond(0, 9, true);
    this.counter.count(10 * SECOND_MICROS - 1, true);
    final List<String> beforeTheEnd = List.copyOf(this.reports);
    this.counter.count(10 * SECOND_MICROS, true);

    assertEquals(List.of(), beforeTheEnd);
    assertEquals(List.of("12 of 12"), this.reports);
  }

  @Test
  void fewerThanTenDroppedFramesAreNoReason() {
    this.counter.count(0L, false);
    this.counter.count(SECOND_MICROS, false);
    this.everySecond(2, 10, true);

    assertEquals(List.of(), this.reports, "nine frames were dropped");
  }

  @Test
  void dropsOfHalfTheFramesAreNoReason() {
    for (int frame = 0; frame <= 21; frame++) {
      final boolean late = frame % 2 == 0;
      this.counter.count((frame * 10 * SECOND_MICROS) / 21, late);
    }

    assertEquals(List.of(), this.reports, "eleven of twenty-two frames were dropped");
  }

  @Test
  void aStretchCountsOnlyItsOwnFrames() {
    this.everySecond(0, 10, false);
    this.everySecond(11, 20, true);

    assertEquals(List.of("10 of 10"), this.reports);
  }

  @Test
  void framesOnTimeAreNeverReported() {
    this.everySecond(0, 120, false);

    assertEquals(List.of(), this.reports);
  }

  @Test
  void reportsAtMostOnceAMinuteOfVideo() {
    this.everySecond(0, 69, true);
    final List<String> withinTheMinute = List.copyOf(this.reports);
    this.counter.count(70 * SECOND_MICROS, true);

    assertEquals(List.of("11 of 11"), withinTheMinute, "the stretches up to seventy seconds come within a minute of the first report");
    assertEquals(List.of("11 of 11", "10 of 10"), this.reports, "a minute after the first report, the next stretch is reported");
  }

  @Test
  void aJumpBackInTheTimestampsStartsCountingAnew() {
    this.everySecond(0, 10, true);
    this.everySecond(0, 10, true);

    assertEquals(List.of("11 of 11", "11 of 11"), this.reports);
  }

  @Test
  void framesWithTheTimestampOfTheStartOfAStretchCountInIt() {
    for (int frame = 0; frame < 11; frame++) {
      this.counter.count(0L, true);
    }
    this.counter.count(10 * SECOND_MICROS, true);

    assertEquals(List.of("12 of 12"), this.reports);
  }

  @Test
  void playersWarnInTheLog() {
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      PlaybackSession.logFallingBehind(301, 302);
    } finally {
      System.setErr(standardError);
    }
    final String log = captured.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("Playback falls behind: 301 of the last 302 frames came too late to show"), log);
  }
}
