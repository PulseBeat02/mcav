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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.brandonli.mcav.bukkit.testing.EqualityAssertions;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

final class Mcv2PacerTest {

  private static final int VIDEO_FRAMES_PER_SECOND = 50;

  private static final int ENCODING_MILLISECONDS = 25;
  private static final int SLOW_ENCODING_MILLISECONDS = 38;
  private static final int OVERLOADED_ENCODING_MILLISECONDS = 61;

  private static final String PRESET_NAME = "FAST";

  private static final long SECOND = 1_000_000_000L;

  private static final int FULL_WIDTH = 1920;
  private static final int FULL_HEIGHT = 1080;

  private static final int SMALL_WIDTH = 1280;
  private static final int SMALL_HEIGHT = 720;

  private static final double NANOSECONDS_PER_SECOND = 1e9;

  private static final String CLIMBED_BACK_PREFIX = "climbed back ";
  private static final String AFTER_THE_STEP_SUFFIX = " s after the step";
  private static final String TRIED_AGAIN_PREFIX = "tried again ";
  private static final String AFTER_FALLING_BACK_SUFFIX = " s after falling back";

  // 20 ms, so every frame time and share below is an exact double
  private static final long FRAME_50 = 20_000_000L;

  private static final long FRAME_62 = 16_000_000L;

  private static final int[] FULL = { 1920, 1080 };

  private static final int[] SMALL = { 1280, 720 };

  private static final Mcv2Pacer.Preset LIVE = Mcv2Pacer.Preset.of("live", 1);

  private static final Mcv2Pacer.Preset FAST = Mcv2Pacer.Preset.of("live-fast", 0.5);

  @Test
  void shouldBuildTheLadderFromTheSizes() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final List<Mcv2Pacer.Rung> ladder = pacer.getLadder();
    final int size = ladder.size();
    assertEquals(11, size);

