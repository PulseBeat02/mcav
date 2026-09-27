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
package me.brandonli.mcav.media.mcv2.encode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.mcv2.Workers;
import org.junit.jupiter.api.Test;

/**
 * The lambda of a live frame from the motion of its source: the measure of the motion, pinned on pictures small enough
 * to compute by hand, and the rule that turns the average motion into a lambda.
 */
final class MotionLambdaTest {

  private static final double BASE = 72;

  /** The samples of a 400x300 frame: every fourth pixel of every fourth row. */
  private static final int SAMPLES = 100 * 75;

  /** A 5x5 grey picture whose four sampled pixels, at the corners, hold the given grey values. */
  private static byte[] corners(final int a, final int b, final int c, final int d) {
    final byte[] rgb = new byte[5 * 5 * 3];
    final int[] values = { a, b, c, d };
    final int[] pixels = { 0, 4, 20, 24 };
    for (int k = 0; k < 4; k++) {
      for (int ch = 0; ch < 3; ch++) {
        rgb[pixels[k] * 3 + ch] = (byte) values[k];
      }
    }
    return rgb;
  }

  @Test
  void blursTheLumaOfEveryFourthPixelOverThreeByThreeSamples() {
    // luma r + 2 g + b of the samples: 40, 80 on the first row, 120, 160 on the second; each blurred sample sums its
    // 3x3 neighbourhood, the edge samples repeated, so the top left one is 4 * 40 + 2 * 80 + 2 * 120 + 160
    assertArrayEquals(
      new int[] { 720, 840, 960, 1080 },
      MotionLambda.blurredLuma(corners(10, 20, 30, 40), 5, 5, Workers.SEQUENTIAL, new int[4], new int[4])
    );
    // 3600 over four samples, in units of 36 per luma step
    assertEquals(25.0, MotionLambda.temporalInformation(new int[] { 720, 840, 960, 1080 }, new int[4], Workers.SEQUENTIAL));
    assertEquals(25.0, MotionLambda.temporalInformation(new int[4], new int[] { 720, 840, 960, 1080 }, Workers.SEQUENTIAL));
    // a picture four pixels wide or less has one column of samples
    assertArrayEquals(
      new int[] { 360 },
      MotionLambda.blurredLuma(corners(10, 20, 30, 40), 1, 1, Workers.SEQUENTIAL, new int[1], new int[1])
    );
  }

  @Test
  void measuresTheSameMotionOnManyWorkers() {
    // many bands of rows and of samples, so every worker has some
    final Random random = new Random(11);
    final byte[] first = new byte[400 * 300 * 3];
    final byte[] second = new byte[first.length];
    random.nextBytes(first);
    random.nextBytes(second);
    final Workers workers = new Workers(ForkJoinPool.commonPool(), 4);
    final int[] blurred = MotionLambda.blurredLuma(first, 400, 300, Workers.SEQUENTIAL, new int[SAMPLES], new int[SAMPLES]);
    assertArrayEquals(blurred, MotionLambda.blurredLuma(first, 400, 300, workers, new int[SAMPLES], new int[SAMPLES]));
    final int[] other = MotionLambda.blurredLuma(second, 400, 300, Workers.SEQUENTIAL, new int[SAMPLES], new int[SAMPLES]);
    assertEquals(
      MotionLambda.temporalInformation(blurred, other, Workers.SEQUENTIAL),
      MotionLambda.temporalInformation(blurred, other, workers)
    );
  }

  @Test
  void keepsTheLambdaUpToTheKneeAndRaisesItAboveUpToFourTimes() {
    assertEquals(1, MotionLambda.raise(Double.NaN));
    assertEquals(1, MotionLambda.raise(0));
    assertEquals(1, MotionLambda.raise(MotionLambda.KNEE));
    assertEquals(Math.pow(2, MotionLambda.EXPONENT), MotionLambda.raise(2 * MotionLambda.KNEE), 1e-12);
    // the two gameplay captures: lambda 195 and 157 at their temporal information
    assertEquals(195, BASE * MotionLambda.raise(16.331), 2);
    assertEquals(157, BASE * MotionLambda.raise(12.333), 2);
    assertEquals(MotionLambda.MAX_RAISE, MotionLambda.raise(1000));
  }

  @Test
  void averagesTheMotionOfConsecutiveFramesAndStartsOverAtACut() {
    final MotionLambda motion = new MotionLambda();
    assertEquals(BASE, motion.lambda(BASE));
    final byte[] still = corners(10, 20, 30, 40);
    // ten grey levels brighter: 40 luma, 360 once blurred, 10 in luma units
    final byte[] brighter = corners(20, 30, 40, 50);
    // the first frame has nothing to be compared with
    motion.observe(still, 5, 5, false, Workers.SEQUENTIAL);
    assertEquals(BASE, motion.lambda(BASE));
    motion.observe(brighter, 5, 5, false, Workers.SEQUENTIAL);
    assertEquals(BASE * MotionLambda.raise(10), motion.lambda(BASE));
    // an unchanged frame moves the average a sixteenth of the way to no motion
    motion.observe(brighter, 5, 5, false, Workers.SEQUENTIAL);
    assertEquals(BASE * MotionLambda.raise(10 - 10 * MotionLambda.SMOOTHING), motion.lambda(BASE));
    // a scene cut starts over at the profile's lambda
    motion.observe(still, 5, 5, true, Workers.SEQUENTIAL);
    assertEquals(BASE, motion.lambda(BASE));
    motion.observe(brighter, 5, 5, false, Workers.SEQUENTIAL);
    assertEquals(BASE * MotionLambda.raise(10), motion.lambda(BASE));
    // and so does another height, or another width
    motion.observe(new byte[5 * 9 * 3], 5, 9, false, Workers.SEQUENTIAL);
    assertEquals(BASE, motion.lambda(BASE));
    motion.observe(still, 5, 5, false, Workers.SEQUENTIAL);
    motion.observe(brighter, 5, 5, false, Workers.SEQUENTIAL);
    assertEquals(BASE * MotionLambda.raise(10), motion.lambda(BASE));
    motion.observe(new byte[9 * 5 * 3], 9, 5, false, Workers.SEQUENTIAL);
    assertEquals(BASE, motion.lambda(BASE));
  }
}
