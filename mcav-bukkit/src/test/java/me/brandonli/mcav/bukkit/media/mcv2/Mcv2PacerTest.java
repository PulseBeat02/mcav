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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The pacer's ladder and its steps: down by preset, then frame rate, then size, then to the dithered maps, each with a
 * message that says why; back up when there is room; keyframes left out; and the waits that keep it from swinging.
 */
final class Mcv2PacerTest {

  private static final long SECOND = 1_000_000_000L;

  /** A frame of a 50 fps video: 20 ms exactly, so every frame time and share below is an exact double. */
  private static final long FRAME_50 = 20_000_000L;

  /** A frame of a 62.5 fps video: 16 ms, 625 of which make ten seconds, an odd number. */
  private static final long FRAME_62 = 16_000_000L;

  private static final int[] FULL = { 1920, 1080 };

  private static final int[] SMALL = { 1280, 720 };

  private static final Mcv2Pacer.Preset LIVE = new Mcv2Pacer.Preset("live", 1);

  /** A preset that takes half the time: every time below stays an exact double. */
  private static final Mcv2Pacer.Preset FAST = new Mcv2Pacer.Preset("live-fast", 0.5);

  /**
   * Drives a pacer with a video at a frame rate; an encoded frame takes a time that grows with the pixels of its size
   * and with the cost of its preset.
   */
  private static final class Driver {

    private final Mcv2Pacer pacer;

    private final long interval;

    private final List<Mcv2Pacer.Change> changes = new ArrayList<>();

    private long now;

    Driver(final Mcv2Pacer pacer, final double fps, final long start) {
      this.pacer = pacer;
      this.interval = Math.round(SECOND / fps);
      this.now = start;
    }

    /** Plays the video for a while; every frame the pacer encodes takes {@code fullMs} scaled to its size and preset. */
    Mcv2Pacer.@Nullable Change play(final double seconds, final double fullMs) {
      Mcv2Pacer.Change last = null;
      final long end = this.now + (long) (seconds * SECOND);
      while (this.now < end) {
        this.now += this.interval;
        final Mcv2Pacer.Change tried = this.pacer.arrive(this.now);
        if (tried != null) {
          this.changes.add(tried);
          last = tried;
        }
        if (this.pacer.isEncoded()) {
          final Mcv2Pacer.Rung rung = this.pacer.getRung();
          final double milliseconds = (fullMs * rung.width() * rung.height() * rung.preset().cost()) / (FULL[0] * FULL[1]);
          final Mcv2Pacer.Change change = this.pacer.encoded(milliseconds, false, this.now);
          if (change != null) {
            this.changes.add(change);
            last = change;
          }
        }
      }
      return last;
    }
  }

  /** Drives a pacer frame by frame at an exact interval; every frame it encodes takes the time given. */
  private static final class Exact {

    private final Mcv2Pacer pacer;

    private final long interval;

    private long now;

    Exact(final Mcv2Pacer pacer, final long interval, final long first) {
      this.pacer = pacer;
      this.interval = interval;
      this.now = first - interval;
    }

    /** The next frame: it arrives, and if the pacer encodes it, it takes the given time; the change it caused, or null. */
    Mcv2Pacer.@Nullable Change frame(final double milliseconds) {
      this.now += this.interval;
      final Mcv2Pacer.Change tried = this.pacer.arrive(this.now);
      if (tried != null) {
        return tried;
      }
      return this.pacer.isEncoded() ? this.pacer.encoded(milliseconds, false, this.now) : null;
    }

    /** Frames until one causes a change, which it returns; the clock is then at that frame. */
    Mcv2Pacer.Change until(final double milliseconds, final int limit) {
      for (int frameNumber = 0; frameNumber < limit; frameNumber++) {
        final Mcv2Pacer.Change change = this.frame(milliseconds);
        if (change != null) {
          return change;
        }
      }
      throw new AssertionError("No change in " + limit + " frames");
    }
  }

