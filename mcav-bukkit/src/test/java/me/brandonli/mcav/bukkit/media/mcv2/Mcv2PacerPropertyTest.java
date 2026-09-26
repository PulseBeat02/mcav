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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

/**
 * Whatever the video and however fast the budget encodes: the pacer never stays long on a rung whose frames take more
 * than their time, a step up it takes under a steady load holds, and it climbs back to the top once the budget frees.
 */
final class Mcv2PacerPropertyTest {

  private static final long SECOND = 1_000_000_000L;
  private static final List<int[]> SIZES = List.of(new int[] { 1920, 1080 }, new int[] { 1280, 720 }, new int[] { 960, 540 });

  /** A rung the pacer was on, from when to when, whether a step up brought it there and a step down ended it. */
  record Stretch(Mcv2Pacer.Rung rung, long from, long to, boolean climbed, boolean leftDown) {}

  /**
   * A screen's encoder under a budget: frames arrive at the video's rate; an encode takes the time of a 1080p frame at
   * the budget's current speed, scaled by the rung's pixels and a little noise; while the encoder works, the newest
   * frame it is asked for waits, as in the screen.
   */
  static final class Simulation {

    private final Mcv2Pacer pacer;
    private final long interval;
    private final double noise;
    private final Random random;
    private final List<Stretch> stretches = new ArrayList<>();
    private long now;
    private long started;
    private boolean climbed;
    private long busyUntil = Long.MIN_VALUE;
    private double cost;
    private boolean encoding;
    private boolean waiting;

    Simulation(final int sizes, final double fps, final double noise, final long seed) {
      this.pacer = new Mcv2Pacer(SIZES.subList(0, sizes));
      this.interval = Math.round(SECOND / fps);
      this.noise = noise;
      this.random = new Random(seed);
    }

    private double costOf(final Mcv2Pacer.Rung rung, final double fullMs) {
      final double scale = 1 + this.noise * (2 * this.random.nextDouble() - 1);
      return (fullMs * scale * rung.width() * rung.height()) / (1920.0 * 1080.0);
    }

    private void record(final Mcv2Pacer.Change change, final long at) {
      this.stretches.add(new Stretch(change.from(), this.started, at, this.climbed, change.down()));
      this.started = at;
      this.climbed = !change.down() && !change.from().isDithered();
    }

    /** Plays the video for a while with 1080p frames taking {@code fullMs}. */
    void play(final double seconds, final double fullMs) {
      final long end = this.now + (long) (seconds * SECOND);
      while (this.now < end) {
        final long arrival = this.now + this.interval;
        while (this.encoding && this.busyUntil <= arrival) {
          this.encoding = false;
          final Mcv2Pacer.Change change = this.pacer.encoded(this.cost, false, this.busyUntil);
          if (change != null) {
            this.record(change, this.busyUntil);
            this.waiting = false;
          }
          if (this.waiting) {
            this.waiting = false;
            this.start(this.busyUntil, fullMs);
          }
        }
        this.now = arrival;
        final Mcv2Pacer.Change tried = this.pacer.arrive(arrival);
        if (tried != null) {
          this.record(tried, arrival);
        }
        if (this.pacer.isEncoded()) {
          if (this.encoding) {
            this.waiting = true;
          } else {
            this.start(arrival, fullMs);
          }
        }
      }
    }

    private void start(final long at, final double fullMs) {
      this.cost = this.costOf(this.pacer.getRung(), fullMs);
      this.busyUntil = at + (long) (this.cost * 1e6);
      this.encoding = true;
    }

    List<Stretch> finish() {
      final List<Stretch> all = new ArrayList<>(this.stretches);
      all.add(new Stretch(this.pacer.getRung(), this.started, this.now, this.climbed, false));
      return all;
    }
  }

  @Provide
  Arbitrary<Double> frameRates() {
    return Arbitraries.of(24.0, 25.0, 30.0, 50.0, 60.0);
  }

  private static double frameMs(final Mcv2Pacer.Rung rung, final double fps) {
    return (1000.0 * rung.divisor()) / fps;
  }

  /**
   * Under a steady load, a rung whose frames take more than their time is left within the time the pacer waits plus
   * two encodes, since it only learns how long a frame takes once the frame is done; and no step up has to be undone.
   */
  @Property(tries = 300)
  boolean neverStaysLongOverItsTime(
    @ForAll("frameRates") final double fps,
    @ForAll @IntRange(min = 1, max = 3) final int sizes,
    @ForAll @DoubleRange(min = 1, max = 3000) final double fullMs,
    @ForAll @DoubleRange(min = 0, max = 0.1) final double noise,
    @ForAll final long seed
  ) {
    final Simulation simulation = new Simulation(sizes, fps, noise, seed);
    simulation.play(180, fullMs);
    final List<Stretch> stretches = simulation.finish();
    for (int i = 0; i < stretches.size(); i++) {
      final Stretch stretch = stretches.get(i);
      if (stretch.rung().isDithered()) {
        continue;
      }
      final double rungMs = (fullMs * stretch.rung().width() * stretch.rung().height()) / (1920.0 * 1080.0);
      final double seconds = (stretch.to() - stretch.from()) / (double) SECOND;
      final boolean over = rungMs * (1 - noise) > frameMs(stretch.rung(), fps);
      if (over && seconds > Mcv2Pacer.DOWN_SECONDS + (2 * rungMs * (1 + noise)) / 1000 + 1) {
        return false;
      }
      // a step up under a steady load is never undone
      if (stretch.climbed() && stretch.leftDown()) {
        return false;
      }
    }
    return true;
  }

  /** After a heavy load, once the budget frees up, the pacer climbs back to the top rung. */
  @Property(tries = 300)
  boolean climbsBackWhenTheLoadDrops(
    @ForAll("frameRates") final double fps,
    @ForAll @IntRange(min = 1, max = 3) final int sizes,
    @ForAll @DoubleRange(min = 20, max = 3000) final double heavyMs,
    @ForAll @DoubleRange(min = 0.05, max = 0.55) final double lightShare,
    @ForAll @DoubleRange(min = 0, max = 0.1) final double noise,
    @ForAll final long seed
  ) {
    final Simulation simulation = new Simulation(sizes, fps, noise, seed);
    simulation.play(20, heavyMs);
    // the top rung fits in lightShare of its frame time, with room to spare
    simulation.play(120, (lightShare * 1000) / fps);
    return simulation.pacer.getRung().equals(simulation.pacer.getLadder().getFirst());
  }
}
