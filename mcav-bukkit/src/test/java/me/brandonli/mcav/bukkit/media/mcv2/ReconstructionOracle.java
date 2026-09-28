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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The reconstruction kernels as the reference decoder writes them, in float32 and float64, kept as the oracle the
 * integer kernels of {@link Reconstruction} are tested against: this is the first Java form of the kernels, the one the
 * conformance streams verified bit for bit, with only the class renamed.
 *
 * <p>Every kernel reconstructs one whole square block, {@code size * size} pixels in row-major order, three channels
 * each, into an {@code int} array of 0..255 values. Motion predictions are passed as four times the predicted value,
 * which is always an integer: half-pixel bilinear sampling averages one, two or four reference pixels. The arithmetic
 * reproduces the reference decoder's float32 and float64 operations in their order; interpolated grid values are exact
 * dyadic numbers, so only the colour conversion and the final add round, exactly as the reference rounds them.
 */
public final class ReconstructionOracle {

  /** Interpolation tables for leaf sizes 8, 16, 32 (index 0..2) and grid widths 1, 2, 4, 8 (index 0..3). */
  private static final int[][][] LOWER = new int[3][4][];

  private static final int[][][] UPPER = new int[3][4][];

  private static final double[][][] FRACTION = new double[3][4][];

  private static final float[][] LOW2_AXIS = new float[3][];

  static {
    for (int sizeIndex = 0; sizeIndex < 3; sizeIndex++) {
      final int size = 8 << sizeIndex;
      for (int gridIndex = 0; gridIndex < 4; gridIndex++) {
        final int grid = 1 << gridIndex;
        final int[] lower = new int[size];
        final int[] upper = new int[size];
        final double[] fraction = new double[size];
        for (int pixel = 0; pixel < size; pixel++) {
          final double position = Math.min(Math.max(((pixel + 0.5) * grid) / size - 0.5, 0.0), grid - 1);
          lower[pixel] = (int) Math.floor(position);
          upper[pixel] = Math.min(lower[pixel] + 1, grid - 1);
          fraction[pixel] = position - lower[pixel];
        }
        LOWER[sizeIndex][gridIndex] = lower;
        UPPER[sizeIndex][gridIndex] = upper;
        FRACTION[sizeIndex][gridIndex] = fraction;
      }
      final float[] axis = new float[size];
      for (int axisIndex = 0; axisIndex < size; axisIndex++) {
        axis[axisIndex] = ((axisIndex + 0.5f) / size) * 2.0f - 1.0f;
      }
      LOW2_AXIS[sizeIndex] = axis;
    }
  }

  private ReconstructionOracle() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Rounds a reconstructed channel exactly as the reference does: {@code floor(clamp(value, 0, 255) + 0.5)} in float32.
   *
   * @param value the channel value
   * @return the byte value, 0 to 255
   */
  public static int rgb8(final float value) {
    final float clamped = Math.min(Math.max(value, 0.0f), 255.0f);
    return (int) Math.floor(clamped + 0.5f);
  }

  private static int sizeIndex(final int size) {
    return Integer.numberOfTrailingZeros(size) - 3;
  }

  /**
   * Interpolates a grid of nodes at one pixel of a block. Every weight is dyadic and every node an integer scaled by a
   * power of two, so the value is exact in double and representable in float.
   *
   * @param nodes  the nodes
   * @param offset the index of node (0, 0)
   * @param stride the distance between consecutive nodes
   * @param grid   the grid width, 1, 2, 4 or 8
   * @param size   the block size, 8, 16 or 32
   * @param column the column inside the block
   * @param row    the row inside the block
   * @return the interpolated value
   */
  public static double interpolate(
    final float[] nodes,
    final int offset,
    final int stride,
    final int grid,
    final int size,
    final int column,
    final int row
  ) {
    final int sizeIndex = sizeIndex(size);
    final int gridIndex = Integer.numberOfTrailingZeros(grid);
    final int leftNode = LOWER[sizeIndex][gridIndex][column];
    final int rightNode = UPPER[sizeIndex][gridIndex][column];
    final double fractionX = FRACTION[sizeIndex][gridIndex][column];
    final int topNode = LOWER[sizeIndex][gridIndex][row];
    final int bottomNode = UPPER[sizeIndex][gridIndex][row];
    final double fractionY = FRACTION[sizeIndex][gridIndex][row];
    final double top =
      nodes[offset + (topNode * grid + leftNode) * stride] * (1 - fractionX) +
      nodes[offset + (topNode * grid + rightNode) * stride] * fractionX;
    final double bottom =
      nodes[offset + (bottomNode * grid + leftNode) * stride] * (1 - fractionX) +
      nodes[offset + (bottomNode * grid + rightNode) * stride] * fractionX;
    return top * (1 - fractionY) + bottom * fractionY;
  }

