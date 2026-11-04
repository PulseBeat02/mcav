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
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;

// Tests LateFrameCounter with frames whose timestamps the test chooses, so every stretch is exact.
final class LateFrameCounterTest {

  private static final long SECOND_MICROSECONDS = 1_000_000L;
  private static final long STRETCH_MICROSECONDS = 10 * SECOND_MICROSECONDS;

  private final List<String> reports = new ArrayList<>();
  private final LateFrameCounter counter = new LateFrameCounter((dropped, frames) -> this.reports.add(dropped + " of " + frames));

  private void everySecond(final int firstSecond, final int lastSecond, final LongConsumer count) {
    for (int second = firstSecond; second <= lastSecond; second++) {
      count.accept(second * SECOND_MICROSECONDS);
    }
  }

  @Test
  void shouldReportAStretchInWhichMostFramesCameTooLate() {
    this.counter.countShown(0L);
    this.everySecond(1, 10, this.counter::countDropped);

    final List<String> expected = List.of("10 of 11");
    assertEquals(expected, this.reports, "ten seconds of video end with the frame at ten seconds");
  }

  @Test
  void shouldEndAStretchOnlyOnceItsTenSecondsArePast() {
    this.everySecond(0, 9, this.counter::countDropped);
    this.counter.countDropped(STRETCH_MICROSECONDS - 1);
    final List<String> beforeTheEnd = List.copyOf(this.reports);
    this.counter.countDropped(STRETCH_MICROSECONDS);

    assertEquals(List.of(), beforeTheEnd);
    final List<String> expected = List.of("12 of 12");
    assertEquals(expected, this.reports);
  }

  @Test
  void shouldNotReportFewerThanTenDroppedFramesAsAReason() {
    this.counter.countShown(0L);
    this.counter.countShown(SECOND_MICROSECONDS);
    this.everySecond(2, 10, this.counter::countDropped);

    assertEquals(List.of(), this.reports, "nine frames were dropped");
  }

  @Test
  void shouldNotReportAStretchInWhichHalfTheFramesCameTooLate() {
    for (int frame = 0; frame <= 21; frame++) {
      final LongConsumer count = frame % 2 == 0 ? this.counter::countDropped : this.counter::countShown;
      count.accept((frame * STRETCH_MICROSECONDS) / 21);
    }

    assertEquals(List.of(), this.reports, "eleven of twenty-two frames were dropped");
  }

  @Test
  void shouldCountOnlyTheOwnFramesOfAStretch() {
    this.everySecond(0, 10, this.counter::countShown);
    this.everySecond(11, 20, this.counter::countDropped);

    final List<String> expected = List.of("10 of 10");
    assertEquals(expected, this.reports);
  }

  @Test
  void shouldNeverReportFramesOnTime() {
    this.everySecond(0, 120, this.counter::countShown);

    assertEquals(List.of(), this.reports);
  }

  @Test
  void shouldReportAtMostOnceAMinuteOfVideo() {
    this.everySecond(0, 69, this.counter::countDropped);
    final List<String> withinTheMinute = List.copyOf(this.reports);
    this.counter.countDropped(70 * SECOND_MICROSECONDS);

    final List<String> expectedWithinTheMinute = List.of("11 of 11");
    assertEquals(expectedWithinTheMinute, withinTheMinute, "the stretches up to seventy seconds come within a minute of the first report");
    final List<String> expectedAfterAMinute = List.of("11 of 11", "10 of 10");
    assertEquals(expectedAfterAMinute, this.reports, "a minute after the first report, the next stretch is reported");
  }

  @Test
  void shouldStartCountingAnewAfterAJumpBackInTheTimestamps() {
    this.everySecond(0, 10, this.counter::countDropped);
    this.everySecond(0, 10, this.counter::countDropped);

    final List<String> expected = List.of("11 of 11", "11 of 11");
    assertEquals(expected, this.reports);
  }

  @Test
  void shouldCountFramesWithTheTimestampOfTheStartOfAStretchInIt() {
    for (int frame = 0; frame < 11; frame++) {
      this.counter.countDropped(0L);
    }

    this.counter.countDropped(STRETCH_MICROSECONDS);

    final List<String> expected = List.of("12 of 12");
    assertEquals(expected, this.reports);
  }

  @Test
  void shouldWarnInTheLogFromPlayers() {
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      PlaybackSession.logFallingBehind(301, 302);
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);
    final boolean reported = log.contains("Playback falls behind: 301 of the last 302 frames came too late to show");
    assertTrue(reported, log);
  }
}
