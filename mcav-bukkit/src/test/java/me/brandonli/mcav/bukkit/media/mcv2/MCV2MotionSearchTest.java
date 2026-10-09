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
import org.junit.jupiter.api.Test;

/** The local motion search: its step ladder, and the vectors it finds on smooth pictures. */
final class MCV2MotionSearchTest {

  private static final int WIDTH = 96;

  private static final int HEIGHT = 96;

  @Test
  void packsBothPartsOfAVectorIntoOneInt() {
    assertEquals(0x0005FFFD, pack(5, -3));
    final int[][] vectors = { { 0, 0 }, { 5, -3 }, { -7, 12 }, { -32768, 32767 }, { 32767, -32768 } };
    for (final int[] vector : vectors) {
      final int packed = pack(vector[0], vector[1]);
      assertEquals(vector[0], motionX(packed));
      assertEquals(vector[1], motionY(packed));
    }
  }

  /** A smooth picture used to check exact seeded motion. */
  private static byte[] picture() {
    final byte[] rgb = new byte[WIDTH * HEIGHT * 3];
    for (int row = 0; row < HEIGHT; row++) {
      for (int column = 0; column < WIDTH; column++) {
        final int offset = (row * WIDTH + column) * 3;
        rgb[offset] = (byte) (2 * (int) (60 + 50 * Math.sin(column / 9.0) + 10 * Math.cos(row / 13.0)));
        rgb[offset + 1] = (byte) (2 * (int) (60 + 40 * Math.cos(row / 8.0) + 15 * Math.sin(column / 11.0)));
        rgb[offset + 2] = (byte) (2 * (int) (60 + 30 * Math.sin((column + row) / 10.0)));
      }
    }
    return rgb;
  }

  /** The block at (blockLeft, blockTop) of the picture displaced by a vector in whole pixels. */
  private static int[] block(
    final byte[] rgb,
    final int blockLeft,
    final int blockTop,
    final int size,
    final int motionX,
    final int motionY
  ) {
    final int[] source = new int[size * size * 3];
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        for (int channel = 0; channel < 3; channel++) {
          source[(row * size + column) * 3 + channel] =
            rgb[((blockTop + row + motionY) * WIDTH + blockLeft + column + motionX) * 3 + channel] & 255;
        }
      }
    }
    return source;
  }

  private static int pack(final int motionX, final int motionY) {
    return (int) Mcv2Internals.invoke(MCV2.class, null, "packMotion", new Class<?>[] { int.class, int.class }, motionX, motionY);
  }

  private static int motionX(final int vector) {
    return (int) Mcv2Internals.invoke(MCV2.class, null, "motionX", new Class<?>[] { int.class }, vector);
  }

  private static int motionY(final int vector) {
    return (int) Mcv2Internals.invoke(MCV2.class, null, "motionY", new Class<?>[] { int.class }, vector);
  }

  private static int search(final int[] source, final int range) {
    return seeded(source, range);
  }

  @Test
  void findsAWholePixelDisplacement() {
    final int[] source = block(picture(), 32, 32, 16, 5, -3);
    assertEquals((5 << 16) | (-3 & 0xFFFF), search(source, 8));
  }

  private static int seeded(final int[] source, final int range, final int... seeds) {
    return Mcv2Internals.javaKernels().seeded(picture(), WIDTH, HEIGHT, source, 32, 32, 16, range, seeds);
  }

  private static int vector(final int vectorX, final int vectorY) {
    return (vectorX << 16) | (vectorY & 0xFFFF);
  }

  @Test
  void seededSearchStartsFromTheBestSeed() {
    // the true vector is far from zero but one seed is next to it: the diamond walks the last pixel
    final int[] source = block(picture(), 32, 32, 16, 10, -7);
    assertEquals(vector(10, -7), seeded(source, 24, vector(0, 0), vector(9, -7), vector(9, -7), vector(-30, 40)));
  }

  @Test
  void seededSearchStaysInsideTheRange() {
    // seeds past the range are clamped to its edge, and so is every step
    final int[] source = block(picture(), 32, 32, 16, 10, 0);
    final int found = seeded(source, 2, vector(100, 0), vector(-100, 0));
    assertEquals(2, found >> 16);
    // with a range of one pixel the walk takes at most two steps, both improving here
    final int[] near = block(picture(), 32, 32, 16, 1, 1);
    assertEquals(vector(1, 1), seeded(near, 1));
  }

  private static byte[] sampledCosts(final int[][] costs) {
    final byte[] reference = new byte[64 * 64 * 3];
    Arrays.fill(reference, (byte) 120);
    for (final int sampleRow : new int[] { 4, 12, 20, 28 }) {
      for (final int sampleColumn : new int[] { 4, 12, 20, 28 }) {
        for (final int[] cost : costs) {
          final int pixel = (16 + sampleRow + cost[1]) * 64 + 16 + sampleColumn + cost[0];
          reference[pixel * 3] = (byte) cost[2];
          reference[pixel * 3 + 1] = 0;
          reference[pixel * 3 + 2] = 0;
        }
      }
    }
    return reference;
  }

  @Test
  void usesANonzeroSeedAcrossALocalMinimum() {
    final byte[] reference = sampledCosts(new int[][] { { 0, 0, 100 }, { 2, 2, 0 } });
    assertEquals(
      vector(2, 2),
      Mcv2Internals.javaKernels().seeded(reference, 64, 64, new int[32 * 32 * 3], 16, 16, 32, 2, new int[] { vector(2, 2) })
    );
  }

  @Test
  void stopsAfterTwiceTheRangeEvenWhenTheNextStepWouldImprove() {
    final byte[] reference = sampledCosts(new int[][] {
      { 0, 0, 100 },
      { -1, 0, 90 },
      { -2, 0, 80 },
      { -2, -1, 70 },
      { -2, -2, 60 },
      { -1, -2, 50 },
    });
    assertEquals(vector(-2, -2), Mcv2Internals.javaKernels().seeded(reference, 64, 64, new int[32 * 32 * 3], 16, 16, 32, 2, new int[0]));
  }

  @Test
  void measuresBlocksNearTheEdgeWithClamping() {
    // a block in the corner, displaced so its samples clamp: the general sampling path
    final byte[] rgb = picture();
    final int[] source = new int[8 * 8 * 3];
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        final int referenceRow = Math.max(0, row - 4);
        final int referenceColumn = Math.max(0, column - 4);
        for (int channel = 0; channel < 3; channel++) {
          source[(row * 8 + column) * 3 + channel] = rgb[(referenceRow * WIDTH + referenceColumn) * 3 + channel] & 0xFF;
        }
      }
    }
    final int found = Mcv2Internals.javaKernels().seeded(rgb, WIDTH, HEIGHT, source, 0, 0, 8, 4, new int[] { vector(-4, -4) });
    assertEquals(true, Math.abs(found >> 16) <= 4 && Math.abs((short) found) <= 4);
    assertEquals(vector(-4, -4), found, "the clamped corner has an exact four-pixel displacement");
  }
}