  /**
   * Predicts a block from a reference picture: every pixel plus a half-pixel displacement, each coordinate clamped to
   * the picture before bilinear sampling.
   *
   * @param reference the reference picture, row-major RGB
   * @param width     the picture width
   * @param height    the picture height
   * @param blockLeft the block's left edge
   * @param blockTop  the block's top edge
   * @param size      the block size; pixels outside the picture are predicted too, from clamped coordinates
   * @param motionX   the horizontal displacement in half pixels
   * @param motionY   the vertical displacement in half pixels
   * @param out       receives four times each predicted channel, {@code size * size * 3} values
   */
  public static void predict(
    final byte[] reference,
    final int width,
    final int height,
    final int blockLeft,
    final int blockTop,
    final int size,
    final int motionX,
    final int motionY,
    final int[] out
  ) {
    for (int row = 0; row < size; row++) {
      final int halfPixelY = Math.min(Math.max(2 * (blockTop + row) + motionY, 0), 2 * (height - 1));
      final int topRow = halfPixelY >> 1;
      final int bottomRow = Math.min(topRow + 1, height - 1);
      final boolean halfY = (halfPixelY & 1) != 0;
      for (int column = 0; column < size; column++) {
        final int halfPixelX = Math.min(Math.max(2 * (blockLeft + column) + motionX, 0), 2 * (width - 1));
        final int leftColumn = halfPixelX >> 1;
        final int rightColumn = Math.min(leftColumn + 1, width - 1);
        final boolean halfX = (halfPixelX & 1) != 0;
        final int topLeft = (topRow * width + leftColumn) * 3;
        final int at = (row * size + column) * 3;
        if (!halfX && !halfY) {
          for (int channel = 0; channel < 3; channel++) {
            out[at + channel] = 4 * (reference[topLeft + channel] & 0xFF);
          }
        } else if (!halfY) {
          final int topRight = (topRow * width + rightColumn) * 3;
          for (int channel = 0; channel < 3; channel++) {
            out[at + channel] = 2 * ((reference[topLeft + channel] & 0xFF) + (reference[topRight + channel] & 0xFF));
          }
        } else if (!halfX) {
          final int bottomLeft = (bottomRow * width + leftColumn) * 3;
          for (int channel = 0; channel < 3; channel++) {
            out[at + channel] = 2 * ((reference[topLeft + channel] & 0xFF) + (reference[bottomLeft + channel] & 0xFF));
          }
        } else {
          final int topRight = (topRow * width + rightColumn) * 3;
          final int bottomLeft = (bottomRow * width + leftColumn) * 3;
          final int bottomRight = (bottomRow * width + rightColumn) * 3;
          for (int channel = 0; channel < 3; channel++) {
            out[at + channel] =
              (reference[topLeft + channel] & 0xFF) +
              (reference[topRight + channel] & 0xFF) +
              (reference[bottomLeft + channel] & 0xFF) +
              (reference[bottomRight + channel] & 0xFF);
          }
        }
      }
    }
  }

  /**
   * Reconstructs a skip or motion block: the prediction, rounded.
   *
   * @param prediction four times the predicted channels
   * @param size       the block size
   * @param out        the reconstructed channels
   */
  public static void predicted(final int[] prediction, final int size, final int[] out) {
    final int sampleCount = size * size * 3;
    for (int sample = 0; sample < sampleCount; sample++) {
      out[sample] = rgb8(prediction[sample] * 0.25f);
    }
  }

