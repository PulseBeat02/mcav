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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.mcv2.Workers;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/** The live frame's choice of global vector and its scene cut test. */
final class LiveAnalysisTest {

  private static final double LAMBDA = 65.255994022;

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(LiveAnalysis.class);
  }

  private static int vector(final int x, final int y) {
    return (x << 16) | (y & 0xFFFF);
  }

  /** The picture whose pixel (x, y) is the given one's (x + dx, y + dy), clamped at the edges. */
  private static byte[] shifted(final byte[] rgb, final int width, final int height, final int dx, final int dy) {
    final byte[] out = new byte[rgb.length];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int sx = Math.min(Math.max(x + dx, 0), width - 1);
        final int sy = Math.min(Math.max(y + dy, 0), height - 1);
        System.arraycopy(rgb, (sy * width + sx) * 3, out, (y * width + x) * 3, 3);
      }
    }
    return out;
  }

  @Test
  void choosesTheVectorSkipCodesBest() {
    final int width = 100;
    final int height = 70;
    final byte[] reference = Mcv2EncoderTest.texture(width, height, 5);
    final byte[] source = shifted(reference, width, height, 3, -2);
    final ForkJoinPool pool = new ForkJoinPool(3);
    try {
      for (final Workers workers : new Workers[] { Workers.SEQUENTIAL, new Workers(pool, 3) }) {
        final LiveAnalysis.Result pan = LiveAnalysis.analyze(
          source,
          reference,
          width,
          height,
          LAMBDA,
          45,
          new int[] { 0, vector(6, -4) },
          workers
        );
        assertEquals(1, pan.vector());
        assertFalse(pan.sceneCut());
        final LiveAnalysis.Result still = LiveAnalysis.analyze(
          reference,
          reference,
          width,
          height,
          LAMBDA,
          45,
          new int[] { 0, vector(6, -4) },
          workers
        );
        assertEquals(0, still.vector());
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void samplesHalfPixelsAndFindsSceneCuts() {
    final int width = 64;
    final int height = 64;
    final byte[] reference = Mcv2EncoderTest.texture(width, height, 6);
    // every half-pixel case of the sampling, near the edges too
    for (final int v : new int[] { vector(1, 0), vector(0, 1), vector(1, 1), vector(-7, 9), vector(9, -7) }) {
      final LiveAnalysis.Result result = LiveAnalysis.analyze(
        reference,
        reference,
        width,
        height,
        LAMBDA,
        45,
        new int[] { v },
        Workers.SEQUENTIAL
      );
      assertEquals(0, result.vector());
      assertFalse(result.sceneCut());
    }
    final byte[] inverted = reference.clone();
    for (int i = 0; i < inverted.length; i++) {
      inverted[i] = (byte) (255 - (inverted[i] & 0xFF));
    }
    assertTrue(LiveAnalysis.analyze(inverted, reference, width, height, LAMBDA, 45, new int[] { 0 }, Workers.SEQUENTIAL).sceneCut());
    // the threshold decides
    assertFalse(LiveAnalysis.analyze(inverted, reference, width, height, LAMBDA, 1000, new int[] { 0 }, Workers.SEQUENTIAL).sceneCut());
  }
}
