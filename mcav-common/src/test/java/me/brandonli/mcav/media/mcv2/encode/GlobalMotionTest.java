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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.mcv2.Workers;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The global motion estimate: phase correlation plus the exact refinement, on circularly shifted pictures. */
final class GlobalMotionTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(GlobalMotion.class);
  }

  /** A textured picture: smooth waves plus a little seeded noise, so every displacement is distinguishable. */
  private static byte[] texture(final int width, final int height) {
    final Random random = new Random(width * 31L + height);
    final byte[] rgb = new byte[width * height * 3];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int at = (y * width + x) * 3;
        final double wave = 50 * Math.sin(x / 7.0) * Math.cos(y / 5.0) + 30 * Math.sin((x - 2 * y) / 11.0);
        for (int c = 0; c < 3; c++) {
          rgb[at + c] = (byte) Math.min(255, Math.max(0, (int) (128 + wave + 20 * c + random.nextInt(24))));
        }
      }
    }
    return rgb;
  }

  /** The picture whose pixel (x, y) is the reference's (x + dx, y + dy), wrapping around. */
  private static byte[] shifted(final byte[] reference, final int width, final int height, final int dx, final int dy) {
    final byte[] rgb = new byte[reference.length];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int from = (Math.floorMod(y + dy, height) * width + Math.floorMod(x + dx, width)) * 3;
        System.arraycopy(reference, from, rgb, (y * width + x) * 3, 3);
      }
    }
    return rgb;
  }

  private static int estimate(final int width, final int height, final int dx, final int dy) {
    final byte[] reference = texture(width, height);
    return GlobalMotion.estimate(shifted(reference, width, height, dx, dy), reference, width, height);
  }

  @ParameterizedTest(name = "({0}, {1})")
  @CsvSource({ "0, 0", "8, -12", "-8, 12", "13, 2", "-21, -30" })
  void findsAPan(final int dx, final int dy) {
    assertEquals(((2 * dx) << 16) | ((2 * dy) & 0xFFFF), estimate(256, 160, dx, dy));
  }

  @Test
  void staysInsideTheLargestDisplacement() {
    final int vector = estimate(512, 512, 132, 132);
    final int x = vector >> 16;
    final int y = (short) vector;
    assertTrue(x <= 2 * GlobalMotion.MAX_RANGE && x >= 2 * (GlobalMotion.MAX_RANGE - 3), "x " + x);
    assertTrue(y <= 2 * GlobalMotion.MAX_RANGE && y >= 2 * (GlobalMotion.MAX_RANGE - 3), "y " + y);
  }

  @Test
  void handlesPicturesSmallerThanOneQuarterSample() {
    final byte[] picture = texture(3, 2);
    assertEquals(0, GlobalMotion.estimate(picture, picture, 3, 2));
  }

  @Test
  void estimatesTheSameOnAnyNumberOfWorkers() {
    final byte[] reference = texture(320, 180);
    final byte[] source = shifted(reference, 320, 180, 9, -4);
    final ForkJoinPool pool = new ForkJoinPool(4);
    try {
      final int sequential = GlobalMotion.estimate(source, reference, 320, 180);
      assertEquals(((2 * 9) << 16) | ((2 * -4) & 0xFFFF), sequential);
      assertEquals(sequential, GlobalMotion.estimate(source, reference, 320, 180, new Workers(pool, 4)));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void projectsHalfResolutionLuma() {
    // a 3x3 picture: the samples at even columns and rows, (0, 0), (2, 0), (0, 2) and (2, 2), each 4 times its luma
    final byte[] rgb = new byte[3 * 3 * 3];
    for (int i = 0; i < 9; i++) {
      rgb[i * 3] = (byte) i;
      rgb[i * 3 + 1] = (byte) i;
      rgb[i * 3 + 2] = (byte) i;
    }
    final long[] sums = GlobalMotion.projections(rgb, 3, 3, Workers.SEQUENTIAL);
    // columns 0 and 2 over rows 0 and 2, then rows 0 and 2 over columns 0 and 2
    assertEquals(4 * (0 + 6), sums[0]);
    assertEquals(4 * (2 + 8), sums[1]);
    assertEquals(4 * (0 + 2), sums[2]);
    assertEquals(4 * (6 + 8), sums[3]);
  }

  @Test
  void alignsProjectionsPreferringTheSmallerShift() {
    final long[] previous = { 5, 1, 9, 4, 7, 3, 8, 2 };
    final long[] current = new long[8];
    for (int i = 0; i < 8; i++) {
      current[i] = previous[Math.min(i + 2, 7)];
    }
    assertEquals(2, GlobalMotion.align(previous, current, 0, 8));
    // a flat axis matches every shift equally: no motion
    assertEquals(0, GlobalMotion.align(new long[] { 3, 3, 3, 3 }, new long[] { 3, 3, 3, 3 }, 0, 4));
  }

  @Test
  void estimatesLiveMotionFromProjections() {
    final int width = 256;
    final int height = 160;
    final byte[] reference = texture(width, height);
    final ForkJoinPool pool = new ForkJoinPool(3);
    try {
      for (final Workers workers : new Workers[] { Workers.SEQUENTIAL, new Workers(pool, 3) }) {
        for (final int[] shift : new int[][] { { 0, 0 }, { 8, -6 }, { -12, 4 } }) {
          final byte[] source = shifted(reference, width, height, shift[0], shift[1]);
          final long[] before = GlobalMotion.projections(reference, width, height, workers);
          final long[] now = GlobalMotion.projections(source, width, height, workers);
          final int vector = GlobalMotion.estimateLive(source, reference, width, height, before, now, workers);
          assertEquals(((2 * shift[0]) << 16) | ((2 * shift[1]) & 0xFFFF), vector);
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void keepsLiveCandidatesInsideTheLargestDisplacement() {
    // projections that align at the edge of the range: the refinement's candidates past it are skipped
    final int width = 512;
    final int height = 512;
    final byte[] reference = texture(width, height);
    final byte[] source = shifted(reference, width, height, 132, 132);
    final int vector = GlobalMotion.estimateLive(
      source,
      reference,
      width,
      height,
      GlobalMotion.projections(reference, width, height, Workers.SEQUENTIAL),
      GlobalMotion.projections(source, width, height, Workers.SEQUENTIAL),
      Workers.SEQUENTIAL
    );
    assertTrue(Math.abs(vector >> 16) <= 2 * GlobalMotion.MAX_RANGE);
    assertTrue(Math.abs((short) vector) <= 2 * GlobalMotion.MAX_RANGE);
    // projections made to align 64 samples, the largest displacement, down and not across: the refinement's rows
    // past the edge are skipped while its columns are measured
    final long[] before = new long[256 + 256];
    final long[] after = new long[256 + 256];
    final Random random = new Random(3);
    for (int i = 0; i < 256; i++) {
      before[256 + i] = random.nextInt(1_000_000);
    }
    for (int i = 0; i < 192; i++) {
      after[256 + i] = before[256 + i + 64];
    }
    final int vertical = GlobalMotion.estimateLive(
      new byte[512 * 512 * 3],
      new byte[512 * 512 * 3],
      512,
      512,
      before,
      after,
      Workers.SEQUENTIAL
    );
    assertEquals(0, vertical);
  }
}