    final Mcv2Pacer.Rung expectedFirst = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1);
    final Mcv2Pacer.Rung first = ladder.getFirst();
    assertEquals(expectedFirst, first);

    final Mcv2Pacer.Rung expectedFifth = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 6);
    final Mcv2Pacer.Rung fifth = ladder.get(4);
    assertEquals(expectedFifth, fifth);

    final Mcv2Pacer.Rung expectedSixth = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 1);
    final Mcv2Pacer.Rung sixth = ladder.get(5);
    assertEquals(expectedSixth, sixth);

    final Mcv2Pacer.Rung last = ladder.getLast();
    assertEquals(Mcv2Pacer.Rung.DITHERED, last);

    final Mcv2Pacer.Rung expectedRung = ladder.getFirst();
    final Mcv2Pacer.Rung rung = pacer.getRung();
    assertEquals(expectedRung, rung);

    final boolean frameRateUnknown = pacer.getVideoFramesPerSecond().isEmpty();
    assertTrue(frameRateUnknown);
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(new int[] { 1 })));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(new int[] { 0, 1 })));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(new int[] { 1, 0 })));
  }

  @Test
  void shouldBuildALadderOfPresetsBeforeTheFrameRates() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL), List.of(LIVE, FAST), Mcv2Pacer.Floor.DITHERED_MAPS);
    final List<Mcv2Pacer.Rung> ladder = pacer.getLadder();
    final int size = ladder.size();
    assertEquals(13, size);

    final Mcv2Pacer.Rung expectedFirst = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1, LIVE);
    final Mcv2Pacer.Rung first = ladder.getFirst();
    assertEquals(expectedFirst, first);

    final Mcv2Pacer.Rung expectedSecond = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1, FAST);
    final Mcv2Pacer.Rung second = ladder.get(1);
    assertEquals(expectedSecond, second);

    final Mcv2Pacer.Rung expectedThird = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2, FAST);
    final Mcv2Pacer.Rung third = ladder.get(2);
    assertEquals(expectedThird, third);

    final Mcv2Pacer.Rung expectedSixth = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 6, FAST);
    final Mcv2Pacer.Rung sixth = ladder.get(5);
    assertEquals(expectedSixth, sixth);

    final Mcv2Pacer.Rung expectedSeventh = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 1, LIVE);
    final Mcv2Pacer.Rung seventh = ladder.get(6);
    assertEquals(expectedSeventh, seventh);

    final Mcv2Pacer.Rung expectedEighth = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 1, FAST);
    final Mcv2Pacer.Rung eighth = ladder.get(7);
    assertEquals(expectedEighth, eighth);

    final Mcv2Pacer.Rung last = ladder.getLast();
    assertEquals(Mcv2Pacer.Rung.DITHERED, last);
    // one preset is the ladder of a screen that does not step through presets
    final List<Mcv2Pacer.Rung> expectedLadder = new Mcv2Pacer(List.of(FULL), Mcv2Pacer.Floor.LOWEST_ENCODED_RUNG).getLadder();
    final List<Mcv2Pacer.Rung> onePresetLadder = new Mcv2Pacer(
      List.of(FULL),
      List.of(Mcv2Pacer.Preset.ONLY),
      Mcv2Pacer.Floor.LOWEST_ENCODED_RUNG
    ).getLadder();
    assertEquals(expectedLadder, onePresetLadder);
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(FULL), List.of(), Mcv2Pacer.Floor.DITHERED_MAPS));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(), List.of(LIVE), Mcv2Pacer.Floor.DITHERED_MAPS));
    // every preset costs less than the one before
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(FULL), List.of(FAST, LIVE), Mcv2Pacer.Floor.DITHERED_MAPS));
    assertThrows(IllegalArgumentException.class, () ->
      new Mcv2Pacer(List.of(FULL), List.of(LIVE, Mcv2Pacer.Preset.of("same", 1)), Mcv2Pacer.Floor.DITHERED_MAPS)
    );
    assertThrows(IllegalArgumentException.class, () -> Mcv2Pacer.Preset.of("free", 0));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Pacer.Preset.of("negative", -1));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Pacer.Preset.of("unknown", Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Pacer.Preset.of("endless", Double.POSITIVE_INFINITY));
  }

  @Test
  void shouldDescribeRungsAndRates() {
    final boolean dithered = Mcv2Pacer.Rung.DITHERED.isDithered();
    assertTrue(dithered);

    final double ditheredFrameRate = Mcv2Pacer.Rung.DITHERED.framesPerSecond(60);
    assertEquals(0, ditheredFrameRate);

    final String ditheredDescription = Mcv2Pacer.Rung.DITHERED.describe(60);
    assertEquals("the dithered maps", ditheredDescription);

    final Mcv2Pacer.Rung rung = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 4);
    final boolean rungDithered = rung.isDithered();
    assertFalse(rungDithered);

    final double frameRate = rung.framesPerSecond(30);
    assertEquals(7.5, frameRate);

    final String descriptionAtThirty = rung.describe(30);
    assertEquals("1280x720 at 7.5 fps", descriptionAtThirty);

    final String descriptionAtSixty = rung.describe(60);
    assertEquals("1280x720 at 15 fps", descriptionAtSixty);

    final Mcv2Pacer.Preset preset = rung.getPreset();
    assertEquals(Mcv2Pacer.Preset.ONLY, preset);

    final String fastDescription = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 4, FAST).describe(60);
    assertEquals("1280x720 at 15 fps with the live-fast search", fastDescription);

    final String roundedRate = Mcv2Pacer.rate(29.97);
    assertEquals("30", roundedRate);

    final String fractionalRate = Mcv2Pacer.rate(12.5);
    assertEquals("12.5", fractionalRate);
    // exactly a twentieth from a whole number is not whole any more
    final String twentiethRate = Mcv2Pacer.rate(0.05);
    assertEquals("0.1", twentiethRate);
  }

  @Test
  void shouldEncodeEveryFrameAtTheTopAndMeasureTheVideo() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    // nothing is judged before the video's frame rate is known
    final Optional<Mcv2Pacer.Change> unknownRateChange = pacer.reportEncodedFrame(500, Mcv2FrameKind.PREDICTED, 0);
    final boolean changeAbsentBeforeRateKnown = unknownRateChange.isEmpty();
    assertTrue(changeAbsentBeforeRateKnown);

    final Driver driver = new Driver(pacer, 60, -5 * SECOND);
    final Mcv2Pacer.@Nullable Change change = driver.play(3, 5);
    assertNull(change);

    final double videoFramesPerSecond = pacer.getVideoFramesPerSecond().orElseThrow();
    assertEquals(60, videoFramesPerSecond, 0.1);

    final Mcv2Pacer.Rung expectedRung = pacer.getLadder().getFirst();
    final Mcv2Pacer.Rung rung = pacer.getRung();
    assertEquals(expectedRung, rung);

    // two frames at the same time do not change the measured rate
    pacer.arrive(driver.now);
    final double frameRateAfterSameTime = pacer.getVideoFramesPerSecond().orElseThrow();
    assertEquals(60, frameRateAfterSameTime, 0.1);

    final Optional<Mcv2Pacer.Change> instantChange = pacer.reportEncodedFrame(0, Mcv2FrameKind.PREDICTED, 0);
    final boolean instantChangeAbsent = instantChange.isEmpty();
    assertTrue(instantChangeAbsent);

    assertThrows(IllegalArgumentException.class, () -> pacer.reportEncodedFrame(-1, Mcv2FrameKind.PREDICTED, 0));
    assertThrows(IllegalArgumentException.class, () -> pacer.reportEncodedFrame(Double.NaN, Mcv2FrameKind.PREDICTED, 0));
    assertThrows(IllegalArgumentException.class, () -> pacer.reportEncodedFrame(Double.POSITIVE_INFINITY, Mcv2FrameKind.PREDICTED, 0));
  }

  @Test
  void shouldLeaveKeyframesOut() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    for (int frameNumber = 0; frameNumber < 200; frameNumber++) {
      final Optional<Mcv2Pacer.Change> change = pacer.reportEncodedFrame(
        1000,
        Mcv2FrameKind.KEYFRAME,
        driver.now + (frameNumber * SECOND) / 60
      );
      final boolean changeAbsent = change.isEmpty();
      assertTrue(changeAbsent);
    }

    final Mcv2Pacer.Rung expectedRung = pacer.getLadder().getFirst();
    final Mcv2Pacer.Rung rung = pacer.getRung();
    assertEquals(expectedRung, rung);
  }

  @Test
  void shouldStepDownTheFrameRateFirst() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    // 25 ms per frame is more than the 16.7 ms a frame has at 60 fps, and fits in 30 fps' 33.3 ms
    final Mcv2Pacer.Change change = driver.play(2, ENCODING_MILLISECONDS);
    assertNotNull(change);

    final boolean stepsDown = change.isDown();
    assertTrue(stepsDown);

    final Mcv2Pacer.Rung expectedFrom = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1);
    final Mcv2Pacer.Rung from = change.getFrom();
    assertEquals(expectedFrom, from);

    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2);
    final Mcv2Pacer.Rung to = change.getTo();
    assertEquals(expectedTo, to);

    final String description = change.describe();
    assertEquals(
      "MCV2 screen steps down to 1920x1080 at 30 fps: encoding 1920x1080 takes 25.0 ms per frame, more than the 16.7 ms a frame has at 60 fps with the encoder threads it has",
      description
    );

    // every other frame is encoded now
    int encoded = 0;
    for (int frameNumber = 1; frameNumber <= 10; frameNumber++) {
      pacer.arrive(driver.now + (frameNumber * SECOND) / 60);
      encoded += pacer.isEncoded() ? 1 : 0;
    }

    assertEquals(5, encoded);
  }

  @Test
  void shouldStepDownThePresetsBeforeTheFrameRate() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL), List.of(LIVE, FAST), Mcv2Pacer.Floor.DITHERED_MAPS);
    final Driver driver = new Driver(pacer, VIDEO_FRAMES_PER_SECOND, 0);
    driver.play(5, 5);
    // 22 ms per frame is more than the 20 ms a frame has at 50 fps; the faster preset's predicted 11 ms fits 17 ms
    final Mcv2Pacer.Change change = driver.play(2, 22);
    assertNotNull(change);

    final Mcv2Pacer.Rung expectedFrom = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1, LIVE);
    final Mcv2Pacer.Rung from = change.getFrom();
    assertEquals(expectedFrom, from);

    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1, FAST);
    final Mcv2Pacer.Rung to = change.getTo();
    assertEquals(expectedTo, to);

    final String description = change.describe();
    assertEquals(
      "MCV2 screen steps down to 1920x1080 at 50 fps with the live-fast search: encoding 1920x1080 with the live search takes 22.0 ms per frame, more than the 20.0 ms a frame has at 50 fps with the encoder threads it has",
      description
    );

    // every frame is still encoded, and it holds
    driver.play(20, 22);
    final int changeCount = driver.changes.size();
    assertEquals(1, changeCount);

    final Mcv2Pacer.Rung expectedRung = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1, FAST);
    final Mcv2Pacer.Rung rung = pacer.getRung();
    assertEquals(expectedRung, rung);

    // once the budget frees, the slower preset is predicted at 5 ms, within 0.7 of 20 ms: the pacer climbs back to it
    final Mcv2Pacer.Change climbed = driver.play(20, 5);
    assertNotNull(climbed);

    final String climbDescription = climbed.describe();
    assertEquals(
      "MCV2 screen steps back up to 1920x1080 at 50 fps with the live search: encoding 1920x1080 with the live-fast search takes 2.5 ms per frame, well within the 20.0 ms a frame has at 50 fps",
      climbDescription
    );
    final Mcv2Pacer.Rung expectedTop = pacer.getLadder().getFirst();
    final Mcv2Pacer.Rung rungAfterClimb = pacer.getRung();
    assertEquals(expectedTop, rungAfterClimb);
  }

  @Test
  void shouldTakeAFasterPresetThatOnlyKeepsUpBeforeALowerFrameRate() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL), List.of(LIVE, FAST), Mcv2Pacer.Floor.DITHERED_MAPS);
    final Driver driver = new Driver(pacer, VIDEO_FRAMES_PER_SECOND, 0);
    driver.play(5, 5);
    // 38 ms per frame: the faster preset's 19 ms keeps up with 20 ms without fitting 17 ms, and keeps every frame
    final Mcv2Pacer.Change change = driver.play(2, SLOW_ENCODING_MILLISECONDS);
    assertNotNull(change);

    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1, FAST);
    final Mcv2Pacer.Rung to = change.getTo();
    assertEquals(expectedTo, to);
  }

  @Test
  void shouldStepDownTheFrameRateAtTheFastestPreset() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL), List.of(LIVE, FAST), Mcv2Pacer.Floor.DITHERED_MAPS);
    final Driver driver = new Driver(pacer, VIDEO_FRAMES_PER_SECOND, 0);
    driver.play(5, 5);
    // 44 ms per frame: the faster preset's 22 ms does not keep up with 20 ms at 50 fps, and fits 34 ms at 25 fps
    final Mcv2Pacer.Change change = driver.play(2, 44);
    assertNotNull(change);

    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2, FAST);
    final Mcv2Pacer.Rung to = change.getTo();
    assertEquals(expectedTo, to);

    /*
     * 160 ms: the faster preset's 80 ms fits no rate of 1080p down to 12.5 fps (68 ms); 720p is predicted at 71.1 ms
     * with the slower preset and 35.6 ms with the faster, which fits 16.7 fps (51 ms)
     */
    final Mcv2Pacer.Change smaller = driver.play(4, 160);
    assertNotNull(smaller);

    final Mcv2Pacer.Rung expectedSmallerTo = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 3, FAST);
    final Mcv2Pacer.Rung smallerTo = smaller.getTo();
    assertEquals(expectedSmallerTo, smallerTo);

    final int changeCount = driver.changes.size();
    assertEquals(2, changeCount);
  }

  @Test
  void shouldStepDownToALowerFrameRateOnlyWhereItFitsWithRoom() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, VIDEO_FRAMES_PER_SECOND, 0);
    driver.play(5, 5);
    // 38 ms per frame keeps up with every other frame's 40 ms without fitting 34 ms; every third frame's 51 ms fits
    final Mcv2Pacer.Change change = driver.play(2, SLOW_ENCODING_MILLISECONDS);
    assertNotNull(change);

    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 3);
    final Mcv2Pacer.Rung to = change.getTo();
    assertEquals(expectedTo, to);
  }

  @Test
  void shouldStepDownToASmallerSizeOnlyWhereItFitsWithRoom() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    // 55 ms per frame fits every fourth frame's 56.7 ms
    final Mcv2Pacer.Change first = driver.play(3, 55);
    assertNotNull(first);

    final Mcv2Pacer.Rung expectedFirstTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 4);
    final Mcv2Pacer.Rung firstTo = first.getTo();
    assertEquals(expectedFirstTo, firstTo);

    // 140 ms: 720p's predicted 62 ms keeps up with every fourth frame's 66.7 ms without fitting 56.7, and fits every sixth frame's 85 ms
    final Mcv2Pacer.Change change = driver.play(4, 140);
    assertNotNull(change);

    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 6);
    final Mcv2Pacer.Rung to = change.getTo();
    assertEquals(expectedTo, to);
  }

  @Test
  void shouldStepDownToASmallerSizeWhenNoFrameRateFits() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    // 100 ms fits no frame rate of 1080p down to 10 fps (85 ms), and 720p's predicted 44.4 ms fits 15 fps (56.7 ms)
    final Mcv2Pacer.Change change = driver.play(4, 100);
    assertNotNull(change);

    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 4);
    final Mcv2Pacer.Rung to = change.getTo();
    assertEquals(expectedTo, to);

    final int changeCount = driver.changes.size();
    assertEquals(1, changeCount);
  }

  @Test
  void shouldFallBackToTheDitheredMapsAndTryAgainLaterAndLater() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    // 2000 ms at 1080p, 889 ms at 720p: nothing fits, not even 720p at 10 fps
    final Mcv2Pacer.Change dithered = driver.play(2, 2000);
    assertNotNull(dithered);

    final boolean stepsDown = dithered.isDown();
    assertTrue(stepsDown);

    final boolean onDitheredMaps = pacer.getRung().isDithered();
    assertTrue(onDitheredMaps);

    final boolean describesTheFallBack = dithered
      .describe()
      .startsWith("MCV2 screen steps down to the dithered maps: encoding 1920x1080 takes 2000.0 ms");
    assertTrue(describesTheFallBack);

    // nothing is encoded, and a frame reported meanwhile changes nothing
    final Mcv2Pacer.@Nullable Change meanwhile = driver.play(28, 2000);
    assertNull(meanwhile);

    final boolean encoded = pacer.isEncoded();
    assertFalse(encoded);

    final Optional<Mcv2Pacer.Change> reported = pacer.reportEncodedFrame(1, Mcv2FrameKind.PREDICTED, driver.now);
    final boolean reportedChangeAbsent = reported.isEmpty();
    assertTrue(reportedChangeAbsent);

    // 30 s after falling back, the lowest encoded rung is tried and fails 2 s later (ten frames at 10 fps, then a second over its time)
    driver.play(4, 2000);
    final Mcv2Pacer.Change tried = driver.changes.get(1);
    final Mcv2Pacer.Rung from = tried.getFrom();
    assertEquals(Mcv2Pacer.Rung.DITHERED, from);

    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 6);
    final Mcv2Pacer.Rung to = tried.getTo();
    assertEquals(expectedTo, to);

    final boolean triedStepsDown = tried.isDown();
    assertFalse(triedStepsDown);

    final String description = tried.describe();
    assertEquals("MCV2 screen tries encoding again at 1280x720 at 10 fps after showing the dithered maps", description);

    final int changeCount = driver.changes.size();
    assertEquals(3, changeCount);

    final boolean stillDithered = pacer.getRung().isDithered();
    assertTrue(stillDithered);

    // the next try comes 60 seconds later, not 30
    driver.play(55, 2000);
    final int changeCountBeforeRetry = driver.changes.size();
    assertEquals(3, changeCountBeforeRetry);

    driver.play(8, 2000);
    final int changeCountAfterRetry = driver.changes.size();
    assertEquals(5, changeCountAfterRetry);
  }

  @Test
  void shouldStepBackUpToTheBestRungThatFitsWithRoom() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    driver.play(2, 100);
    final Mcv2Pacer.Rung expectedRung = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 4);
    final Mcv2Pacer.Rung rung = pacer.getRung();
    assertEquals(expectedRung, rung);

    /*
     * 720p now takes 4 ms, 1080p 9 ms: 1080p at 60 fps would fit in 0.7 of its 16.7 ms, but the pacer keeps off the top
     * rung it left for ten seconds, so it climbs to 1080p at 30 fps first, the best rung it may take
     */
    driver.play(30, 9);
    final int changeCount = driver.changes.size();
    assertEquals(3, changeCount);

    final Mcv2Pacer.Change first = driver.changes.get(1);
    final boolean stepsDown = first.isDown();
    assertFalse(stepsDown);

    final Mcv2Pacer.Rung expectedFirstTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2);
    final Mcv2Pacer.Rung firstTo = first.getTo();
    assertEquals(expectedFirstTo, firstTo);

    final String description = first.describe();
    assertEquals(
      "MCV2 screen steps back up to 1920x1080 at 30 fps: encoding 1280x720 takes 4.0 ms per frame, well within the 66.7 ms a frame has at 15 fps",
      description
    );
    final Mcv2Pacer.Rung expectedSecondTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1);
    final Mcv2Pacer.Rung secondTo = driver.changes.get(2).getTo();
    assertEquals(expectedSecondTo, secondTo);

    final Mcv2Pacer.Rung expectedRungAfterClimb = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1);
    final Mcv2Pacer.Rung rungAfterClimb = pacer.getRung();
    assertEquals(expectedRungAfterClimb, rungAfterClimb);
  }

  @Test
  void shouldKeepOffARungItHadToLeave() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    driver.play(2, ENCODING_MILLISECONDS);
    final Mcv2Pacer.Rung expectedAfterStep = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2);
    final Mcv2Pacer.Rung rungAfterStep = pacer.getRung();
    assertEquals(expectedAfterStep, rungAfterStep);

    // 11 ms would fit 60 fps with room, but the top rung is kept off for ten seconds
    driver.play(9, 11);
    final int changeCount = driver.changes.size();
    assertEquals(1, changeCount);

    driver.play(7, 11);
    final Mcv2Pacer.Rung expectedAfterWait = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1);
    final Mcv2Pacer.Rung rungAfterWait = pacer.getRung();
    assertEquals(expectedAfterWait, rungAfterWait);

    // leaving it again keeps it off twice as long
    driver.play(2, ENCODING_MILLISECONDS);
    final Mcv2Pacer.Rung expectedAfterSecondStep = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2);
    final Mcv2Pacer.Rung rungAfterSecondStep = pacer.getRung();
    assertEquals(expectedAfterSecondStep, rungAfterSecondStep);

    driver.play(19, 11);
    final Mcv2Pacer.Rung expectedDuringLongerWait = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2);
    final Mcv2Pacer.Rung rungDuringLongerWait = pacer.getRung();
    assertEquals(expectedDuringLongerWait, rungDuringLongerWait);

    driver.play(7, 11);
    final Mcv2Pacer.Rung expectedAfterLongerWait = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1);
    final Mcv2Pacer.Rung rungAfterLongerWait = pacer.getRung();
    assertEquals(expectedAfterLongerWait, rungAfterLongerWait);

    // holding the top rung as long as it would next be kept off, forty seconds, makes the wait short again
    driver.play(41, 11);
    driver.play(2, ENCODING_MILLISECONDS);
    final Mcv2Pacer.Rung expectedAfterThirdStep = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2);
    final Mcv2Pacer.Rung rungAfterThirdStep = pacer.getRung();
    assertEquals(expectedAfterThirdStep, rungAfterThirdStep);

    driver.play(16, 11);
    final Mcv2Pacer.Rung expectedAfterShortWait = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 1);
    final Mcv2Pacer.Rung rungAfterShortWait = pacer.getRung();
    assertEquals(expectedAfterShortWait, rungAfterShortWait);
  }

  @Test
  void shouldKeepUpBarelyBeforeFallingBackToTheDitheredMaps() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    // 90 ms fits 10 fps (100 ms) without the room the pacer likes, but it keeps up: better than the dithered maps
    final Mcv2Pacer.Change change = driver.play(3, 90);
    assertNotNull(change);

    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 6);
    final Mcv2Pacer.Rung to = change.getTo();
    assertEquals(expectedTo, to);
  }

  @Test
  void shouldWaitForANewEncoderToWarmUp() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Driver driver = new Driver(pacer, 60, 0);
    // over its time from the first frame, but no step while the encoder warms up
    final Mcv2Pacer.@Nullable Change warmingUp = driver.play(4.9, 100);
    assertNull(warmingUp);

    final Mcv2Pacer.@Nullable Change warmedUp = driver.play(2, 100);
    assertNotNull(warmedUp);
  }

  @Test
  void shouldNotMeasureTheVideoOnTheDitheredMaps() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    driver.play(2, 2000);
    final boolean dithered = pacer.getRung().isDithered();
    assertTrue(dithered);

    // the dithering slows the video down to a frame a second; the pacer keeps the video's own rate
    final Driver slow = new Driver(pacer, 1, driver.now);
    slow.play(10, 2000);
    final double videoFramesPerSecond = pacer.getVideoFramesPerSecond().orElseThrow();
    assertEquals(60, videoFramesPerSecond, 0.5);
  }

  @Test
  void shouldNeverSkipFramesOfASlowVideo() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 8, 0);
    driver.play(5, 5);
    // at 8 fps every rung that skips frames would fall under 10 fps: 1080p's 150 ms miss its 125 ms, 720p's 67 fit
    final Mcv2Pacer.Change change = driver.play(3, 150);
    assertNotNull(change);

    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 1);
    final Mcv2Pacer.Rung to = change.getTo();
    assertEquals(expectedTo, to);

    // from the dithered maps it tries the smallest size at every frame
    driver.play(3, 1000);
    final boolean dithered = pacer.getRung().isDithered();
    assertTrue(dithered);

    driver.play(31, 1000);
    final Mcv2Pacer.Rung expectedRetryTo = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 1);
    final Mcv2Pacer.Rung retryTo = driver.changes.get(2).getTo();
    assertEquals(expectedRetryTo, retryTo);
  }

  @Test
  void shouldSmoothTheMeasuredFrameRate() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    pacer.arrive(0);
    pacer.arrive(10_000_000);
    final double firstFrameRate = pacer.getVideoFramesPerSecond().orElseThrow();
    assertEquals(100, firstFrameRate, 1e-9);

    // a 20 ms interval moves the measure a fifth of the way from 10 ms: to 12 ms
    pacer.arrive(30_000_000);
    final double expectedFrameRate = NANOSECONDS_PER_SECOND / 12e6;
    final double smoothedFrameRate = pacer.getVideoFramesPerSecond().orElseThrow();
    assertEquals(expectedFrameRate, smoothedFrameRate, 1e-9);
  }

  @Test
  void shouldAllowAFrameRateExactlyAtTheToleratedMinimum() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    long now = 0;
    // 60 ms frames over the 52.6 ms of 19 fps: found over at the 96th frame, the 114th is still short of a second later
    for (int frameNumber = 1; frameNumber <= 114; frameNumber++) {
      now += 52_631_579L;
      pacer.arrive(now);
      pacer.reportEncodedFrame(60, Mcv2FrameKind.PREDICTED, now);
    }

    // these intervals smooth the measured rate to exactly 19 fps, so every second frame gives exactly 9.5 fps
    final long[] intervals = {
      52_631_551L,
      52_631_570L,
      52_631_602L,
      52_631_587L,
      52_631_596L,
      52_631_591L,
      52_631_583L,
      52_631_585L,
      52_631_609L,
      52_631_605L,
      52_631_573L,
      52_631_540L,
    };
    for (final long interval : intervals) {
      now += interval;
      pacer.arrive(now);
    }

    final double videoFramesPerSecond = pacer.getVideoFramesPerSecond().orElseThrow();
    assertEquals(19, videoFramesPerSecond);

    final Mcv2Pacer.Change change = pacer.reportEncodedFrame(60, Mcv2FrameKind.PREDICTED, now).orElseThrow();
    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2);
    final Mcv2Pacer.Rung to = change.getTo();
    assertEquals(expectedTo, to);
  }

  @Test
  void shouldStepDownExactlyASecondAfterItsWarmUp() {
    // 30 ms frames at 50 fps from 100 s: nothing is judged until 105 s, whose frame is over its 20 ms, and the step comes at 106 s
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 100 * SECOND);
    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2);
    final Mcv2Pacer.Rung to = clock.until(30, 1000).getTo();
    assertEquals(expectedTo, to);

    final long expectedNow = 106 * SECOND;
    final long now = clock.now;
    assertEquals(expectedNow, now);
  }

  @Test
  void shouldJudgeFromTheTenthMeasuredFrame() {
    /*
     * one frame a second taking 1.5 s: five seconds pass before ten frames are measured (the first frame's interval is
     * unknown), so the frame at 10 s is the first judged, and the step to the dithered maps comes at 11 s
     */
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, SECOND, 0);
    clock.until(1500, 100);
    final long expectedNow = 11 * SECOND;
    final long now = clock.now;
    assertEquals(expectedNow, now);

    final boolean dithered = pacer.getRung().isDithered();
    assertTrue(dithered);
  }

  @Test
  void shouldKeepARungWhoseFramesTakeExactlyTheirTime() {
    // 20 ms per frame at 50 fps is not over the 20 ms a frame has
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    for (int frameNumber = 0; frameNumber < 500; frameNumber++) {
      final Mcv2Pacer.@Nullable Change change = clock.frame(20);
      assertNull(change);
    }

    final Mcv2Pacer.Rung expectedRung = pacer.getLadder().getFirst();
    final Mcv2Pacer.Rung rung = pacer.getRung();
    assertEquals(expectedRung, rung);
  }

  @Test
  void shouldStepToTheFirstRungItFitsExactlyTheShareOf() {
    // 34 ms is exactly 0.85 of the 40 ms a frame has at 25 fps
    final double share = Mcv2Pacer.FIT * 40.0;
    assertEquals(34.0, share);

    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2);
    final Mcv2Pacer.Rung to = new Exact(pacer, FRAME_50, 0).until(34, 1000).getTo();
    assertEquals(expectedTo, to);
  }

  @Test
  void shouldEncodeTheFirstOfEveryThreeFrames() {
    // 40 ms per frame at 50 fps fits 16.7 fps' 60 ms with room, not 25 fps' 40 ms
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 3);
    final Mcv2Pacer.Rung to = clock.until(40, 1000).getTo();
    assertEquals(expectedTo, to);

    final boolean[] encoded = new boolean[6];
    for (int frameNumber = 0; frameNumber < encoded.length; frameNumber++) {
      clock.now += FRAME_50;
      pacer.arrive(clock.now);
      encoded[frameNumber] = pacer.isEncoded();
    }

    final boolean[] expectedEncoded = new boolean[] { true, false, false, true, false, false };
    assertArrayEquals(expectedEncoded, encoded);
  }

  @Test
  void shouldStayOnTheLowestRungWithoutDitheredMaps() {
    // 2 s per frame fits nothing; without dithered maps the floor is the lowest rung that keeps 10 fps: 12.5 fps
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL), Mcv2Pacer.Floor.LOWEST_ENCODED_RUNG);
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 4);
    final Mcv2Pacer.Rung to = clock.until(2000, 1000).getTo();
    assertEquals(expectedTo, to);

    for (int frameNumber = 0; frameNumber < 500; frameNumber++) {
      final Mcv2Pacer.@Nullable Change change = clock.frame(2000);
      assertNull(change);
    }

    final Mcv2Pacer.Rung expectedRung = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 4);
    final Mcv2Pacer.Rung rung = pacer.getRung();
    assertEquals(expectedRung, rung);
  }

  @Test
  void shouldClimbBackOnAClockBeforeZero() {
    // System.nanoTime may be negative: a rung the pacer never left is never kept off
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, -1000 * SECOND);
    driver.play(5, 5);
    driver.play(2, 100);
    final Mcv2Pacer.Rung expectedRung = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 4);
    final Mcv2Pacer.Rung rung = pacer.getRung();
    assertEquals(expectedRung, rung);

    driver.play(30, 9);
    final Mcv2Pacer.Rung expectedClimbTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2);
    final Mcv2Pacer.Rung climbTo = driver.changes.get(1).getTo();
    assertEquals(expectedClimbTo, climbTo);
  }

  @Test
  void shouldClimbWhenTheRungItLeftIsFreeAndFitsExactlyWithRoom() {
    /*
     * 62.5 fps: 25 ms per frame steps down to 31.25 fps; there 11.2 ms is exactly 0.7 of the 16 ms a frame has at the
     * top, which is kept off for ten seconds - 625 frames, so the frame that frees it is one the pacer encodes - and
     * the climb comes five seconds of room later, at the first encoded frame after them: 939 frames after the step
     */
    final double room = Mcv2Pacer.ROOM * 16.0;
    assertEquals(11.2, room);

    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_62, 0);
    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2);
    final Mcv2Pacer.Rung to = clock.until(ENCODING_MILLISECONDS, 1000).getTo();
    assertEquals(expectedTo, to);

    final long stepped = clock.now;
    final Mcv2Pacer.Change climb = clock.until(11.2, 2000);
    final Mcv2Pacer.Rung expectedClimbTo = pacer.getLadder().getFirst();
    final Mcv2Pacer.Rung climbTo = climb.getTo();
    assertEquals(expectedClimbTo, climbTo);

    final long expectedNow = stepped + 939 * FRAME_62;
    final long now = clock.now;
    assertEquals(expectedNow, now);
  }

  @Test
  void shouldClimbExactlyFiveSecondsAfterRoomAppears() {
    /*
     * 50 fps: 30 ms per frame steps down to 25 fps; there 10 ms leaves room at the top once it is free, from the first
     * encoded frame at or after ten seconds (501 frames after the step), and the climb comes exactly five seconds later
     */
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    final Mcv2Pacer.Rung expectedTo = Mcv2Pacer.Rung.of(FULL_WIDTH, FULL_HEIGHT, 2);
    final Mcv2Pacer.Rung to = clock.until(30, 1000).getTo();
    assertEquals(expectedTo, to);

    final long stepped = clock.now;
    clock.until(10, 2000);
    final long expectedNow = stepped + 751 * FRAME_50;
    final long now = clock.now;
    assertEquals(expectedNow, now);

    final Mcv2Pacer.Rung expectedRung = pacer.getLadder().getFirst();
    final Mcv2Pacer.Rung rung = pacer.getRung();
    assertEquals(expectedRung, rung);
  }

  @Test
  void shouldKeepOffOnlyTenSecondsAgainARungHeldAsLongAsItWouldNextBeKeptOff() {
    /*
     * 50 fps. Leaving the top rung doubles how long it is kept off the next time it is left, unless it held meanwhile
     * as long as that next wait: step down, climb back after ten seconds, hold the top exactly twenty seconds, then
     * leave it at once (61 ms per frame is over at the first frame) - the top is then kept off ten seconds, not twenty
     */
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    clock.until(30, 1000);
    final Mcv2Pacer.Rung expectedTo = pacer.getLadder().getFirst();
    final Mcv2Pacer.Rung to = clock.until(10, 2000).getTo();
    assertEquals(expectedTo, to);
    // the top's first encoded frame starts its hold; 1000 frames later is exactly twenty seconds
    for (int frameNumber = 0; frameNumber < 1001; frameNumber++) {
      final Mcv2Pacer.@Nullable Change change = clock.frame(10);
      assertNull(change);
    }

    final Mcv2Pacer.Change left = clock.until(OVERLOADED_ENCODING_MILLISECONDS, 1000);
    final boolean stepsDown = left.isDown();
    assertTrue(stepsDown);

    final long stepped = clock.now;
    // back on 25 fps and 16.7 fps frames of 10 ms fit with room; the top is free again ten seconds after the step
    while (!pacer.getRung().equals(pacer.getLadder().getFirst()) && clock.now < stepped + 30 * SECOND) {
      clock.frame(10);
    }

    final boolean climbedBackSoon = clock.now < stepped + 20 * SECOND;
    assertTrue(climbedBackSoon, CLIMBED_BACK_PREFIX + (clock.now - stepped) / NANOSECONDS_PER_SECOND + AFTER_THE_STEP_SUFFIX);
  }

  @Test
  void shouldKeepOffTwiceAsLongARungThatFailsSoonAfterItsReturn() {
    /*
     * 50 fps: step down, climb back after ten seconds, hold the top for six seconds, then leave it at once - the top
     * held less than the twenty seconds it would next be kept off, so it is kept off those twenty seconds
     */
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    clock.until(30, 1000);
    final Mcv2Pacer.Rung expectedTo = pacer.getLadder().getFirst();
    final Mcv2Pacer.Rung to = clock.until(10, 2000).getTo();
    assertEquals(expectedTo, to);

    for (int frameNumber = 0; frameNumber < 300; frameNumber++) {
      final Mcv2Pacer.@Nullable Change change = clock.frame(10);
      assertNull(change);
    }

    final boolean stepsDown = clock.until(OVERLOADED_ENCODING_MILLISECONDS, 1000).isDown();
    assertTrue(stepsDown);

    final long stepped = clock.now;
    while (!pacer.getRung().equals(pacer.getLadder().getFirst()) && clock.now < stepped + 60 * SECOND) {
      clock.frame(10);
    }

    final boolean keptOffTwentySeconds = clock.now >= stepped + 20 * SECOND;
    assertTrue(keptOffTwentySeconds, CLIMBED_BACK_PREFIX + (clock.now - stepped) / NANOSECONDS_PER_SECOND + AFTER_THE_STEP_SUFFIX);

    final Mcv2Pacer.Rung expectedRung = pacer.getLadder().getFirst();
    final Mcv2Pacer.Rung rung = pacer.getRung();
    assertEquals(expectedRung, rung, "the held-off rung eventually returns");

    final boolean climbedBeforeDeadline = clock.now < stepped + 60 * SECOND;
    assertTrue(climbedBeforeDeadline, "the climb occurred before the loop deadline");
  }

  @Test
  void shouldNotShortenTheNextWaitWhenATryHoldsLessThanItsWait() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    driver.play(2, 2000);
    final boolean dithered = pacer.getRung().isDithered();
    assertTrue(dithered);

    // 30 seconds later 720p at 10 fps is tried; at 89 ms of its 100 it keeps up for ten seconds, then fails
    driver.play(31, 200);
    final Mcv2Pacer.Rung expectedRung = Mcv2Pacer.Rung.of(SMALL_WIDTH, SMALL_HEIGHT, 6);
    final Mcv2Pacer.Rung rung = pacer.getRung();
    assertEquals(expectedRung, rung);

    driver.play(10, 200);
    driver.play(2, 2000);
    final boolean ditheredAgain = pacer.getRung().isDithered();
    assertTrue(ditheredAgain);

    final int changes = driver.changes.size();
    final long fellBack = driver.now;
    // the try held less than the 60 seconds the next wait is, so that wait stays 60 seconds instead of going back to 30
    while (driver.changes.size() == changes && driver.now < fellBack + 120 * SECOND) {
      driver.play(1, 2000);
    }

    final boolean waitedAboutSixtySeconds = driver.now - fellBack >= 58 * SECOND;
    assertTrue(waitedAboutSixtySeconds, TRIED_AGAIN_PREFIX + (driver.now - fellBack) / NANOSECONDS_PER_SECOND + AFTER_FALLING_BACK_SUFFIX);

    final boolean retried = driver.changes.size() > changes;
    assertTrue(retried, "the encoded retry actually occurred");

    final Mcv2Pacer.Change retry = driver.changes.get(changes);
    final boolean fromDithered = retry.getFrom().isDithered();
    assertTrue(fromDithered);

    final boolean toDithered = retry.getTo().isDithered();
    assertFalse(toDithered);

    final boolean retriedBeforeDeadline = driver.now < fellBack + 120 * SECOND;
    assertTrue(retriedBeforeDeadline, "the retry occurred before the loop deadline");
  }

  @Test
  void shouldKeepOffOnlyTheFirstWaitAgainARungThatHoldsExactlyItsNextWait() {
    // 50 fps: the top fails and is kept off ten seconds, so a second failure would keep it off twenty
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    clock.until(30, 1000);
    final Mcv2Pacer.Rung expectedTo = pacer.getLadder().getFirst();
    final Mcv2Pacer.Rung to = clock.until(10, 2000).getTo();
    assertEquals(expectedTo, to);
    // its first frame back starts the hold; the 1,000th after it ends exactly the twenty seconds it held
    for (int frameNumber = 0; frameNumber <= 1000; frameNumber++) {
      final Mcv2Pacer.@Nullable Change change = clock.frame(10);
      assertNull(change);
    }

    // one frame far over, so no later frame of the hold counts, then a second of frames over
    final Mcv2Pacer.@Nullable Change farOver = clock.frame(1000);
    assertNull(farOver);

    final boolean stepsDown = clock.until(30, 1000).isDown();
    assertTrue(stepsDown);

    final long stepped = clock.now;
    while (!pacer.getRung().equals(pacer.getLadder().getFirst()) && clock.now < stepped + 60 * SECOND) {
      clock.frame(10);
    }

    // the hold ended the doubled wait: ten seconds kept off and five of room, not twenty and five
    final boolean climbedBackSoon = clock.now - stepped < 20 * SECOND;
    assertTrue(climbedBackSoon, CLIMBED_BACK_PREFIX + (clock.now - stepped) / NANOSECONDS_PER_SECOND + AFTER_THE_STEP_SUFFIX);
  }

  @Test
  void shouldShortenThatWaitAgainWhenATryHoldsExactlyItsNextWait() {
    // 50 fps, one size: far over at the top, the pacer falls back to the dithered maps; the next fall would wait 60 s
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    Mcv2Pacer.Change fall = clock.until(1000, 100_000);
    while (!fall.getTo().isDithered()) {
      fall = clock.until(1000, 100_000);
    }

    // 30 seconds later the lowest encoded rung is tried: at 60 ms a frame it keeps up, with no room to climb
    final Mcv2Pacer.Change tried = clock.until(60, 2000);
    final boolean triedDithered = tried.getTo().isDithered();
    assertFalse(triedDithered);

    /*
     * its hold starts with its first encoded frame, a divisor of frames after the try, and ends on an encoded frame
     * exactly 60 seconds later, as long as the next wait would be
     */
    final long held = clock.now + tried.getTo().getDivisor() * FRAME_50 + 60 * SECOND;
    while (clock.now < held) {
      final Mcv2Pacer.@Nullable Change change = clock.frame(60);
      assertNull(change);
    }

    // every frame after it is far over, so no later frame of the try counts, and the pacer falls back again
    final boolean fellBackAgain = clock.until(1000, 1000).getTo().isDithered();
    assertTrue(fellBackAgain);

    final long fellBack = clock.now;
    clock.until(60, 5000);
    // the try held as long as the wait, so the wait went back to 30 seconds instead of 60
    final long expectedWait = 30 * SECOND;
    final long wait = clock.now - fellBack;
    assertEquals(expectedWait, wait, TRIED_AGAIN_PREFIX + (clock.now - fellBack) / NANOSECONDS_PER_SECOND + AFTER_FALLING_BACK_SUFFIX);
  }

  @Test
  void shouldComparePresetsByEveryPart() {
    final Mcv2Pacer.Preset preset = Mcv2Pacer.Preset.of(PRESET_NAME, 1);
    EqualityAssertions.assertEqualityContract(
      preset,
      Mcv2Pacer.Preset.of(PRESET_NAME, 1),
      Mcv2Pacer.Preset.of("SLOW", 1),
      Mcv2Pacer.Preset.of(PRESET_NAME, 2)
    );
  }

  @Test
  void shouldHashPresetsByEveryPart() {
    final Mcv2Pacer.Preset preset = Mcv2Pacer.Preset.of(PRESET_NAME, 1);

    final int hash = preset.hashCode();
    final int expected = Objects.hash(PRESET_NAME, 1.0);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribePresetsWithEveryPart() {
    final String description = Mcv2Pacer.Preset.ONLY.toString();
    assertEquals("Preset{name=, cost=1.0}", description);
  }

  @Test
  void shouldReturnThePresetName() {
    final Mcv2Pacer.Preset preset = Mcv2Pacer.Preset.of(PRESET_NAME, 1);

    final String name = preset.getName();
    assertEquals(PRESET_NAME, name);
  }

  @Test
  void shouldCompareRungsByEveryPart() {
    final Mcv2Pacer.Preset fast = Mcv2Pacer.Preset.of(PRESET_NAME, 1);
    final Mcv2Pacer.Rung rung = Mcv2Pacer.Rung.of(16, 9, 1, fast);
    EqualityAssertions.assertEqualityContract(
      rung,
      Mcv2Pacer.Rung.of(16, 9, 1, fast),
      Mcv2Pacer.Rung.of(32, 9, 1, fast),
      Mcv2Pacer.Rung.of(16, 18, 1, fast),
      Mcv2Pacer.Rung.of(16, 9, 2, fast),
      Mcv2Pacer.Rung.of(16, 9, 1)
    );
  }

  @Test
  void shouldHashRungsByEveryPart() {
    final Mcv2Pacer.Rung rung = Mcv2Pacer.Rung.of(16, 9, 1);

    final int hash = rung.hashCode();
    final int expected = Objects.hash(16, 9, 1, Mcv2Pacer.Preset.ONLY);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribeRungsWithEveryPart() {
    final String description = Mcv2Pacer.Rung.DITHERED.toString();
    assertEquals("Rung{width=0, height=0, divisor=0, preset=Preset{name=, cost=1.0}}", description);
  }

  @Test
  void shouldCompareChangesByEveryPart() {
    final Mcv2Pacer.Rung full = Mcv2Pacer.Rung.of(16, 9, 1);
    final Mcv2Pacer.Change change = Mcv2Pacer.Change.of(full, Mcv2Pacer.Rung.DITHERED, true, 1, 2, 3);
    EqualityAssertions.assertEqualityContract(
      change,
      Mcv2Pacer.Change.of(full, Mcv2Pacer.Rung.DITHERED, true, 1, 2, 3),
      Mcv2Pacer.Change.of(Mcv2Pacer.Rung.DITHERED, Mcv2Pacer.Rung.DITHERED, true, 1, 2, 3),
      Mcv2Pacer.Change.of(full, full, true, 1, 2, 3),
      Mcv2Pacer.Change.of(full, Mcv2Pacer.Rung.DITHERED, false, 1, 2, 3),
      Mcv2Pacer.Change.of(full, Mcv2Pacer.Rung.DITHERED, true, 9, 2, 3),
      Mcv2Pacer.Change.of(full, Mcv2Pacer.Rung.DITHERED, true, 1, 9, 3),
      Mcv2Pacer.Change.of(full, Mcv2Pacer.Rung.DITHERED, true, 1, 2, 9)
    );
  }

  @Test
  void shouldHashChangesByEveryPart() {
    final Mcv2Pacer.Rung full = Mcv2Pacer.Rung.of(16, 9, 1);
    final Mcv2Pacer.Change change = Mcv2Pacer.Change.of(full, Mcv2Pacer.Rung.DITHERED, true, 1, 2, 3);

    final int hash = change.hashCode();
    final int expected = Objects.hash(full, Mcv2Pacer.Rung.DITHERED, true, 1.0, 2.0, 3.0);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribeChangesWithEveryPart() {
    final Mcv2Pacer.Change change = Mcv2Pacer.Change.of(Mcv2Pacer.Rung.DITHERED, Mcv2Pacer.Rung.DITHERED, false, 1, 2, 3);

    final String description = change.toString();
    final String rung = Mcv2Pacer.Rung.DITHERED.toString();
    final String expected =
      "Change{from=%s, to=%s, down=false, encodeMilliseconds=1.0, frameMilliseconds=2.0, videoFramesPerSecond=3.0}".formatted(rung, rung);
    assertEquals(expected, description);
  }

  @Test
  void shouldReturnTheChangeTimings() {
    final Mcv2Pacer.Change change = Mcv2Pacer.Change.of(Mcv2Pacer.Rung.DITHERED, Mcv2Pacer.Rung.DITHERED, false, 1, 2, 3);

    final double encodeMilliseconds = change.getEncodeMilliseconds();
    assertEquals(1.0, encodeMilliseconds);

    final double frameMilliseconds = change.getFrameMilliseconds();
    assertEquals(2.0, frameMilliseconds);

    final double videoFramesPerSecond = change.getVideoFramesPerSecond();
    assertEquals(3.0, videoFramesPerSecond);
  }

  @Test
  void shouldRefuseNullPartsOfPresetsRungsAndChanges() {
    assertThrows(NullPointerException.class, () -> Mcv2Pacer.Preset.of(null, 1));
    assertThrows(NullPointerException.class, () -> Mcv2Pacer.Rung.of(1280, 720, 1, null));
    assertThrows(NullPointerException.class, () -> Mcv2Pacer.Change.of(null, Mcv2Pacer.Rung.DITHERED, false, 0, 0, 0));
    assertThrows(NullPointerException.class, () -> Mcv2Pacer.Change.of(Mcv2Pacer.Rung.DITHERED, null, false, 0, 0, 0));
  }

  private static final class Driver {

    private final Mcv2Pacer pacer;

    private final long interval;

    private final List<Mcv2Pacer.Change> changes = new ArrayList<>();

    private long now;

    Driver(final Mcv2Pacer pacer, final double framesPerSecond, final long start) {
      this.pacer = pacer;
      this.interval = Math.round(SECOND / framesPerSecond);
      this.now = start;
    }

    Mcv2Pacer.@Nullable Change play(final double seconds, final double fullMilliseconds) {
      Mcv2Pacer.Change last = null;
      final long end = this.now + (long) (seconds * SECOND);
      while (this.now < end) {
        this.now += this.interval;
        final Mcv2Pacer.@Nullable Change tried = this.pacer.arrive(this.now).orElse(null);
        if (tried != null) {
          this.changes.add(tried);
          last = tried;
        }
        final Mcv2Pacer.Change change = this.encodeArrived(fullMilliseconds);
        if (change != null) {
          this.changes.add(change);
          last = change;
        }
      }

      return last;
    }

    private Mcv2Pacer.@Nullable Change encodeArrived(final double fullMilliseconds) {
      if (!this.pacer.isEncoded()) {
        return null;
      }

      final Mcv2Pacer.Rung rung = this.pacer.getRung();
      final double milliseconds =
        (fullMilliseconds * rung.getWidth() * rung.getHeight() * rung.getPreset().getCost()) / (FULL[0] * FULL[1]);
      return this.pacer.reportEncodedFrame(milliseconds, Mcv2FrameKind.PREDICTED, this.now).orElse(null);
    }
  }

  private static final class Exact {

    private final Mcv2Pacer pacer;

    private final long interval;

    private long now;

    Exact(final Mcv2Pacer pacer, final long interval, final long first) {
      this.pacer = pacer;
      this.interval = interval;
      this.now = first - interval;
    }

    Mcv2Pacer.@Nullable Change frame(final double milliseconds) {
      this.now += this.interval;
      final Mcv2Pacer.@Nullable Change tried = this.pacer.arrive(this.now).orElse(null);
      if (tried != null) {
        return tried;
      }

      return this.pacer.isEncoded() ? this.pacer.reportEncodedFrame(milliseconds, Mcv2FrameKind.PREDICTED, this.now).orElse(null) : null;
    }

    Mcv2Pacer.Change until(final double milliseconds, final int limit) {
      for (int frameNumber = 0; frameNumber < limit; frameNumber++) {
        final Mcv2Pacer.Change change = this.frame(milliseconds);
        if (change != null) {
          return change;
        }
      }

      final String message = "No change in %s frames!".formatted(limit);
      throw new AssertionError(message);
    }
  }
}
