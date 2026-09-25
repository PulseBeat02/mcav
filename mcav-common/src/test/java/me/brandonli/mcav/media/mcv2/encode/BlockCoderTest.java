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
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import me.brandonli.mcav.media.mcv2.Reconstruction;
import org.junit.jupiter.api.Test;

/**
 * One block's candidate search. The whole search is proven against the reference by {@link EncoderConformanceTest};
 * these cases pin the rules that decide which candidates are measured at all: once a candidate reconstructs the block
 * perfectly, nothing that costs more bits is worth measuring, not even the coarser quantizers of the same class.
 */
final class BlockCoderTest {

  /** The ship profile without local motion search, so the block predicts from exactly its own position. */
  private static final EncoderSettings STILL = new EncoderSettings(
    65.255994022,
    60,
    0,
    true,
    true,
    45.0,
    EncoderSettings.ReferencePolicy.PREVIOUS_FRAME
  );

  private static byte[] reference(final int low, final int high) {
    final Random random = new Random(8);
    final byte[] rgb = new byte[8 * 8 * 3];
    for (int i = 0; i < rgb.length; i++) {
      rgb[i] = (byte) (low + random.nextInt(high - low));
    }
    return rgb;
  }

  /** Codes the single 8x8 block of a P frame predicting from the reference at zero motion. */
  private static FrameJob code(final byte[] source, final byte[] reference) {
    final FrameJob job = new FrameJob(STILL, source, reference, 8, 8, false, new int[] { 0 }, new int[] { 0 });
    new BlockCoder(job, 8).code(2, 0, 0, 0);
    return job;
  }

  @Test
  void aPerfectResidualEndsTheLadder() {
    // a uniform brightness step: the one-node residual grid is perfect at the finest quantizer, then the one-byte DC
    // class is perfect for less, and nothing after it can be cheaper
    final byte[] reference = reference(40, 200);
    final byte[] source = reference.clone();
    for (int i = 0; i < source.length; i++) {
      source[i] = (byte) ((source[i] & 0xFF) + 16);
    }
    final FrameJob job = code(source, reference);
    for (int trial = 0; trial < 2; trial++) {
      assertEquals(0, job.distortion(trial, 2, 0));
      assertEquals(Mcv2Format.MODE_COMPACT, job.mode(trial, 2, 0));
      assertEquals(0, job.quantizer(trial, 2, 0));
      assertArrayEquals(new byte[] { 0, 16 }, job.record(trial, 2, 0));
    }
  }

  @Test
  void aPerfectReducedResidualEndsTheLadder() {
    // a bump on a 4x4 luma grid: the full 4x4 residual grid and the reduced Y4C1 residual are perfect at the finest
    // quantizer, and the 4x4 nibble class is perfect at the coarsest for fewer bytes still
    final float[] nodes = new float[16];
    nodes[5] = 96;
    nodes[6] = 96;
    nodes[9] = 96;
    nodes[10] = 96;
    final byte[] reference = reference(40, 150);
    final byte[] source = reference.clone();
    for (int y = 0; y < 8; y++) {
      for (int x = 0; x < 8; x++) {
        final double bump = Reconstruction.interpolate(nodes, 0, 1, 4, 8, x, y);
        assertEquals(Math.rint(bump), bump);
        for (int c = 0; c < 3; c++) {
          final int at = (y * 8 + x) * 3 + c;
          source[at] = (byte) ((source[at] & 0xFF) + (int) bump);
        }
      }
    }
    final FrameJob job = code(source, reference);
    for (int trial = 0; trial < 2; trial++) {
      assertEquals(0, job.distortion(trial, 2, 0));
      assertEquals(Mcv2Format.MODE_COMPACT, job.mode(trial, 2, 0));
      assertEquals(4, job.quantizer(trial, 2, 0));
      assertEquals(3, job.record(trial, 2, 0)[0]);
    }
  }

  @Test
  void aUniformKeyframeBlockIsSolid() {
    final byte[] source = new byte[8 * 8 * 3];
    for (int i = 0; i < source.length; i += 3) {
      source[i] = 10;
      source[i + 1] = 20;
      source[i + 2] = 30;
    }
    final FrameJob job = new FrameJob(STILL, source, new byte[0], 8, 8, true, new int[] { 0 }, new int[] { 0 });
    new BlockCoder(job, 8).code(2, 0, 0, 0);
    assertEquals(Mcv2Format.MODE_SOLID, job.mode(0, 2, 0));
    assertArrayEquals(new byte[] { 10, 20, 30 }, job.record(0, 2, 0));
    assertEquals(0, job.distortion(1, 2, 0));
  }
}
