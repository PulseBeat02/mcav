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

final class Mcv2MotionSearchTest {

  private static final int VECTOR_COMPONENT_MASK = 0xFFFF;

  private static final int SAMPLED_PICTURE_SIZE = 64;

  private static final int WIDTH = 96;

  private static final int HEIGHT = 96;

  @Test
  void shouldPackBothPartsOfAVectorIntoOneInt() {
    final int packedExample = pack(5, -3);
    assertEquals(0x0005FFFD, packedExample);

    final int[][] vectors = { { 0, 0 }, { 5, -3 }, { -7, 12 }, { -32768, 32767 }, { 32767, -32768 } };
    for (final int[] vector : vectors) {
      final int packed = pack(vector[0], vector[1]);
      final int expectedMotionX = vector[0];
      final int motionX = motionX(packed);
      assertEquals(expectedMotionX, motionX);

      final int expectedMotionY = vector[1];
      final int motionY = motionY(packed);
      assertEquals(expectedMotionY, motionY);
    }
  }

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

  private int[] block(final byte[] rgb, final int blockLeft, final int blockTop, final int size, final int motionX, final int motionY) {
    final int[] source = new int[size * size * 3];
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        this.copyPixel(source, row * size + column, rgb, (blockTop + row + motionY) * WIDTH + blockLeft + column + motionX);
      }
    }

    return source;
  }

  private void copyPixel(final int[] destination, final int destinationPixel, final byte[] rgb, final int sourcePixel) {
    for (int channel = 0; channel < 3; channel++) {
      destination[destinationPixel * 3 + channel] = rgb[sourcePixel * 3 + channel] & 255;
    }
  }

  private static int pack(final int motionX, final int motionY) {
    return (int) Mcv2Internals.callForResult(Mcv2.class, null, "packMotion", new Class<?>[] { int.class, int.class }, motionX, motionY);
  }

  private static int motionX(final int vector) {
    return (int) Mcv2Internals.callForResult(Mcv2.class, null, "motionX", new Class<?>[] { int.class }, vector);
  }

  private static int motionY(final int vector) {
    return (int) Mcv2Internals.callForResult(Mcv2.class, null, "motionY", new Class<?>[] { int.class }, vector);
  }

  private static int search(final int[] source, final int range) {
    return seeded(source, range);
  }

  @Test
  void shouldFindAWholePixelDisplacement() {
    final int[] source = this.block(picture(), 32, 32, 16, 5, -3);
    final int expected = (5 << 16) | (-3 & VECTOR_COMPONENT_MASK);
    final int found = search(source, 8);
    assertEquals(expected, found);
  }

  private static int seeded(final int[] source, final int range, final int... seeds) {
    return Mcv2Internals.javaKernels().seeded(picture(), WIDTH, HEIGHT, source, 32, 32, 16, range, seeds);
  }

  private int vector(final int vectorX, final int vectorY) {
    return (vectorX << 16) | (vectorY & VECTOR_COMPONENT_MASK);
  }

  @Test
  void shouldStartASeededSearchFromTheBestSeed() {
    // the true vector is far from zero but one seed is next to it: the diamond walks the last pixel
    final int[] source = this.block(picture(), 32, 32, 16, 10, -7);
    final int expected = this.vector(10, -7);
    final int found = seeded(source, 24, this.vector(0, 0), this.vector(9, -7), this.vector(9, -7), this.vector(-30, 40));
    assertEquals(expected, found);
  }

  @Test
  void shouldKeepASeededSearchInsideTheRange() {
    // seeds past the range are clamped to its edge, and so is every step
    final int[] source = this.block(picture(), 32, 32, 16, 10, 0);
    final int found = seeded(source, 2, this.vector(100, 0), this.vector(-100, 0));
    final int foundX = found >> 16;
    assertEquals(2, foundX);

    // with a range of one pixel the walk takes at most two steps, both improving here
    final int[] near = this.block(picture(), 32, 32, 16, 1, 1);
    final int expectedNear = this.vector(1, 1);
    final int foundNear = seeded(near, 1);
    assertEquals(expectedNear, foundNear);
  }

  private byte[] sampledCosts(final int[][] costs) {
    final byte[] reference = new byte[SAMPLED_PICTURE_SIZE * SAMPLED_PICTURE_SIZE * 3];
    Arrays.fill(reference, (byte) 120);
    for (final int sampleRow : new int[] { 4, 12, 20, 28 }) {
      for (final int sampleColumn : new int[] { 4, 12, 20, 28 }) {
        this.paintCosts(reference, sampleRow, sampleColumn, costs);
      }
    }

    return reference;
  }

  private void paintCosts(final byte[] reference, final int sampleRow, final int sampleColumn, final int[][] costs) {
    for (final int[] cost : costs) {
      final int pixel = (16 + sampleRow + cost[1]) * SAMPLED_PICTURE_SIZE + 16 + sampleColumn + cost[0];
      reference[pixel * 3] = (byte) cost[2];
      reference[pixel * 3 + 1] = 0;
      reference[pixel * 3 + 2] = 0;
    }
  }

  @Test
  void shouldUseANonzeroSeedAcrossALocalMinimum() {
    final byte[] reference = this.sampledCosts(new int[][] { { 0, 0, 100 }, { 2, 2, 0 } });
    final int expected = this.vector(2, 2);
    final int found = Mcv2Internals.javaKernels().seeded(
      reference,
      SAMPLED_PICTURE_SIZE,
      SAMPLED_PICTURE_SIZE,
      new int[32 * 32 * 3],
      16,
      16,
      32,
      2,
      new int[] { this.vector(2, 2) }
    );
    assertEquals(expected, found);
  }

  @Test
  void shouldStopAfterTwiceTheRangeEvenWhenTheNextStepWouldImprove() {
    final byte[] reference = this.sampledCosts(new int[][] {
      { 0, 0, 100 },
      { -1, 0, 90 },
      { -2, 0, 80 },
      { -2, -1, 70 },
      { -2, -2, 60 },
      { -1, -2, 50 },
    });
    final int expected = this.vector(-2, -2);
    final int found = Mcv2Internals.javaKernels().seeded(
      reference,
      SAMPLED_PICTURE_SIZE,
      SAMPLED_PICTURE_SIZE,
      new int[32 * 32 * 3],
      16,
      16,
      32,
      2,
      new int[0]
    );
    assertEquals(expected, found);
  }

  @Test
  void shouldMeasureBlocksNearTheEdgeWithClamping() {
    // a block in the corner, displaced so its samples clamp: the general sampling path
    final byte[] rgb = picture();
    final int[] source = new int[8 * 8 * 3];
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        final int referenceRow = Math.max(0, row - 4);
        final int referenceColumn = Math.max(0, column - 4);
        this.copyPixel(source, row * 8 + column, rgb, referenceRow * WIDTH + referenceColumn);
      }
    }

    final int found = Mcv2Internals.javaKernels().seeded(rgb, WIDTH, HEIGHT, source, 0, 0, 8, 4, new int[] { this.vector(-4, -4) });
    final boolean withinRange = Math.abs(found >> 16) <= 4 && Math.abs((short) found) <= 4;
    assertEquals(true, withinRange);

    final int expected = this.vector(-4, -4);
    assertEquals(expected, found, "the clamped corner has an exact four-pixel displacement");
  }
}
