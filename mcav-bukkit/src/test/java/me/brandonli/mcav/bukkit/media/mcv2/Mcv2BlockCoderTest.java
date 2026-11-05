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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

final class Mcv2BlockCoderTest {

  private static final int ROOT_SIZE = 32;

  private static final double LAMBDA = 65.255994022;

  private static final int REFERENCE_LEVEL_BASE = 40;
  private static final int REFERENCE_LEVEL_SPREAD = 160;

  private byte[] reference(final int low, final int high) {
    final Random random = new Random(8);
    final byte[] rgb = new byte[8 * 8 * 3];
    for (int index = 0; index < rgb.length; index++) {
      rgb[index] = (byte) (low + random.nextInt(high - low));
    }

    return rgb;
  }

  private static Mcv2BlockState code(final byte[] source, final byte[] reference) {
    final Mcv2BlockState job = new Mcv2BlockState(
      source,
      reference,
      8,
      8,
      Mcv2FrameKind.PREDICTED,
      Mcv2BlockState.Thresholds.STANDARD,
      LAMBDA,
      null
    );
    job.code(8, 2, 0, 0, 0, 0);
    return job;
  }

  @Test
  void shouldEndTheLadderOnAPerfectResidual() {
    // Sixteen luma levels require q=2 and sixteen nodes of four.
    final byte[] reference = this.reference(40, 200);
    final byte[] source = reference.clone();
    for (int index = 0; index < source.length; index++) {
      source[index] = (byte) ((source[index] & 0xFF) + 16);
    }

    final Mcv2BlockState job = code(source, reference);
    {
      final long distortion = job.distortion(2, 0);
      assertEquals(0, distortion);
      final int mode = job.mode(2, 0);
      assertEquals(Mcv2Decoder.MODE_COMPACT, mode);
      final int quantizer = job.quantizer(2, 0);
      assertEquals(2, quantizer);
      final byte[] expectedRecord = new byte[] { 0, 0, 0x44, 0x44, 0x44, 0x44, 0x44, 0x44, 0x44, 0x44 };
      final byte[] record = job.record(2, 0);
      assertArrayEquals(expectedRecord, record);
    }
  }

  @Test
  void shouldNeedTheFinestQuantizerThatHoldsTheFittedValues() {
    final float[] fit = new float[16];
    // the sixteen luma nibbles of the 4x4 grid classes hold -8 to 7 steps
    fit[0] = 7;
    final int quantizerAtSeven = Mcv2BlockState.neededQuantizer(fit);
    assertEquals(0, quantizerAtSeven);

    fit[0] = -8.5f;
    final int quantizerAtMinusEightPointFive = Mcv2BlockState.neededQuantizer(fit);
    assertEquals(0, quantizerAtMinusEightPointFive);

    fit[0] = -8.6f;
    final int quantizerAtMinusEightPointSix = Mcv2BlockState.neededQuantizer(fit);
    assertEquals(1, quantizerAtMinusEightPointSix);

    fit[0] = 7.5f;
    final int quantizerAtSevenPointFive = Mcv2BlockState.neededQuantizer(fit);
    assertEquals(1, quantizerAtSevenPointFive);

    fit[0] = 14.9f;
    final int quantizerAtFourteenPointNine = Mcv2BlockState.neededQuantizer(fit);
    assertEquals(1, quantizerAtFourteenPointNine);

    fit[0] = 15;
    final int quantizerAtFifteen = Mcv2BlockState.neededQuantizer(fit);
    assertEquals(2, quantizerAtFifteen);

    fit[0] = 1000;
    final int quantizerAtThousand = Mcv2BlockState.neededQuantizer(fit);
    assertEquals(2, quantizerAtThousand);

    fit[0] = -1000;
    final int quantizerAtMinusThousand = Mcv2BlockState.neededQuantizer(fit);
    assertEquals(2, quantizerAtMinusThousand);
  }

