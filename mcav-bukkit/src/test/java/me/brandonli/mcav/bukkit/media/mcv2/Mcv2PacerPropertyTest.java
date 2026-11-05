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
import org.checkerframework.checker.nullness.qual.Nullable;

final class Mcv2PacerPropertyTest {

  private static final String SEED = "20260926";

  private static final long SECOND = 1_000_000_000L;

  private static final List<int[]> SIZES = List.of(new int[] { 1920, 1080 }, new int[] { 1280, 720 }, new int[] { 960, 540 });

  private static final double FULL_WIDTH = 1920.0;
  private static final double FULL_HEIGHT = 1080.0;

  private static final int MILLISECONDS_PER_SECOND = 1000;

  private static final List<Mcv2Pacer.Preset> PRESETS = List.of(
    Mcv2Pacer.Preset.of("live", 1),
    Mcv2Pacer.Preset.of("live-fast", 0.6),
    Mcv2Pacer.Preset.of("fastest", 0.35)
  );

  @Provide
  Arbitrary<Double> frameRates() {
    return Arbitraries.of(24.0, 25.0, 30.0, 50.0, 60.0);
  }

  private double frameMilliseconds(final Mcv2Pacer.Rung rung, final double framesPerSecond) {
    return (1000.0 * rung.getDivisor()) / framesPerSecond;
  }

  @Property(seed = SEED, tries = 300)
  boolean shouldNeverStayLongOverItsTime(
    @ForAll("frameRates") final double framesPerSecond,
    @ForAll @IntRange(min = 1, max = 3) final int sizes,
    @ForAll @IntRange(min = 1, max = 3) final int presets,
    @ForAll @DoubleRange(min = 1, max = 3000) final double fullMilliseconds,
    @ForAll @DoubleRange(min = 0, max = 0.1) final double noise,
    @ForAll final long seed
  ) {
    final Simulation simulation = new Simulation(sizes, presets, framesPerSecond, noise, seed);
    simulation.play(180, fullMilliseconds);
    final List<Stretch> stretches = simulation.finish();
    for (int stretchIndex = 0; stretchIndex < stretches.size(); stretchIndex++) {
      final Stretch stretch = stretches.get(stretchIndex);
      if (stretch.rung().isDithered()) {
        continue;
      }
      final double rungMilliseconds =
        (fullMilliseconds * stretch.rung().getWidth() * stretch.rung().getHeight() * stretch.rung().getPreset().getCost()) /
        (FULL_WIDTH * FULL_HEIGHT);
      final double seconds = (stretch.to() - stretch.from()) / (double) SECOND;
      final boolean over = rungMilliseconds * (1 - noise) > this.frameMilliseconds(stretch.rung(), framesPerSecond);
      // it waits for the rung's first samples (and, on the first rung, for the encoder to warm up), then a second
      final double frame =
        Math.max(rungMilliseconds * (1 + noise), this.frameMilliseconds(stretch.rung(), framesPerSecond)) / MILLISECONDS_PER_SECOND;
      final double allowed =
        Mcv2Pacer.DOWN_SECONDS + (Mcv2Pacer.MINIMUM_SAMPLES + 2) * frame + 1 + (stretchIndex == 0 ? Mcv2Pacer.STARTUP_SECONDS : 0);
      if (over && seconds > allowed) {
        return false;
      }
      // a step up under a steady load is never undone
      if (stretch.climbed() && stretch.leftDown()) {
        return false;
      }
    }

    return true;
  }

  @Property(seed = SEED, tries = 300)
  boolean shouldClimbBackWhenTheLoadDrops(
    @ForAll("frameRates") final double framesPerSecond,
    @ForAll @IntRange(min = 1, max = 3) final int sizes,
    @ForAll @IntRange(min = 1, max = 3) final int presets,
    @ForAll @DoubleRange(min = 20, max = 3000) final double heavyMilliseconds,
    @ForAll @DoubleRange(min = 0.05, max = 0.55) final double lightShare,
    @ForAll @DoubleRange(min = 0, max = 0.1) final double noise,
    @ForAll final long seed
  ) {
    final Simulation simulation = new Simulation(sizes, presets, framesPerSecond, noise, seed);
    simulation.play(20, heavyMilliseconds);
    // the top rung fits in lightShare of its frame time, with room to spare
    simulation.play(120, (lightShare * MILLISECONDS_PER_SECOND) / framesPerSecond);
    return simulation.pacer.getRung().equals(simulation.pacer.getLadder().getFirst());
  }

  private record Stretch(Mcv2Pacer.Rung rung, long from, long to, boolean climbed, boolean leftDown) {}

  private static final class Simulation {

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

    Simulation(final int sizes, final int presets, final double framesPerSecond, final double noise, final long seed) {
      this.pacer = new Mcv2Pacer(
        SIZES.subList(0, sizes),
        presets == 1 ? List.of(Mcv2Pacer.Preset.ONLY) : PRESETS.subList(0, presets),
        Mcv2Pacer.Floor.DITHERED_MAPS
      );
      this.interval = Math.round(SECOND / framesPerSecond);
      this.noise = noise;
      this.random = new Random(seed);
    }

    private double costOf(final Mcv2Pacer.Rung rung, final double fullMilliseconds) {
      final double scale = 1 + this.noise * (2 * this.random.nextDouble() - 1);
      return (fullMilliseconds * scale * rung.getWidth() * rung.getHeight() * rung.getPreset().getCost()) / (FULL_WIDTH * FULL_HEIGHT);
    }

    private void record(final Mcv2Pacer.Change change, final long at) {
      this.stretches.add(new Stretch(change.getFrom(), this.started, at, this.climbed, change.isDown()));
      this.started = at;
      this.climbed = !change.isDown() && !change.getFrom().isDithered();
    }

    void play(final double seconds, final double fullMilliseconds) {
      final long end = this.now + (long) (seconds * SECOND);
      while (this.now < end) {
        final long arrival = this.now + this.interval;
        this.finishEncodingsBy(arrival, fullMilliseconds);
        this.now = arrival;
        this.arrive(arrival, fullMilliseconds);
      }
    }

    private void finishEncodingsBy(final long arrival, final double fullMilliseconds) {
      while (this.encoding && this.busyUntil <= arrival) {
        this.encoding = false;
        final Mcv2Pacer.@Nullable Change change = this.pacer
          .reportEncodedFrame(this.cost, Mcv2FrameKind.PREDICTED, this.busyUntil)
          .orElse(null);
        if (change != null) {
          this.record(change, this.busyUntil);
          this.waiting = false;
        }

        if (this.waiting) {
          this.waiting = false;
          this.start(this.busyUntil, fullMilliseconds);
        }
      }
    }

    private void arrive(final long arrival, final double fullMilliseconds) {
      final Mcv2Pacer.@Nullable Change tried = this.pacer.arrive(arrival).orElse(null);
      if (tried != null) {
        this.record(tried, arrival);
      }

      if (!this.pacer.isEncoded()) {
        return;
      }

      if (this.encoding) {
        this.waiting = true;
        return;
      }

      this.start(arrival, fullMilliseconds);
    }

    private void start(final long at, final double fullMilliseconds) {
      this.cost = this.costOf(this.pacer.getRung(), fullMilliseconds);
      this.busyUntil = at + (long) (this.cost * 1e6);
      this.encoding = true;
    }

    List<Stretch> finish() {
      final List<Stretch> all = new ArrayList<>(this.stretches);
      all.add(new Stretch(this.pacer.getRung(), this.started, this.now, this.climbed, false));
      return all;
    }
  }
}