  /**
   * Reconstructs a flat block.
   *
   * @param color the colour as {@code 0xRRGGBB}
   * @param size  the block size
   * @param out   the reconstructed channels
   */
  public static void solid(final int color, final int size, final int[] out) {
    final int pixelCount = size * size;
    for (int pixel = 0; pixel < pixelCount; pixel++) {
      out[pixel * 3] = (color >> 16) & 0xFF;
      out[pixel * 3 + 1] = (color >> 8) & 0xFF;
      out[pixel * 3 + 2] = color & 0xFF;
    }
  }

  /**
   * Reconstructs a two-colour palette block from its record: two RGB endpoints, then one selector bit per pixel.
   *
   * @param record the bytes holding the record
   * @param offset the record offset
   * @param size   the block size
   * @param out    the reconstructed channels
   */
  public static void palette(final byte[] record, final int offset, final int size, final int[] out) {
    final int pixelCount = size * size;
    for (int pixel = 0; pixel < pixelCount; pixel++) {
      final int bit = (record[offset + 6 + pixel / 8] >> (pixel & 7)) & 1;
      final int at = offset + bit * 3;
      out[pixel * 3] = record[at] & 0xFF;
      out[pixel * 3 + 1] = record[at + 1] & 0xFF;
      out[pixel * 3 + 2] = record[at + 2] & 0xFF;
    }
  }

