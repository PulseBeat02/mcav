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
 * The pacer's ladder and its steps: down by frame rate, then size, then to the dithered maps, each with a message
 * that says why; back up when there is room; keyframes left out; and the waits that keep it from swinging.
 */
final class Mcv2PacerTest {

  private static final long SECOND = 1_000_000_000L;
  private static final int[] FULL = { 1920, 1080 };
  private static final int[] SMALL = { 1280, 720 };

  /**
   * Drives a pacer with a video at a frame rate; an encoded frame takes a time that grows with the pixels of its size.
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

    /** Plays the video for a while; every frame the pacer encodes takes {@code fullMs} scaled to its size. */
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
          final double milliseconds = (fullMs * rung.width() * rung.height()) / (FULL[0] * FULL[1]);
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
  void describesRungsAndRates() {
    assertTrue(Mcv2Pacer.Rung.DITHERED.isDithered());
    assertEquals(0, Mcv2Pacer.Rung.DITHERED.fps(60));
    assertEquals("the dithered maps", Mcv2Pacer.Rung.DITHERED.describe(60));
    final Mcv2Pacer.Rung rung = new Mcv2Pacer.Rung(1280, 720, 4);
    assertFalse(rung.isDithered());
    assertEquals(7.5, rung.fps(30));
    assertEquals("1280x720 at 7.5 fps", rung.describe(30));
    assertEquals("1280x720 at 15 fps", rung.describe(60));
    assertEquals("30", Mcv2Pacer.rate(29.97));
    assertEquals("12.5", Mcv2Pacer.rate(12.5));
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
    assertThrows(IllegalArgumentException.class, () -> pacer.encoded(-1, false, 0));
    assertThrows(IllegalArgumentException.class, () -> pacer.encoded(Double.NaN, false, 0));
    assertThrows(IllegalArgumentException.class, () -> pacer.encoded(Double.POSITIVE_INFINITY, false, 0));
  }

  @Test
  void leavesKeyframesOut() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(1, 5);
    for (int i = 0; i < 200; i++) {
      assertNull(pacer.encoded(1000, true, driver.now + (i * SECOND) / 60));
    }
    assertEquals(pacer.getLadder().getFirst(), pacer.getRung());
  }

  @Test
  void stepsDownTheFrameRateFirst() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(1, 5);
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
    for (int i = 1; i <= 10; i++) {
      pacer.arrive(driver.now + (i * SECOND) / 60);
      encoded += pacer.isEncoded() ? 1 : 0;
    }
    assertEquals(5, encoded);
  }

  @Test
  void stepsDownToASmallerSizeWhenNoFrameRateFits() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(1, 5);
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
    driver.play(1, 5);
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
    // 30 seconds after it fell back, the lowest encoded rung is tried, which fails again a second later
    driver.play(3, 2000);
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
    driver.play(6, 2000);
    assertEquals(5, driver.changes.size());
  }

  @Test
  void stepsBackUpToTheBestRungThatFitsWithRoom() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 60, 0);
    driver.play(1, 5);
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
    driver.play(1, 5);
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
    // holding the top rung for five seconds makes the wait short again
    driver.play(6, 11);
    driver.play(2, 25);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 2), pacer.getRung());
    driver.play(16, 11);
    assertEquals(new Mcv2Pacer.Rung(1920, 1080, 1), pacer.getRung());
  }

  @Test
  void neverSkipsFramesOfASlowVideo() {
    final Mcv2Pacer pacer = new Mcv2Pacer(List.of(FULL, SMALL));
    final Driver driver = new Driver(pacer, 8, 0);
    driver.play(1, 5);
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
}
