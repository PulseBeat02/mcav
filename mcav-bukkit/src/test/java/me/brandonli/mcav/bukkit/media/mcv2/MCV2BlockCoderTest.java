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

final class MCV2BlockCoderTest {

  private static byte[] reference(final int low, final int high) {
    final Random random = new Random(8);
    final byte[] rgb = new byte[8 * 8 * 3];
    for (int index = 0; index < rgb.length; index++) {
      rgb[index] = (byte) (low + random.nextInt(high - low));
    }
    return rgb;
  }

  private static Mcv2BlockState code(final byte[] source, final byte[] reference) {
    final Mcv2BlockState job = new Mcv2BlockState(source, reference, 8, 8, false, false, 65.255994022, null);
    job.code(8, 2, 0, 0, 0, Mcv2BlockState.NO_VECTOR);
    return job;
  }

  @Test
  void aPerfectResidualEndsTheLadder() {
    // Sixteen luma levels require q=2 and sixteen nodes of four.
    final byte[] reference = reference(40, 200);
    final byte[] source = reference.clone();
    for (int index = 0; index < source.length; index++) {
      source[index] = (byte) ((source[index] & 0xFF) + 16);
    }
    final Mcv2BlockState job = code(source, reference);
    {
      assertEquals(0, job.distortion(2, 0));
      assertEquals(Mcv2Decoder.MODE_COMPACT, job.mode(2, 0));
      assertEquals(2, job.quantizer(2, 0));
      assertArrayEquals(new byte[] { 0, 0, 0x44, 0x44, 0x44, 0x44, 0x44, 0x44, 0x44, 0x44 }, job.record(2, 0));
    }
  }

  @Test
  void needsTheFinestQuantizerThatHoldsTheFittedValues() {
    final float[] fit = new float[16];
    // the sixteen luma nibbles of the 4x4 grid classes hold -8 to 7 steps
    fit[0] = 7;
    assertEquals(0, Mcv2BlockState.neededQuantizer(fit));
    fit[0] = -8.5f;
    assertEquals(0, Mcv2BlockState.neededQuantizer(fit));
    fit[0] = -8.6f;
    assertEquals(1, Mcv2BlockState.neededQuantizer(fit));
    fit[0] = 7.5f;
    assertEquals(1, Mcv2BlockState.neededQuantizer(fit));
    fit[0] = 14.9f;
    assertEquals(1, Mcv2BlockState.neededQuantizer(fit));
    fit[0] = 15;
    assertEquals(2, Mcv2BlockState.neededQuantizer(fit));
    fit[0] = 1000;
    assertEquals(2, Mcv2BlockState.neededQuantizer(fit));
    fit[0] = -1000;
    assertEquals(2, Mcv2BlockState.neededQuantizer(fit));
  }

  @Test
  void halvesABlockIntoTheRoundedMeansOfItsSquares() {
    // every 2x2 square of a 4x4 block around its own level, which the square's mean restores
    final int[] block = new int[4 * 4 * 3];
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        final int level = 40 * (2 * (row / 2) + column / 2) + 20;
        final int wobble = (column % 2 == 0 ? 3 : -1) * (row % 2 == 0 ? 1 : -1);
        for (int channel = 0; channel < 3; channel++) {
          block[(row * 4 + column) * 3 + channel] = level + channel + wobble;
        }
      }
    }
    final int[] half = new int[2 * 2 * 3];
    Mcv2Internals.javaKernels().halve(block, 4, half);
    assertArrayEquals(new int[] { 20, 21, 22, 60, 61, 62, 100, 101, 102, 140, 141, 142 }, half);
    // a mean of 2.5 rounds up, one of 0.25 down
    final int[] square = { 1, 0, 255, 2, 0, 255, 3, 0, 255, 4, 1, 254 };
    final int[] mean = new int[3];
    Mcv2Internals.javaKernels().halve(square, 2, mean);
    assertArrayEquals(new int[] { 3, 0, 255 }, mean);
  }

  @Test
  void aUniformKeyframeBlockIsSolid() {
    final byte[] source = new byte[8 * 8 * 3];
    for (int offset = 0; offset < source.length; offset += 3) {
      source[offset] = 10;
      source[offset + 1] = 20;
      source[offset + 2] = 30;
    }
    final Mcv2BlockState job = new Mcv2BlockState(source, new byte[0], 8, 8, true, false, 65.255994022, null);
    job.code(8, 2, 0, 0, 0, Mcv2BlockState.NO_VECTOR);
    assertEquals(Mcv2Decoder.MODE_SOLID, job.mode(2, 0));
    assertArrayEquals(new byte[] { 10, 20, 30 }, job.record(2, 0));
    assertEquals(0, job.distortion(2, 0));
  }

  @Test
  void aBlockBelowTheSearchSizeTakesItsParentsVectorWhicheverWayItPoints() {
    final byte[] reference = new byte[32 * 32 * 3];
    final Random random = new Random(45);
    for (int index = 0; index < reference.length; index++) {
      reference[index] = (byte) (40 + random.nextInt(160));
    }
    final byte[] source = reference.clone();
    for (int index = 0; index < source.length; index++) {
      source[index] = (byte) (source[index] + 16);
    }
    // The brightness step reaches motion search past the fixed early SKIP threshold.
    for (final int parent : new int[] { -4 << 16, 0x0000fffc, 4 << 16, -1 }) {
      final Mcv2BlockState job = new Mcv2BlockState(source, reference, 32, 32, false, false, 65.255994022, null);
      final Object coder = job.code(8, 2, 5, 8, 8, parent);
      assertEquals(parent, Mcv2BlockState.localVector(coder));
    }
  }

  @Test
  void aBlockWithoutAParentSearchesFromItsOwnSeeds() {
    final byte[] source = new byte[32 * 32 * 3];
    new Random(658).nextBytes(source);
    final Mcv2BlockState job = new Mcv2BlockState(source, source.clone(), 32, 32, false, false, 0, null);
    final Object coder = job.code(8, 2, 5, 8, 8, Mcv2BlockState.NO_VECTOR);
    Mcv2Internals.call(coder.getClass(), coder, "searchMotion", new Class<?>[] { int.class }, Mcv2BlockState.NO_VECTOR);
    // A still picture has its uniquely perfect sample match at zero displacement.
    assertEquals(0, Mcv2Internals.invoke(coder.getClass(), coder, "searchMotion", new Class<?>[] { int.class }, Mcv2BlockState.NO_VECTOR));
  }
}