  /**
   * Reconstructs a pattern palette block.
   *
   * @param pattern the resolved pattern
   * @param size    the block size
   * @param out     the reconstructed channels
   */
  public static void pattern(final PatternRecord pattern, final int size, final int[] out) {
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final int color = pattern.colorAt(column, row);
        final int at = (row * size + column) * 3;
        out[at] = (color >> 16) & 0xFF;
        out[at + 1] = (color >> 8) & 0xFF;
        out[at + 2] = color & 0xFF;
      }
    }
  }

  /**
   * Reconstructs an RGB intra grid block (modes 4 to 7) from its interleaved unsigned nodes.
   *
   * @param record the bytes holding the nodes
   * @param offset the offset of the first node
   * @param grid   the grid width
   * @param size   the block size
   * @param nodes  scratch space for {@code 3 * grid * grid} floats
   * @param out    the reconstructed channels
   */
  public static void intraGrid(
    final byte[] record,
    final int offset,
    final int grid,
    final int size,
    final float[] nodes,
    final int[] out
  ) {
    for (int nodeIndex = 0; nodeIndex < 3 * grid * grid; nodeIndex++) {
      nodes[nodeIndex] = record[offset + nodeIndex] & 0xFF;
    }
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final int at = (row * size + column) * 3;
        for (int channel = 0; channel < 3; channel++) {
          out[at + channel] = rgb8((float) interpolate(nodes, channel, 3, grid, size, column, row));
        }
      }
    }
  }

  /**
   * Reconstructs a YCoCg residual grid block (modes 8 to 11): signed nodes scaled by {@code 2^quantizer} before
   * interpolation, converted and added to the prediction in float32.
   *
   * @param prediction four times the predicted channels
   * @param record     the bytes holding the nodes
   * @param offset     the offset of the first node, after the motion bytes
   * @param grid       the grid width
   * @param quantizer  the quantizer
   * @param size       the block size
   * @param nodes      scratch space for {@code 3 * grid * grid} floats
   * @param out        the reconstructed channels
   */
  public static void residualGrid(
    final int[] prediction,
    final byte[] record,
    final int offset,
    final int grid,
    final int quantizer,
    final int size,
    final float[] nodes,
    final int[] out
  ) {
    for (int nodeIndex = 0; nodeIndex < 3 * grid * grid; nodeIndex++) {
      nodes[nodeIndex] = (float) (record[offset + nodeIndex] * (1 << quantizer));
    }
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final float pixelLuma = (float) interpolate(nodes, 0, 3, grid, size, column, row);
        final float pixelChromaOrange = (float) interpolate(nodes, 1, 3, grid, size, column, row);
        final float pixelChromaGreen = (float) interpolate(nodes, 2, 3, grid, size, column, row);
        final int at = (row * size + column) * 3;
        out[at] = rgb8(prediction[at] * 0.25f + (pixelLuma + pixelChromaOrange - pixelChromaGreen));
        out[at + 1] = rgb8(prediction[at + 1] * 0.25f + (pixelLuma + pixelChromaGreen));
        out[at + 2] = rgb8(prediction[at + 2] * 0.25f + (pixelLuma - pixelChromaOrange - pixelChromaGreen));
      }
    }
  }

  /**
   * Reconstructs a reduced-chroma block (modes 12 to 15): a luma grid and a coarser interleaved chroma grid. Intra
   * records convert in float32; residual records are scaled after interpolation and converted in float64, and rounded
   * to float32 once, after the prediction is added, exactly as the reference decoder does.
   *
   * @param prediction four times the predicted channels, or null for an intra record
   * @param record     the bytes holding the record
   * @param offset     the offset of the first luma node, after any motion bytes
   * @param luma       the luma grid width
   * @param chroma     the chroma grid width
   * @param quantizer  the quantizer, zero for an intra record
   * @param size       the block size
   * @param nodes      scratch space for at least 72 floats
   * @param out        the reconstructed channels
   */
  public static void reduced(
    final int @Nullable [] prediction,
    final byte[] record,
    final int offset,
    final int luma,
    final int chroma,
    final int quantizer,
    final int size,
    final float[] nodes,
    final int[] out
  ) {
    final boolean residual = prediction != null;
    for (int nodeIndex = 0; nodeIndex < luma * luma; nodeIndex++) {
      nodes[nodeIndex] = residual ? (float) record[offset + nodeIndex] : (float) (record[offset + nodeIndex] & 0xFF);
    }
    final int chromaAt = offset + luma * luma;
    for (int nodeIndex = 0; nodeIndex < 2 * chroma * chroma; nodeIndex++) {
      nodes[64 + nodeIndex] = record[chromaAt + nodeIndex];
    }
    final double scale = 1 << quantizer;
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final float pixelLuma = (float) interpolate(nodes, 0, 1, luma, size, column, row);
        final float pixelChromaOrange = (float) interpolate(nodes, 64, 2, chroma, size, column, row);
        final float pixelChromaGreen = (float) interpolate(nodes, 65, 2, chroma, size, column, row);
        final int at = (row * size + column) * 3;
        if (prediction == null) {
          out[at] = rgb8(pixelLuma + pixelChromaOrange - pixelChromaGreen);
          out[at + 1] = rgb8(pixelLuma + pixelChromaGreen);
          out[at + 2] = rgb8(pixelLuma - pixelChromaOrange - pixelChromaGreen);
          continue;
        }
        final double scaledLuma = pixelLuma * scale;
        final double scaledChromaOrange = pixelChromaOrange * scale;
        final double scaledChromaGreen = pixelChromaGreen * scale;
        out[at] = rgb8((float) (prediction[at] * 0.25 + (scaledLuma + scaledChromaOrange - scaledChromaGreen)));
        out[at + 1] = rgb8((float) (prediction[at + 1] * 0.25 + (scaledLuma + scaledChromaGreen)));
        out[at + 2] = rgb8((float) (prediction[at + 2] * 0.25 + (scaledLuma - scaledChromaOrange - scaledChromaGreen)));
      }
    }
  }

  /**
   * Reconstructs a compact record's body (mode 17) on top of its motion prediction, in float32.
   *
   * @param prediction four times the predicted channels, at the record's motion
   * @param record     the bytes holding the record
   * @param body       the offset of the first body byte
   * @param kind       the class, 0 to 8
   * @param quantizer  the quantizer
   * @param size       the block size
   * @param nodes      scratch space for at least 16 floats
   * @param out        the reconstructed channels
   */
  public static void compact(
    final int[] prediction,
    final byte[] record,
    final int body,
    final int kind,
    final int quantizer,
    final int size,
    final float[] nodes,
    final int[] out
  ) {
    final float step = 1 << quantizer;
    int grid = 0;
    float chromaOrange = 0.0f;
    float chromaGreen = 0.0f;
    switch (kind) {
      case CompactRecord.GRID2_YC -> {
        grid = 2;
        for (int nodeIndex = 0; nodeIndex < 4; nodeIndex++) {
          nodes[nodeIndex] = record[body + nodeIndex];
        }
        chromaOrange = record[body + 4];
        chromaGreen = record[body + 5];
      }
      case CompactRecord.GRID4_N4_YC, CompactRecord.GRID4_N4_Y -> {
        grid = 4;
        for (int nodeIndex = 0; nodeIndex < 16; nodeIndex++) {
          final int value = ((record[body + nodeIndex / 2] & 0xFF) >> ((nodeIndex & 1) * 4)) & 15;
          nodes[nodeIndex] = (value ^ 8) - 8;
        }
        if (kind == CompactRecord.GRID4_N4_YC) {
          chromaOrange = record[body + 8];
          chromaGreen = record[body + 9];
        }
      }
      case CompactRecord.GRID4_YC -> {
        grid = 4;
        for (int nodeIndex = 0; nodeIndex < 16; nodeIndex++) {
          nodes[nodeIndex] = record[body + nodeIndex];
        }
        chromaOrange = record[body + 16];
        chromaGreen = record[body + 17];
      }
      case CompactRecord.VQ64, CompactRecord.PQ64 -> {
        grid = 4;
        final float dc = record[body];
        final int ids = (record[body + 3] & 0xFF) | (kind == CompactRecord.PQ64 ? (record[body + 4] & 0xFF) << 8 : 0);
        for (int nodeIndex = 0; nodeIndex < 16; nodeIndex++) {
          final int nodeRow = nodeIndex / 4;
          final int nodeColumn = nodeIndex % 4;
          final int book;
          if (kind == CompactRecord.VQ64) {
            book = ResidualBooks.vq(ids, nodeIndex);
          } else if (nodeColumn < 2) {
            book = ResidualBooks.pq(0, ids & 63, nodeRow * 2 + nodeColumn);
          } else {
            book = ResidualBooks.pq(1, ids >> 6, nodeRow * 2 + nodeColumn - 2);
          }
          nodes[nodeIndex] = (float) book + dc;
        }
        chromaOrange = record[body + 1];
        chromaGreen = record[body + 2];
      }
      default -> {
        // DC_Y, GAIN_BIAS and LOW2 need no node grid
      }
    }
    final float[] axis = LOW2_AXIS[sizeIndex(size)];
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final int at = (row * size + column) * 3;
        final float predictedRed = prediction[at] * 0.25f;
        final float predictedGreen = prediction[at + 1] * 0.25f;
        final float predictedBlue = prediction[at + 2] * 0.25f;
        if (kind == CompactRecord.GAIN_BIAS) {
          final float gain = 1.0f + (float) record[body] / 64.0f;
          final float biasLuma = record[body + 1];
          final float biasChromaOrange = record[body + 2];
          final float biasChromaGreen = record[body + 3];
          out[at] = rgb8(predictedRed * gain + (biasLuma + biasChromaOrange - biasChromaGreen));
          out[at + 1] = rgb8(predictedGreen * gain + (biasLuma + biasChromaGreen));
          out[at + 2] = rgb8(predictedBlue * gain + (biasLuma - biasChromaOrange - biasChromaGreen));
          continue;
        }
        if (kind == CompactRecord.DC_Y) {
          final float dc = (float) record[body] * step;
          out[at] = rgb8(predictedRed + dc);
          out[at + 1] = rgb8(predictedGreen + dc);
          out[at + 2] = rgb8(predictedBlue + dc);
          continue;
        }
        final float pixelLuma;
        float pixelChromaOrange = chromaOrange;
        float pixelChromaGreen = chromaGreen;
        if (kind == CompactRecord.LOW2) {
          pixelLuma = (float) record[body] + (float) record[body + 1] * axis[column] + (float) record[body + 2] * axis[row];
          pixelChromaOrange = record[body + 3];
          pixelChromaGreen = record[body + 4];
        } else {
          pixelLuma = (float) interpolate(nodes, 0, 1, grid, size, column, row);
        }
        final float scaledLuma = pixelLuma * step;
        final float scaledChromaOrange = pixelChromaOrange * step;
        final float scaledChromaGreen = pixelChromaGreen * step;
        out[at] = rgb8(predictedRed + (scaledLuma + scaledChromaOrange - scaledChromaGreen));
        out[at + 1] = rgb8(predictedGreen + (scaledLuma + scaledChromaGreen));
        out[at + 2] = rgb8(predictedBlue + (scaledLuma - scaledChromaOrange - scaledChromaGreen));
      }
    }
  }
}
