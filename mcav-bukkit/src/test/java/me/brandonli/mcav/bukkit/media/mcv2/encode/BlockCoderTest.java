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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import me.brandonli.mcav.bukkit.media.mcv2.CompactRecord;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format;
import me.brandonli.mcav.bukkit.media.mcv2.ReconstructionOracle;
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
    for (int index = 0; index < rgb.length; index++) {
      rgb[index] = (byte) (low + random.nextInt(high - low));
    }
    return rgb;
  }

  /** Codes the single 8x8 block of a P frame predicting from the reference at zero motion. */
  private static FrameJob code(final byte[] source, final byte[] reference) {
    final FrameJob job = new FrameJob(STILL, source, reference, 8, 8, false, new int[] { 0 }, new int[] { 0 }, null, null);
    new BlockCoder(job, 8).code(2, 0, 0, 0);
    return job;
  }

  /** A live search from the top that tries one mode, with one quantizer and the cell fits. */
  private static LiveSearch cellFits(final int mode, final int classes) {
    return new LiveSearch(
      8,
      0,
      0,
      0,
      0,
      0,
      0,
      1 << mode,
      1 << mode,
      1 << mode,
      classes,
      1,
      false,
      8,
      false,
      LiveSearch.CELL_FITS,
      0,
      false
    );
  }

  /** A live search of every mode in which a block smaller than 16 takes its parent's vector without searching. */
  private static LiveSearch searchFrom16() {
    return new LiveSearch(
      8,
      0,
      0,
      0,
      0,
      0,
      0,
      LiveSearch.ALL_MODES,
      LiveSearch.ALL_MODES,
      LiveSearch.ALL_MODES,
      LiveSearch.ALL_CLASSES,
      LiveSearch.FROM_LAMBDA,
      false,
      16,
      false,
      0,
      0,
      false
    );
  }

  @Test
  void aBlockTooSmallForHalfResolutionMotionSearchesFromItsOwnSeeds() {
    // half-resolution motion seeds blocks of 16 pixels and more; an 8x8 block without a parent searches from its seeds
    final LiveSearch halfMotion = new LiveSearch(
      8,
      0,
      0,
      0,
      0,
      0,
      0,
      LiveSearch.ALL_MODES,
      LiveSearch.ALL_MODES,
      LiveSearch.ALL_MODES,
      LiveSearch.ALL_CLASSES,
      LiveSearch.FROM_LAMBDA,
      true,
      8,
      false,
      LiveSearch.HALF_MOTION,
      0,
      false
    );
    final Random random = new Random(658);
    final byte[] picture = new byte[32 * 32 * 3];
    random.nextBytes(picture);
    final EncoderSettings settings = STILL.withLive(halfMotion);
    final FrameJob job = new FrameJob(settings, picture, picture.clone(), 32, 32, false, new int[] { 0 }, new int[] { 0 }, null, null);
    final BlockCoder coder = new BlockCoder(job, 8);
    coder.code(2, 5, 8, 8);
    assertEquals(MotionSearch.pack(0, 0), coder.localVector(), "a still picture moves nowhere");
  }

  @Test
  void aBlockBelowTheSearchSizeTakesItsParentsVectorWhicheverWayItPoints() {
    // a still picture: a search finds no motion, so a vector other than none is the parent's
    final Random random = new Random(45);
    final byte[] picture = new byte[32 * 32 * 3];
    random.nextBytes(picture);
    final EncoderSettings settings = STILL.withLive(searchFrom16());
    // left, up and right by two pixels, in half pixels, and left and up by half a pixel: a leftward vector packs
    // negative, the last one to -1
    for (final int parent : new int[] {
      MotionSearch.pack(-4, 0),
      MotionSearch.pack(0, -4),
      MotionSearch.pack(4, 0),
      MotionSearch.pack(-1, -1),
    }) {
      final FrameJob job = new FrameJob(settings, picture, picture.clone(), 32, 32, false, new int[] { 0 }, new int[] { 0 }, null, null);
      final BlockCoder coder = new BlockCoder(job, 8);
      // the 8x8 block at (8, 8), the sixth at its level
      coder.code(2, 5, 8, 8, parent, 0);
      assertEquals(parent, coder.localVector(), "parent " + MotionSearch.unpackX(parent) + "," + MotionSearch.unpackY(parent));
    }
    // the root has no parent, and searches
    final FrameJob job = new FrameJob(settings, picture, picture.clone(), 32, 32, false, new int[] { 0 }, new int[] { 0 }, null, null);
    final BlockCoder root = new BlockCoder(job, 8);
    root.code(2, 5, 8, 8);
    assertEquals(MotionSearch.pack(0, 0), root.localVector());
  }

  /** The rounded mean of a channel of YCoCg values over a square cell of an 8x8 block, as a cell fit takes it. */
  private static int cellMean(final float[] values, final int channel, final int cell, final int column, final int row, final int low) {
    double sum = 0;
    for (int pixelRow = row * cell; pixelRow < (row + 1) * cell; pixelRow++) {
      for (int pixelColumn = column * cell; pixelColumn < (column + 1) * cell; pixelColumn++) {
        sum += values[(pixelRow * 8 + pixelColumn) * 3 + channel];
      }
    }
    final float mean = (float) (sum / (cell * cell));
    return (int) Math.min(Math.max((float) Math.floor(mean + 0.5f), low), low + 255);
  }

  @Test
  void fitsAReducedGridAsTheMeansOfItsCells() {
    final byte[] source = reference(0, 256);
    final float[] ycocg = new float[source.length];
    for (int offset = 0; offset < source.length; offset += 3) {
      final int red = source[offset] & 0xFF;
      final int green = source[offset + 1] & 0xFF;
      final int blue = source[offset + 2] & 0xFF;
      ycocg[offset] = (red + 2 * green + blue) * 0.25f;
      ycocg[offset + 1] = (red - blue) * 0.5f;
      ycocg[offset + 2] = (-red + 2 * green - blue) * 0.25f;
    }
    final EncoderSettings settings = STILL.withLive(cellFits(Mcv2Format.MODE_INTRA_Y4C1, 0));
    final FrameJob job = new FrameJob(settings, source, new byte[0], 8, 8, true, new int[] { 0 }, new int[] { 0 }, null, null);
    new BlockCoder(job, 8).code(2, 0, 0, 0);
    assertEquals(Mcv2Format.MODE_INTRA_Y4C1, job.mode(0, 2, 0));
    final byte[] record = job.record(0, 2, 0);
    // a 4x4 luma grid of 2x2 cells, then the whole block's chroma
    for (int node = 0; node < 16; node++) {
      assertEquals(cellMean(ycocg, 0, 2, node % 4, node / 4, 0), record[node] & 0xFF, "luma node " + node);
    }
    assertEquals(cellMean(ycocg, 1, 8, 0, 0, Byte.MIN_VALUE), record[16]);
    assertEquals(cellMean(ycocg, 2, 8, 0, 0, Byte.MIN_VALUE), record[17]);
  }

  @Test
  void fitsACompactGridAsTheMeansOfItsCells() {
    // the reference plus a luma offset per 2x2 cell: the residual's cell means are the offsets themselves, which a
    // least-squares fit of the interpolated grid would not give
    final byte[] reference = reference(40, 200);
    final byte[] source = reference.clone();
    final int[] offsets = new int[16];
    for (int node = 0; node < 16; node++) {
      offsets[node] = -36 + 12 * (node % 4) + 16 * (node / 4);
    }
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        for (int channel = 0; channel < 3; channel++) {
          final int at = (row * 8 + column) * 3 + channel;
          source[at] = (byte) ((source[at] & 0xFF) + offsets[(row / 2) * 4 + column / 2]);
        }
      }
    }
    final EncoderSettings settings = STILL.withLive(cellFits(Mcv2Format.MODE_COMPACT, 1 << CompactRecord.GRID4_YC));
    final FrameJob job = new FrameJob(settings, source, reference, 8, 8, false, new int[] { 0 }, new int[] { 0 }, null, null);
    new BlockCoder(job, 8).code(2, 0, 0, 0);
    assertEquals(Mcv2Format.MODE_COMPACT, job.mode(0, 2, 0));
    final byte[] record = job.record(0, 2, 0);
    assertEquals(CompactRecord.GRID4_YC, record[0]);
    for (int node = 0; node < 16; node++) {
      assertEquals(offsets[node], record[1 + node], "luma node " + node);
    }
    assertEquals(0, record[17]);
    assertEquals(0, record[18]);
  }

  @Test
  void aPerfectResidualEndsTheLadder() {
    // a uniform brightness step: the one-node residual grid is perfect at the finest quantizer, then the one-byte DC
    // class is perfect for less, and nothing after it can be cheaper
    final byte[] reference = reference(40, 200);
    final byte[] source = reference.clone();
    for (int index = 0; index < source.length; index++) {
      source[index] = (byte) ((source[index] & 0xFF) + 16);
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
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        final double bump = ReconstructionOracle.interpolate(nodes, 0, 1, 4, 8, column, row);
        assertEquals(Math.rint(bump), bump);
        for (int channel = 0; channel < 3; channel++) {
          final int at = (row * 8 + column) * 3 + channel;
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
    for (int offset = 0; offset < source.length; offset += 3) {
      source[offset] = 10;
      source[offset + 1] = 20;
      source[offset + 2] = 30;
    }
    final FrameJob job = new FrameJob(STILL, source, new byte[0], 8, 8, true, new int[] { 0 }, new int[] { 0 }, null, null);
    new BlockCoder(job, 8).code(2, 0, 0, 0);
    assertEquals(Mcv2Format.MODE_SOLID, job.mode(0, 2, 0));
    assertArrayEquals(new byte[] { 10, 20, 30 }, job.record(0, 2, 0));
    assertEquals(0, job.distortion(1, 2, 0));
  }

  @Test
  void needsTheFinestQuantizerThatHoldsTheFittedValues() {
    final float[] fit = new float[18];
    // the sixteen luma nibbles of the 4x4 grid classes hold -8 to 7 steps
    fit[0] = 7;
    assertEquals(0, BlockCoder.neededQuantizer(CompactRecord.GRID4_N4_Y, fit, 16));
    fit[0] = -8.5f;
    assertEquals(0, BlockCoder.neededQuantizer(CompactRecord.GRID4_N4_Y, fit, 16));
    fit[0] = -8.6f;
    assertEquals(1, BlockCoder.neededQuantizer(CompactRecord.GRID4_N4_Y, fit, 16));
    fit[0] = 7.5f;
    assertEquals(1, BlockCoder.neededQuantizer(CompactRecord.GRID4_N4_Y, fit, 16));
    fit[0] = 59.9f;
    assertEquals(3, BlockCoder.neededQuantizer(CompactRecord.GRID4_N4_Y, fit, 16));
    // 60 is 7.5 steps of 8, which rounds to 8: no quantizer holds it, so the coarsest is needed
    fit[0] = 60;
    assertEquals(4, BlockCoder.neededQuantizer(CompactRecord.GRID4_N4_Y, fit, 16));
    // after the nibbles, the chroma pair holds -128 to 127 steps like every value of the other classes
    fit[0] = 0;
    fit[16] = 127;
    assertEquals(0, BlockCoder.neededQuantizer(CompactRecord.GRID4_N4_YC, fit, 18));
    fit[16] = 128;
    assertEquals(1, BlockCoder.neededQuantizer(CompactRecord.GRID4_N4_YC, fit, 18));
    assertEquals(0, BlockCoder.neededQuantizer(CompactRecord.GRID4_N4_YC, fit, 16));
    final float[] dc = { -128 };
    assertEquals(0, BlockCoder.neededQuantizer(CompactRecord.DC_Y, dc, 1));
    dc[0] = -129;
    assertEquals(1, BlockCoder.neededQuantizer(CompactRecord.DC_Y, dc, 1));
    dc[0] = 16;
    assertEquals(0, BlockCoder.neededQuantizer(CompactRecord.GRID2_YC, dc, 1));
    dc[0] = 1019;
    assertEquals(3, BlockCoder.neededQuantizer(CompactRecord.DC_Y, dc, 1));
    dc[0] = 1020;
    assertEquals(4, BlockCoder.neededQuantizer(CompactRecord.DC_Y, dc, 1));
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
    BlockCoder.halve(block, 4, half);
    assertArrayEquals(new int[] { 20, 21, 22, 60, 61, 62, 100, 101, 102, 140, 141, 142 }, half);
    // a mean of 2.5 rounds up, one of 0.25 down
    final int[] square = { 1, 0, 255, 2, 0, 255, 3, 0, 255, 4, 1, 254 };
    final int[] mean = new int[3];
    BlockCoder.halve(square, 2, mean);
    assertArrayEquals(new int[] { 3, 0, 255 }, mean);
  }
}
