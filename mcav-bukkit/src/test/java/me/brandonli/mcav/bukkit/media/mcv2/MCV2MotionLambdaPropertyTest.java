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

import java.util.Arrays;
import java.util.List;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.MotionLambda;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.Workers;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * The lambda of live frames from their motion is stable: it stays between the profile's lambda and four times it,
 * steady motion gives a steady lambda, a change of motion moves it toward its new value without overshooting or
 * turning back, more motion never gives a lower lambda, and a new scene starts at the profile's lambda. An adaptive
 * profile's choice of search switches only where the average motion leaves the band between its two thresholds, and
 * never back and forth while it stays inside.
 */
final class MCV2MotionLambdaPropertyTest {

  private static final String SEED = "20260927";

  private static final double BASE = 72;

  @Property(seed = SEED, tries = 300)
  boolean staysBetweenTheProfilesLambdaAndFourTimesIt(
    @ForAll @Size(min = 1, max = 60) final List<@DoubleRange(min = 0, max = 200) Double> motions,
    @ForAll @DoubleRange(min = 1, max = 2000) final double base
  ) {
    final MotionLambda motion = new MotionLambda();
    for (final double information : motions) {
      motion.add(information);
      final double lambda = motion.lambda(base);
      if (lambda < base || lambda > base * MotionLambda.MAX_RAISE) {
        return false;
      }
    }
    return true;
  }

  @Property(seed = SEED, tries = 300)
  boolean keepsASteadyLambdaForSteadyMotion(
    @ForAll @DoubleRange(min = 0, max = 200) final double information,
    @ForAll @IntRange(min = 1, max = 60) final int frames
  ) {
    final MotionLambda motion = new MotionLambda();
    for (int frameNumber = 0; frameNumber < frames; frameNumber++) {
      motion.add(information);
      if (motion.lambda(BASE) != BASE * MotionLambda.raise(information)) {
        return false;
      }
    }
    return true;
  }

  @Property(seed = SEED, tries = 300)
  boolean movesTowardANewMotionWithoutOvershootingOrTurningBack(
    @ForAll @DoubleRange(min = 0, max = 200) final double before,
    @ForAll @DoubleRange(min = 0, max = 200) final double after,
    @ForAll @IntRange(min = 1, max = 120) final int frames
  ) {
    final MotionLambda motion = new MotionLambda();
    motion.add(before);
    final double target = BASE * MotionLambda.raise(after);
    double previous = motion.lambda(BASE);
    final double sign = Math.signum(target - previous);
    for (int frameNumber = 0; frameNumber < frames; frameNumber++) {
      motion.add(after);
      final double lambda = motion.lambda(BASE);
      // every step goes the way of the new value, and none passes it
      if ((lambda - previous) * sign < 0 || (target - lambda) * sign < 0) {
        return false;
      }
      previous = lambda;
    }
    return true;
  }

  @Property(seed = SEED, tries = 300)
  boolean neverGivesMoreMotionALowerLambda(
    @ForAll @DoubleRange(min = 0, max = 200) final double oneLambda,
    @ForAll @DoubleRange(min = 0, max = 200) final double otherLambda
  ) {
    return MotionLambda.raise(Math.min(oneLambda, otherLambda)) <= MotionLambda.raise(Math.max(oneLambda, otherLambda));
  }

  @Property(seed = SEED, tries = 200)
  boolean startsANewSceneAtTheProfilesLambda(
    @ForAll @Size(min = 1, max = 20) final List<@IntRange(min = 0, max = 255) Integer> greys,
    @ForAll @IntRange(min = 1, max = 40) final int width,
    @ForAll @IntRange(min = 1, max = 40) final int height
  ) {
    final MotionLambda motion = new MotionLambda();
    for (final int grey : greys) {
      final byte[] rgb = new byte[width * height * 3];
      Arrays.fill(rgb, (byte) grey);
      motion.observe(rgb, width, height, false, Workers.SEQUENTIAL);
    }
    motion.observe(new byte[width * height * 3], width, height, true, Workers.SEQUENTIAL);
    return motion.lambda(BASE) == BASE;
  }
}