  @Test
  void buildsTheLadderFromTheSizes() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final List<Mcv2Pacer.Rung> ladder = pacer.getLadder();
    assertEquals(11, ladder.size());
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1), ladder.getFirst());
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 6), ladder.get(4));
    assertEquals(new Mcv2Pacer.Rung(1280, 720, 1), ladder.get(5));
    assertEquals(Mcv2Pacer.Rung.DITHERED, ladder.getLast());
    assertEquals(ladder.getFirst(), pacer.getRung());
    assertTrue(Double.isNaN(pacer.getVideoFps()));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(new int[] { 1 })));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(new int[] { 0, 1 })));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(new int[] { 1, 0 })));
  }

  @Test
  void buildsALadderOfPresetsBeforeTheFrameRates() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL), List.of(LIVE, FAST), true);
    final List<Mcv2Pacer.Rung> ladder = pacer.getLadder();
    assertEquals(13, ladder.size());
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1, LIVE), ladder.getFirst());
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1, FAST), ladder.get(1));
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2, FAST), ladder.get(2));
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 6, FAST), ladder.get(5));
    assertEquals(new Mcv2Pacer.Rung(1280, 720, 1, LIVE), ladder.get(6));
    assertEquals(new Mcv2Pacer.Rung(1280, 720, 1, FAST), ladder.get(7));
    assertEquals(Mcv2Pacer.Rung.DITHERED, ladder.getLast());
    // one preset is the ladder of a screen that does not step through presets
    assertEquals(
      new Mcv2Pacer(List.of(FULL), false).getLadder(),
      new Mcv2Pacer(List.of(FULL), List.of(Mcv2Pacer.Preset.ONLY), false).getLadder()
    );
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(FULL), List.of(), true));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(), List.of(LIVE), true));
    // every preset costs less than the one before
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(FULL), List.of(FAST, LIVE), true));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer(List.of(FULL), List.of(LIVE, new Mcv2Pacer.Preset("same", 1)), true));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer.Preset("free", 0));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer.Preset("negative", -1));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer.Preset("unknown", Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Pacer.Preset("endless", Double.POSITIVE_INFINITY));
  }

  @Test
  void describesRungsAndRates() {
    assertTrue(Mcv2Pacer.Rung.DITHERED.isDithered());
    assertEquals(0, Mcv2Pacer.Rung.DITHERED.fps(60));
    assertEquals("the dithered maps", Mcv2Pacer.Rung.DITHERED.describe(60));
    final Mcv2Pacer.Rung rung = new Mcv2Pacer.Rung(1280, 720, 4);
    assertFalse(rung.isDithered());
    assertEquals(7.5, rung.fps(30));
    assertEquals("1280x720 at 7.5 fps", rung.describe(30));
    assertEquals("1280x720 at 15 fps", rung.describe(60));
    assertEquals(Mcv2Pacer.Preset.ONLY, rung.preset());
    assertEquals("1280x720 at 15 fps with the live-fast search", new Mcv2Pacer.Rung(1280, 720, 4, FAST).describe(60));
    assertEquals("30", Mcv2Pacer.rate(29.97));
    assertEquals("12.5", Mcv2Pacer.rate(12.5));
    // exactly a twentieth from a whole number is not whole any more
    assertEquals("0.1", Mcv2Pacer.rate(0.05));
  }

  @Test
  void encodesEveryFrameAtTheTopAndMeasuresTheVideo() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    // nothing is judged before the video's frame rate is known
    assertNull(pacer.encoded(500, false, 0));
    final Driver driver = new Driver(pacer, 60, -5 * SECOND);
    assertNull(driver.play(3, 5));
    assertEquals(60, pacer.getVideoFps(), 0.1);
    assertEquals(pacer.getLadder().getFirst(), pacer.getRung());
    // two frames at the same time do not change the measured rate
    pacer.arrive(driver.now);
    assertEquals(60, pacer.getVideoFps(), 0.1);
    assertNull(pacer.encoded(0, false, 0));
    assertThrows(IllegalArgumentException.class, () -> pacer.encoded(-1, false, 0));
    assertThrows(IllegalArgumentException.class, () -> pacer.encoded(Double.NaN, false, 0));
    assertThrows(IllegalArgumentException.class, () -> pacer.encoded(Double.POSITIVE_INFINITY, false, 0));
  }

  @Test
  void leavesKeyframesOut() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    for (int frameNumber = 0; frameNumber < 200; frameNumber++) {
      assertNull(pacer.encoded(1000, true, driver.now + (frameNumber * SECOND) / 60));
    }
    assertEquals(pacer.getLadder().getFirst(), pacer.getRung());
  }

  @Test
  void stepsDownTheFrameRateFirst() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    // 25 ms per frame is more than the 16.7 ms a frame has at 60 fps, and fits in 30 fps' 33.3 ms
    final Mcv2Pacer.Change change = driver.play(2, 25);
    assertNotNull(change);
    assertTrue(change.down());
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1), change.from());
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2), change.to());
    assertEquals(
      "MCV2 screen steps down to 1920x1080 at 30 fps: encoding 1920x1080 takes 25.0 ms per frame, more than the 16.7 ms" +
        " a frame has at 60 fps with the encoder threads it has",
      change.describe()
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
  void stepsDownThePresetsBeforeTheFrameRate() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL), List.of(LIVE, FAST), true);
    final Driver driver = new Driver(pacer, 50, 0);
    driver.play(5, 5);
    // 22 ms per frame is more than the 20 ms a frame has at 50 fps; the faster preset's predicted 11 ms fits 17 ms
    final Mcv2Pacer.Change change = driver.play(2, 22);
    assertNotNull(change);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1, LIVE), change.from());
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1, FAST), change.to());
    assertEquals(
      "MCV2 screen steps down to 1920x1080 at 50 fps with the live-fast search: encoding 1920x1080 with the live search" +
        " takes 22.0 ms per frame, more than the 20.0 ms a frame has at 50 fps with the encoder threads it has",
      change.describe()
    );
    // every frame is still encoded, and it holds
    driver.play(20, 22);
    assertEquals(1, driver.changes.size());
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1, FAST), pacer.getRung());
    // once the budget frees, the slower preset is predicted at 5 ms, within 0.7 of 20 ms: the pacer climbs back to it
    final Mcv2Pacer.Change climbed = driver.play(20, 5);
    assertNotNull(climbed);
    assertEquals(
      "MCV2 screen steps back up to 1920x1080 at 50 fps with the live search: encoding 1920x1080 with the live-fast search" +
        " takes 2.5 ms per frame, well within the 20.0 ms a frame has at 50 fps",
      climbed.describe()
    );
    assertEquals(pacer.getLadder().getFirst(), pacer.getRung());
  }

  @Test
  void takesAFasterPresetThatOnlyKeepsUpBeforeALowerFrameRate() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL), List.of(LIVE, FAST), true);
    final Driver driver = new Driver(pacer, 50, 0);
    driver.play(5, 5);
    // 38 ms per frame: the faster preset's 19 ms keeps up with 20 ms without fitting 17 ms, and keeps every frame
    final Mcv2Pacer.Change change = driver.play(2, 38);
    assertNotNull(change);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1, FAST), change.to());
  }

  @Test
  void stepsDownTheFrameRateAtTheFastestPreset() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL), List.of(LIVE, FAST), true);
    final Driver driver = new Driver(pacer, 50, 0);
    driver.play(5, 5);
    // 44 ms per frame: the faster preset's 22 ms does not keep up with 20 ms at 50 fps, and fits 34 ms at 25 fps
    final Mcv2Pacer.Change change = driver.play(2, 44);
    assertNotNull(change);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2, FAST), change.to());
    // 160 ms: the faster preset's 80 ms fits no rate of 1080p down to 12.5 fps (68 ms); 720p is predicted at 71.1 ms
    // with the slower preset and 35.6 ms with the faster, which fits 16.7 fps (51 ms)
    final Mcv2Pacer.Change smaller = driver.play(4, 160);
    assertNotNull(smaller);
    assertEquals(new Mcv2Pacer.Rung(1280, 720, 3, FAST), smaller.to());
    assertEquals(2, driver.changes.size());
  }

  @Test
  void stepsDownToALowerFrameRateOnlyWhereItFitsWithRoom() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 50, 0);
    driver.play(5, 5);
    // 38 ms per frame keeps up with every other frame's 40 ms without fitting 34 ms; every third frame's 51 ms fits
    final Mcv2Pacer.Change change = driver.play(2, 38);
    assertNotNull(change);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 3), change.to());
  }

  @Test
  void stepsDownToASmallerSizeOnlyWhereItFitsWithRoom() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    // 55 ms per frame fits every fourth frame's 56.7 ms
    final Mcv2Pacer.Change first = driver.play(3, 55);
    assertNotNull(first);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 4), first.to());
    // 140 ms: 720p is predicted at about 62 ms, which keeps up with every fourth frame's 66.7 ms without fitting 56.7
    // ms, and fits every sixth frame's 85 ms
    final Mcv2Pacer.Change change = driver.play(4, 140);
    assertNotNull(change);
    assertEquals(new Mcv2Pacer.Rung(1280, 720, 6), change.to());
  }

  @Test
  void stepsDownToASmallerSizeWhenNoFrameRateFits() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    // 100 ms fits no frame rate of 1080p down to 10 fps (85 ms), and 720p's predicted 44.4 ms fits 15 fps (56.7 ms)
    final Mcv2Pacer.Change change = driver.play(4, 100);
    assertNotNull(change);
    assertEquals(new Mcv2Pacer.Rung(1280, 720, 4), change.to());
    assertEquals(1, driver.changes.size());
  }

  @Test
  void fallsBackToTheDitheredMapsAndTriesAgainLaterAndLater() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    // 2000 ms at 1080p, 889 ms at 720p: nothing fits, not even 720p at 10 fps
    final Mcv2Pacer.Change dithered = driver.play(2, 2000);
    assertNotNull(dithered);
    assertTrue(dithered.down());
    assertTrue(pacer.getRung().isDithered());
    assertTrue(dithered.describe().startsWith("MCV2 screen steps down to the dithered maps: encoding 1920x1080 takes 2000.0 ms"));
    // nothing is encoded, and a frame reported meanwhile changes nothing
    assertNull(driver.play(28, 2000));
    assertFalse(pacer.isEncoded());
    assertNull(pacer.encoded(1, false, driver.now));
    // 30 seconds after it fell back, the lowest encoded rung is tried, which fails again two seconds later (ten frames
    // at 10 fps, then a second over its time)
    driver.play(4, 2000);
    final Mcv2Pacer.Change tried = driver.changes.get(1);
    assertEquals(Mcv2Pacer.Rung.DITHERED, tried.from());
    assertEquals(new Mcv2Pacer.Rung(1280, 720, 6), tried.to());
    assertFalse(tried.down());
    assertEquals("MCV2 screen tries encoding again at 1280x720 at 10 fps after showing the dithered maps", tried.describe());
    assertEquals(3, driver.changes.size());
    assertTrue(pacer.getRung().isDithered());
    // the next try comes 60 seconds later, not 30
    driver.play(55, 2000);
    assertEquals(3, driver.changes.size());
    driver.play(8, 2000);
    assertEquals(5, driver.changes.size());
  }

  @Test
  void stepsBackUpToTheBestRungThatFitsWithRoom() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    driver.play(2, 100);
    assertEquals(new Mcv2Pacer.Rung(1280, 720, 4), pacer.getRung());
    // 720p now takes 4 ms, 1080p 9 ms: 1080p at 60 fps would fit in 0.7 of its 16.7 ms, but the pacer keeps off the
    // top rung it left for ten seconds, so it climbs to 1080p at 30 fps first, the best rung it may take
    driver.play(30, 9);
    assertEquals(3, driver.changes.size());
    final Mcv2Pacer.Change first = driver.changes.get(1);
    assertFalse(first.down());
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2), first.to());
    assertEquals(
      "MCV2 screen steps back up to 1920x1080 at 30 fps: encoding 1280x720 takes 4.0 ms per frame, well within the 66.7 ms" +
        " a frame has at 15 fps",
      first.describe()
    );
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1), driver.changes.get(2).to());
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1), pacer.getRung());
  }

  @Test
  void keepsOffARungItHadToLeave() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    driver.play(2, 25);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2), pacer.getRung());
    // 11 ms would fit 60 fps with room, but the top rung is kept off for ten seconds
    driver.play(9, 11);
    assertEquals(1, driver.changes.size());
    driver.play(7, 11);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1), pacer.getRung());
    // leaving it again keeps it off twice as long
    driver.play(2, 25);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2), pacer.getRung());
    driver.play(19, 11);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2), pacer.getRung());
    driver.play(7, 11);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1), pacer.getRung());
    // holding the top rung as long as it would next be kept off, forty seconds, makes the wait short again
    driver.play(41, 11);
    driver.play(2, 25);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2), pacer.getRung());
    driver.play(16, 11);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1), pacer.getRung());
  }

  @Test
  void keepsUpBarelyBeforeFallingBackToTheDitheredMaps() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    // 90 ms fits 10 fps (100 ms) without the room the pacer likes, but it keeps up: better than the dithered maps
    final Mcv2Pacer.Change change = driver.play(3, 90);
    assertNotNull(change);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 6), change.to());
  }

  @Test
  void waitsForANewEncoderToWarmUp() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Driver driver = new Driver(pacer, 60, 0);
    // over its time from the first frame, but no step while the encoder warms up
    assertNull(driver.play(4.9, 100));
    assertNotNull(driver.play(2, 100));
  }

  @Test
  void doesNotMeasureTheVideoOnTheDitheredMaps() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    driver.play(2, 2000);
    assertTrue(pacer.getRung().isDithered());
    // the dithering slows the video down to a frame a second; the pacer keeps the video's own rate
    final Driver slow = new Driver(pacer, 1, driver.now);
    slow.play(10, 2000);
    assertEquals(60, pacer.getVideoFps(), 0.5);
  }

  @Test
  void neverSkipsFramesOfASlowVideo() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 8, 0);
    driver.play(5, 5);
    // at 8 fps every rung that skips frames would fall under 10 fps: 1080p's 150 ms miss its 125 ms, 720p's 67 fit
    final Mcv2Pacer.Change change = driver.play(3, 150);
    assertNotNull(change);
    assertEquals(new Mcv2Pacer.Rung(1280, 720, 1), change.to());
    // from the dithered maps it tries the smallest size at every frame
    driver.play(3, 1000);
    assertTrue(pacer.getRung().isDithered());
    driver.play(31, 1000);
    assertEquals(new Mcv2Pacer.Rung(1280, 720, 1), driver.changes.get(2).to());
  }

  @Test
  void smoothsTheMeasuredFrameRate() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    pacer.arrive(0);
    pacer.arrive(10_000_000);
    assertEquals(100, pacer.getVideoFps(), 1e-9);
    // a 20 ms interval moves the measure a fifth of the way from 10 ms: to 12 ms
    pacer.arrive(30_000_000);
    assertEquals(1e9 / 12e6, pacer.getVideoFps(), 1e-9);
  }

  @Test
  void stepsDownExactlyASecondAfterItsWarmUp() {
    // 30 ms per frame at 50 fps from a first frame at 100 s: nothing is judged for five seconds, the frame at 105 s is
    // the first found over its 20 ms, and the step comes a second later, at 106 s
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 100 * SECOND);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2), clock.until(30, 1000).to());
    assertEquals(106 * SECOND, clock.now);
  }

  @Test
  void judgesFromTheTenthMeasuredFrame() {
    // one frame a second taking 1.5 s: five seconds pass before ten frames are measured (the first frame's interval is
    // unknown), so the frame at 10 s is the first judged, and the step to the dithered maps comes at 11 s
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, SECOND, 0);
    clock.until(1500, 100);
    assertEquals(11 * SECOND, clock.now);
    assertTrue(pacer.getRung().isDithered());
  }

  @Test
  void keepsARungWhoseFramesTakeExactlyTheirTime() {
    // 20 ms per frame at 50 fps is not over the 20 ms a frame has
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    for (int frameNumber = 0; frameNumber < 500; frameNumber++) {
      assertNull(clock.frame(20));
    }
    assertEquals(pacer.getLadder().getFirst(), pacer.getRung());
  }

  @Test
  void stepsToTheFirstRungItFitsExactlyTheShareOf() {
    // 34 ms is exactly 0.85 of the 40 ms a frame has at 25 fps
    assertEquals(34.0, Mcv2Pacer.FIT * 40.0);
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2), new Exact(pacer, FRAME_50, 0).until(34, 1000).to());
  }

  @Test
  void encodesTheFirstOfEveryThreeFrames() {
    // 40 ms per frame at 50 fps fits 16.7 fps' 60 ms with room, not 25 fps' 40 ms
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 3), clock.until(40, 1000).to());
    final boolean[] encoded = new boolean[6];
    for (int frameNumber = 0; frameNumber < encoded.length; frameNumber++) {
      clock.now += FRAME_50;
      pacer.arrive(clock.now);
      encoded[frameNumber] = pacer.isEncoded();
    }
    assertArrayEquals(new boolean[] { true, false, false, true, false, false }, encoded);
  }

  @Test
  void staysOnTheLowestRungWithoutDitheredMaps() {
    // 2 s per frame fits nothing; without dithered maps the floor is the lowest rung that keeps 10 fps: 12.5 fps
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL), false);
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 4), clock.until(2000, 1000).to());
    for (int frameNumber = 0; frameNumber < 500; frameNumber++) {
      assertNull(clock.frame(2000));
    }
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 4), pacer.getRung());
  }

  @Test
  void climbsBackOnAClockBeforeZero() {
    // System.nanoTime may be negative: a rung the pacer never left is never kept off
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, -1000 * SECOND);
    driver.play(5, 5);
    driver.play(2, 100);
    assertEquals(new Mcv2Pacer.Rung(1280, 720, 4), pacer.getRung());
    driver.play(30, 9);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2), driver.changes.get(1).to());
  }

  @Test
  void climbsWhenTheRungItLeftIsFreeAndFitsExactlyWithRoom() {
    // 62.5 fps: 25 ms per frame steps down to 31.25 fps; there 11.2 ms is exactly 0.7 of the 16 ms a frame has at the top,
    // which is kept off for ten seconds - 625 frames, so the frame that frees it is one the pacer encodes - and the
    // climb comes five seconds of room later, at the first encoded frame after them: 939 frames after the step
    assertEquals(11.2, Mcv2Pacer.ROOM * 16.0);
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_62, 0);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2), clock.until(25, 1000).to());
    final long stepped = clock.now;
    final Mcv2Pacer.Change climb = clock.until(11.2, 2000);
    assertEquals(pacer.getLadder().getFirst(), climb.to());
    assertEquals(stepped + 939 * FRAME_62, clock.now);
  }

  @Test
  void climbsExactlyFiveSecondsAfterRoomAppears() {
    // 50 fps: 30 ms per frame steps down to 25 fps; there 10 ms leaves room at the top once it is free, from the first
    // encoded frame at or after ten seconds (501 frames after the step), and the climb comes exactly five seconds later
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2), clock.until(30, 1000).to());
    final long stepped = clock.now;
    clock.until(10, 2000);
    assertEquals(stepped + 751 * FRAME_50, clock.now);
    assertEquals(pacer.getLadder().getFirst(), pacer.getRung());
  }

  @Test
  void aRungHeldAsLongAsItWouldNextBeKeptOffIsKeptOffOnlyTenSecondsAgain() {
    // 50 fps. Leaving the top rung doubles how long it is kept off the next time it is left, unless it held meanwhile
    // as long as that next wait: step down, climb back after ten seconds, hold the top exactly twenty seconds, then
    // leave it at once (61 ms per frame is over at the first frame) - the top is then kept off ten seconds, not twenty
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    clock.until(30, 1000);
    assertEquals(pacer.getLadder().getFirst(), clock.until(10, 2000).to());
    // the top's first encoded frame starts its hold; 1000 frames later is exactly twenty seconds
    for (int frameNumber = 0; frameNumber < 1001; frameNumber++) {
      assertNull(clock.frame(10));
    }
    final Mcv2Pacer.Change left = clock.until(61, 1000);
    assertTrue(left.down());
    final long stepped = clock.now;
    // back on 25 fps and 16.7 fps frames of 10 ms fit with room; the top is free again ten seconds after the step
    while (!pacer.getRung().equals(pacer.getLadder().getFirst()) && clock.now < stepped + 30 * SECOND) {
      clock.frame(10);
    }
    assertTrue(clock.now < stepped + 20 * SECOND, "climbed back " + (clock.now - stepped) / 1e9 + " s after the step");
  }

  @Test
  void aRungThatFailsSoonAfterItsReturnIsKeptOffTwiceAsLong() {
    // 50 fps: step down, climb back after ten seconds, hold the top for six seconds, then leave it at once - the
    // top held less than the twenty seconds it would next be kept off, so it is kept off those twenty seconds
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Exact clock = new Exact(pacer, FRAME_50, 0);
    clock.until(30, 1000);
    assertEquals(pacer.getLadder().getFirst(), clock.until(10, 2000).to());
    for (int frameNumber = 0; frameNumber < 300; frameNumber++) {
      assertNull(clock.frame(10));
    }
    assertTrue(clock.until(61, 1000).down());
    final long stepped = clock.now;
    while (!pacer.getRung().equals(pacer.getLadder().getFirst()) && clock.now < stepped + 60 * SECOND) {
      clock.frame(10);
    }
    assertTrue(clock.now >= stepped + 20 * SECOND, "climbed back " + (clock.now - stepped) / 1e9 + " s after the step");
  }

  @Test
  void aTryThatHoldsLessThanItsWaitDoesNotShortenTheNextWait() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(5, 5);
    driver.play(2, 2000);
    assertTrue(pacer.getRung().isDithered());
    // 30 seconds later 720p at 10 fps is tried; at 89 ms of its 100 it keeps up for ten seconds, then fails
    driver.play(31, 200);
    assertEquals(new Mcv2Pacer.Rung(1280, 720, 6), pacer.getRung());
    driver.play(10, 200);
    driver.play(2, 2000);
    assertTrue(pacer.getRung().isDithered());
    final int changes = driver.changes.size();
    final long fellBack = driver.now;
    // the try held less than the 60 seconds the next wait is, so that wait stays 60 seconds instead of going back to 30
    while (driver.changes.size() == changes && driver.now < fellBack + 120 * SECOND) {
      driver.play(1, 2000);
    }
    assertTrue(driver.now - fellBack >= 58 * SECOND, "tried again " + (driver.now - fellBack) / 1e9 + " s after falling back");
  }
}
