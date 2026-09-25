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
package me.brandonli.mcav.media.mcv2;

import static me.brandonli.mcav.media.mcv2.Mcv2Format.*;

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
    for (int s = 0; s < 3; s++) {
      final int size = 8 << s;
      for (int g = 0; g < 4; g++) {
        final int grid = 1 << g;
        final int[] lower = new int[size];
        final int[] upper = new int[size];
        final double[] fraction = new double[size];
        for (int p = 0; p < size; p++) {
          final double position = Math.min(Math.max(((p + 0.5) * grid) / size - 0.5, 0.0), grid - 1);
          lower[p] = (int) Math.floor(position);
          upper[p] = Math.min(lower[p] + 1, grid - 1);
          fraction[p] = position - lower[p];
        }
        LOWER[s][g] = lower;
        UPPER[s][g] = upper;
        FRACTION[s][g] = fraction;
      }
      final float[] axis = new float[size];
      for (int i = 0; i < size; i++) {
        axis[i] = ((i + 0.5f) / size) * 2.0f - 1.0f;
      }
      LOW2_AXIS[s] = axis;
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
   * @param x      the column inside the block
   * @param y      the row inside the block
   * @return the interpolated value
   */
  public static double interpolate(
    final float[] nodes,
    final int offset,
    final int stride,
    final int grid,
    final int size,
    final int x,
    final int y
  ) {
    final int s = sizeIndex(size);
    final int g = Integer.numberOfTrailingZeros(grid);
    final int x0 = LOWER[s][g][x];
    final int x1 = UPPER[s][g][x];
    final double fx = FRACTION[s][g][x];
    final int y0 = LOWER[s][g][y];
    final int y1 = UPPER[s][g][y];
    final double fy = FRACTION[s][g][y];
    final double top = nodes[offset + (y0 * grid + x0) * stride] * (1 - fx) + nodes[offset + (y0 * grid + x1) * stride] * fx;
    final double bottom = nodes[offset + (y1 * grid + x0) * stride] * (1 - fx) + nodes[offset + (y1 * grid + x1) * stride] * fx;
    return top * (1 - fy) + bottom * fy;
  }

  /**
   * Predicts a block from a reference picture: every pixel plus a half-pixel displacement, each coordinate clamped to
   * the picture before bilinear sampling.
   *
   * @param reference the reference picture, row-major RGB
   * @param width     the picture width
   * @param height    the picture height
   * @param x         the block's left edge
   * @param y         the block's top edge
   * @param size      the block size; pixels outside the picture are predicted too, from clamped coordinates
   * @param mx        the horizontal displacement in half pixels
   * @param my        the vertical displacement in half pixels
   * @param out       receives four times each predicted channel, {@code size * size * 3} values
   */
  public static void predict(
    final byte[] reference,
    final int width,
    final int height,
    final int x,
    final int y,
    final int size,
    final int mx,
    final int my,
    final int[] out
  ) {
    for (int py = 0; py < size; py++) {
      final int hy = Math.min(Math.max(2 * (y + py) + my, 0), 2 * (height - 1));
      final int y0 = hy >> 1;
      final int y1 = Math.min(y0 + 1, height - 1);
      final boolean halfY = (hy & 1) != 0;
      for (int px = 0; px < size; px++) {
        final int hx = Math.min(Math.max(2 * (x + px) + mx, 0), 2 * (width - 1));
        final int x0 = hx >> 1;
        final int x1 = Math.min(x0 + 1, width - 1);
        final boolean halfX = (hx & 1) != 0;
        final int a = (y0 * width + x0) * 3;
        final int at = (py * size + px) * 3;
        if (!halfX && !halfY) {
          for (int c = 0; c < 3; c++) {
            out[at + c] = 4 * (reference[a + c] & 0xFF);
          }
        } else if (!halfY) {
          final int b = (y0 * width + x1) * 3;
          for (int c = 0; c < 3; c++) {
            out[at + c] = 2 * ((reference[a + c] & 0xFF) + (reference[b + c] & 0xFF));
          }
        } else if (!halfX) {
          final int b = (y1 * width + x0) * 3;
          for (int c = 0; c < 3; c++) {
            out[at + c] = 2 * ((reference[a + c] & 0xFF) + (reference[b + c] & 0xFF));
          }
        } else {
          final int b = (y0 * width + x1) * 3;
          final int d = (y1 * width + x0) * 3;
          final int e = (y1 * width + x1) * 3;
          for (int c = 0; c < 3; c++) {
            out[at + c] = (reference[a + c] & 0xFF) + (reference[b + c] & 0xFF) + (reference[d + c] & 0xFF) + (reference[e + c] & 0xFF);
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
    final int n = size * size * 3;
    for (int i = 0; i < n; i++) {
      out[i] = rgb8(prediction[i] * 0.25f);
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
    final int n = size * size;
    for (int i = 0; i < n; i++) {
      out[i * 3] = (color >> 16) & 0xFF;
      out[i * 3 + 1] = (color >> 8) & 0xFF;
      out[i * 3 + 2] = color & 0xFF;
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
    final int n = size * size;
    for (int i = 0; i < n; i++) {
      final int bit = (record[offset + 6 + i / 8] >> (i & 7)) & 1;
      final int at = offset + bit * 3;
      out[i * 3] = record[at] & 0xFF;
      out[i * 3 + 1] = record[at + 1] & 0xFF;
      out[i * 3 + 2] = record[at + 2] & 0xFF;
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
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        final int color = pattern.colorAt(x, y);
        final int at = (y * size + x) * 3;
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
    for (int i = 0; i < 3 * grid * grid; i++) {
      nodes[i] = record[offset + i] & 0xFF;
    }
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        final int at = (y * size + x) * 3;
        for (int c = 0; c < 3; c++) {
          out[at + c] = rgb8((float) interpolate(nodes, c, 3, grid, size, x, y));
        }
      }
    }
  }

  /**
   * Reconstructs a YCoCg residual grid block (modes 8 to 11): signed nodes scaled by {@code 2^q} before
   * interpolation, converted and added to the prediction in float32.
   *
   * @param prediction four times the predicted channels
   * @param record     the bytes holding the nodes
   * @param offset     the offset of the first node, after the motion bytes
   * @param grid       the grid width
   * @param q          the quantizer
   * @param size       the block size
   * @param nodes      scratch space for {@code 3 * grid * grid} floats
   * @param out        the reconstructed channels
   */
  public static void residualGrid(
    final int[] prediction,
    final byte[] record,
    final int offset,
    final int grid,
    final int q,
    final int size,
    final float[] nodes,
    final int[] out
  ) {
    for (int i = 0; i < 3 * grid * grid; i++) {
      nodes[i] = (float) (record[offset + i] * (1 << q));
    }
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        final float c0 = (float) interpolate(nodes, 0, 3, grid, size, x, y);
        final float c1 = (float) interpolate(nodes, 1, 3, grid, size, x, y);
        final float c2 = (float) interpolate(nodes, 2, 3, grid, size, x, y);
        final int at = (y * size + x) * 3;
        out[at] = rgb8(prediction[at] * 0.25f + ((c0 + c1) - c2));
        out[at + 1] = rgb8(prediction[at + 1] * 0.25f + (c0 + c2));
        out[at + 2] = rgb8(prediction[at + 2] * 0.25f + ((c0 - c1) - c2));
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
   * @param q          the quantizer, zero for an intra record
   * @param size       the block size
   * @param nodes      scratch space for at least 72 floats
   * @param out        the reconstructed channels
   */
  public static void reduced(
    final int@org.checkerframework.checker.nullness.qual.Nullable[] prediction,
    final byte[] record,
    final int offset,
    final int luma,
    final int chroma,
    final int q,
    final int size,
    final float[] nodes,
    final int[] out
  ) {
    final boolean residual = prediction != null;
    for (int i = 0; i < luma * luma; i++) {
      nodes[i] = residual ? (float) record[offset + i] : (float) (record[offset + i] & 0xFF);
    }
    final int chromaAt = offset + luma * luma;
    for (int i = 0; i < 2 * chroma * chroma; i++) {
      nodes[64 + i] = record[chromaAt + i];
    }
    final double scale = 1 << q;
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        final float yv = (float) interpolate(nodes, 0, 1, luma, size, x, y);
        final float co = (float) interpolate(nodes, 64, 2, chroma, size, x, y);
        final float cg = (float) interpolate(nodes, 65, 2, chroma, size, x, y);
        final int at = (y * size + x) * 3;
        if (prediction == null) {
          out[at] = rgb8((yv + co) - cg);
          out[at + 1] = rgb8(yv + cg);
          out[at + 2] = rgb8((yv - co) - cg);
          continue;
        }
        final double ys = yv * scale;
        final double cos = co * scale;
        final double cgs = cg * scale;
        out[at] = rgb8((float) (prediction[at] * 0.25 + ((ys + cos) - cgs)));
        out[at + 1] = rgb8((float) (prediction[at + 1] * 0.25 + (ys + cgs)));
        out[at + 2] = rgb8((float) (prediction[at + 2] * 0.25 + ((ys - cos) - cgs)));
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
   * @param q          the quantizer
   * @param size       the block size
   * @param nodes      scratch space for at least 16 floats
   * @param out        the reconstructed channels
   */
  public static void compact(
    final int[] prediction,
    final byte[] record,
    final int body,
    final int kind,
    final int q,
    final int size,
    final float[] nodes,
    final int[] out
  ) {
    final float step = 1 << q;
    int grid = 0;
    float co = 0.0f;
    float cg = 0.0f;
    switch (kind) {
      case CompactRecord.GRID2_YC -> {
        grid = 2;
        for (int i = 0; i < 4; i++) {
          nodes[i] = record[body + i];
        }
        co = record[body + 4];
        cg = record[body + 5];
      }
      case CompactRecord.GRID4_N4_YC, CompactRecord.GRID4_N4_Y -> {
        grid = 4;
        for (int i = 0; i < 16; i++) {
          final int value = ((record[body + i / 2] & 0xFF) >> ((i & 1) * 4)) & 15;
          nodes[i] = (value ^ 8) - 8;
        }
        if (kind == CompactRecord.GRID4_N4_YC) {
          co = record[body + 8];
          cg = record[body + 9];
        }
      }
      case CompactRecord.GRID4_YC -> {
        grid = 4;
        for (int i = 0; i < 16; i++) {
          nodes[i] = record[body + i];
        }
        co = record[body + 16];
        cg = record[body + 17];
      }
      case CompactRecord.VQ64, CompactRecord.PQ64 -> {
        grid = 4;
        final float dc = record[body];
        final int ids = (record[body + 3] & 0xFF) | (kind == CompactRecord.PQ64 ? (record[body + 4] & 0xFF) << 8 : 0);
        for (int i = 0; i < 16; i++) {
          final int row = i / 4;
          final int column = i % 4;
          final int book;
          if (kind == CompactRecord.VQ64) {
            book = ResidualBooks.vq(ids, i);
          } else if (column < 2) {
            book = ResidualBooks.pq(0, ids & 63, row * 2 + column);
          } else {
            book = ResidualBooks.pq(1, ids >> 6, row * 2 + column - 2);
          }
          nodes[i] = (float) book + dc;
        }
        co = record[body + 1];
        cg = record[body + 2];
      }
      default -> {
        // DC_Y, GAIN_BIAS and LOW2 need no node grid
      }
    }
    final float[] axis = LOW2_AXIS[sizeIndex(size)];
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        final int at = (y * size + x) * 3;
        final float p0 = prediction[at] * 0.25f;
        final float p1 = prediction[at + 1] * 0.25f;
        final float p2 = prediction[at + 2] * 0.25f;
        if (kind == CompactRecord.GAIN_BIAS) {
          final float gain = 1.0f + (float) record[body] / 64.0f;
          final float by = record[body + 1];
          final float bco = record[body + 2];
          final float bcg = record[body + 3];
          out[at] = rgb8(p0 * gain + ((by + bco) - bcg));
          out[at + 1] = rgb8(p1 * gain + (by + bcg));
          out[at + 2] = rgb8(p2 * gain + ((by - bco) - bcg));
          continue;
        }
        if (kind == CompactRecord.DC_Y) {
          final float dc = (float) record[body] * step;
          out[at] = rgb8(p0 + dc);
          out[at + 1] = rgb8(p1 + dc);
          out[at + 2] = rgb8(p2 + dc);
          continue;
        }
        final float yv;
        float cov = co;
        float cgv = cg;
        if (kind == CompactRecord.LOW2) {
          yv = ((float) record[body] + (float) record[body + 1] * axis[x]) + (float) record[body + 2] * axis[y];
          cov = record[body + 3];
          cgv = record[body + 4];
        } else {
          yv = (float) interpolate(nodes, 0, 1, grid, size, x, y);
        }
        final float ys = yv * step;
        final float cos = cov * step;
        final float cgs = cgv * step;
        out[at] = rgb8(p0 + ((ys + cos) - cgs));
        out[at + 1] = rgb8(p1 + (ys + cgs));
        out[at + 2] = rgb8(p2 + ((ys - cos) - cgs));
      }
    }
  }
}