  @Test
  void shouldHalveABlockIntoTheRoundedMeansOfItsSquares() {
    // every 2x2 square of a 4x4 block around its own level, which the square's mean restores
    final int[] block = new int[4 * 4 * 3];
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        this.setWobbledPixel(block, row, column);
      }
    }

    final int[] half = new int[2 * 2 * 3];
    Mcv2Internals.javaKernels().halve(block, 4, half);
    final int[] expectedHalf = new int[] { 20, 21, 22, 60, 61, 62, 100, 101, 102, 140, 141, 142 };
    assertArrayEquals(expectedHalf, half);

    // a mean of 2.5 rounds up, one of 0.25 down
    final int[] square = { 1, 0, 255, 2, 0, 255, 3, 0, 255, 4, 1, 254 };
    final int[] mean = new int[3];
    Mcv2Internals.javaKernels().halve(square, 2, mean);
    final int[] expectedMean = new int[] { 3, 0, 255 };
    assertArrayEquals(expectedMean, mean);
  }

  private void setWobbledPixel(final int[] block, final int row, final int column) {
    final int level = 40 * (2 * (row / 2) + column / 2) + 20;
    final int wobble = (column % 2 == 0 ? 3 : -1) * (row % 2 == 0 ? 1 : -1);
    for (int channel = 0; channel < 3; channel++) {
      block[(row * 4 + column) * 3 + channel] = level + channel + wobble;
    }
  }

  @Test
  void shouldCodeAUniformKeyframeBlockAsSolid() {
    final byte[] source = new byte[8 * 8 * 3];
    for (int offset = 0; offset < source.length; offset += 3) {
      source[offset] = 10;
      source[offset + 1] = 20;
      source[offset + 2] = 30;
    }

    final Mcv2BlockState job = new Mcv2BlockState(
      source,
      new byte[0],
      8,
      8,
      Mcv2FrameKind.KEYFRAME,
      Mcv2BlockState.Thresholds.STANDARD,
      LAMBDA,
      null
    );
    job.code(8, 2, 0, 0, 0, Mcv2BlockState.NO_VECTOR);

    final int mode = job.mode(2, 0);
    assertEquals(Mcv2Decoder.MODE_SOLID, mode);

    final byte[] expectedRecord = new byte[] { 10, 20, 30 };
    final byte[] record = job.record(2, 0);
    assertArrayEquals(expectedRecord, record);

    final long distortion = job.distortion(2, 0);
    assertEquals(0, distortion);
  }

  @Test
  void shouldGiveABlockBelowTheSearchSizeItsParentsVectorWhicheverWayItPoints() {
    final byte[] reference = new byte[ROOT_SIZE * ROOT_SIZE * 3];
    final Random random = new Random(45);
    for (int index = 0; index < reference.length; index++) {
      reference[index] = (byte) (REFERENCE_LEVEL_BASE + random.nextInt(REFERENCE_LEVEL_SPREAD));
    }

    final byte[] source = reference.clone();
    for (int index = 0; index < source.length; index++) {
      source[index] = (byte) (source[index] + 16);
    }

    // The brightness step reaches motion search past the fixed early SKIP threshold.
    for (final int parent : new int[] { -4 << 16, 0x0000fffc, 4 << 16, -1 }) {
      final Mcv2BlockState job = new Mcv2BlockState(
        source,
        reference,
        ROOT_SIZE,
        ROOT_SIZE,
        Mcv2FrameKind.PREDICTED,
        Mcv2BlockState.Thresholds.STANDARD,
        LAMBDA,
        null
      );
      final Object coder = job.code(8, 2, 5, 8, 8, parent);
      final int vector = Mcv2BlockState.localVector(coder);
      assertEquals(parent, vector);
    }
  }

  @Test
  void shouldLetLargerBlocksSearchForTheirOwnMotion() {
    final byte[] reference = new byte[ROOT_SIZE * ROOT_SIZE * 3];
    final Random random = new Random(658);
    for (int index = 0; index < reference.length; index++) {
      reference[index] = (byte) (REFERENCE_LEVEL_BASE + random.nextInt(REFERENCE_LEVEL_SPREAD));
    }

    final byte[] source = reference.clone();
    for (int index = 0; index < source.length; index++) {
      source[index] = (byte) (source[index] + 16);
    }

    for (final int size : new int[] { 16, ROOT_SIZE }) {
      final Mcv2BlockState job = new Mcv2BlockState(
        source,
        reference,
        ROOT_SIZE,
        ROOT_SIZE,
        Mcv2FrameKind.PREDICTED,
        Mcv2BlockState.Thresholds.STANDARD,
        LAMBDA,
        null
      );
      final int level = size == 16 ? 1 : 0;
      final int parent = size == 16 ? -4 << 16 : Mcv2BlockState.NO_VECTOR;
      final Object coder = job.code(size, level, 0, 0, 0, parent);
      final int vector = Mcv2BlockState.localVector(coder);
      assertEquals(0, vector);
      final int mode = job.mode(level, 0);
      assertEquals(Mcv2Decoder.MODE_COMPACT, mode);
    }
  }
}
