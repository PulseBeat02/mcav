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

import java.util.Arrays;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.MotionLambda;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.Workers;
import org.junit.jupiter.api.Test;

/**
 * Tests the temporal information of frames large enough to be summed in several bands, as a 1080p frame is: every
 * sample counts once, whichever band sums it.
 */
final class MCV2MotionLambdaBandsTest {

  /** More samples than three bands of the workers take, with a band left partly full. */
  private static final int SAMPLES = 50_000;

  /** One luma step in the units of the blurred samples: 36 per step, as nine samples of r + 2 g + b each. */
  private static final int LUMA_STEP = 36;

  @Test
  void countsEverySampleOfEveryBandOnce() {
    final int[] current = new int[SAMPLES];
    Arrays.fill(current, LUMA_STEP);
    final int[] previous = new int[SAMPLES];
    assertEquals(1.0, MotionLambda.temporalInformation(current, previous, Workers.SEQUENTIAL));
    // one sample differing by a thousand steps moves the mean by a thousand over the samples, wherever it lies
    final int[] spike = new int[SAMPLES];
    spike[SAMPLES - 1] = 1_000 * LUMA_STEP;
    assertEquals(1_000.0 / SAMPLES, MotionLambda.temporalInformation(spike, previous, Workers.SEQUENTIAL), 1e-12);
  }
}
