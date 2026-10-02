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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.BLOCK_SIZES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHANNELS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHROMA_PLANES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.GRID_WIDTHS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MAX_CHANNEL;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MAX_GRID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.PALETTE_COLORS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ROOT_SIZE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SMALLEST_BLOCK;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.signed;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.sizeIndex;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The normative pixel arithmetic of MCV2 leaves, shared by the decoder and the encoder so that the reconstruction the
 * encoder scores is, by construction, exactly the one the decoder produces.
 *
 * <p>Every kernel reconstructs one whole square block, {@code size * size} pixels in row-major order, three channels
 * each, into an {@code int} array of 0..255 values. Motion predictions are passed as four times the predicted value,
 * which is always an integer: half-pixel bilinear sampling averages one, two or four reference pixels.
 *
 * <p>The reference decoder computes in float32 and float64, but every number it forms is a dyadic rational with few
 * significant bits: predictions are quarters, a grid's interpolation weights are multiples of {@code 1 / (2 size)} on
 * each axis, nodes are small integers scaled by powers of two, and the largest sum stays below 2^13 with at most ten
 * fractional bits, which is 23 of float32's 24 significand bits. So none of its operations rounds, and every channel
 * is {@code floor(clamp(v, 0, 255) + 0.5)} of the exact value {@code v}. The kernels compute that exact value in integer
 * arithmetic, scaled by {@code (2 size)^2}, interpolating separably (rows, then columns), which is the same result
 * without floating point. The first, floating-point form of the kernels is kept by the tests as the oracle these are
 * compared with.
 *
 * <p>These kernels operate on already validated codec data. Sizes must be 8, 16 or 32 pixels; full grids
 * have 1, 2, 4 or 8 nodes per axis, reduced grids use luma/chroma pairs 4/1 or 8/2, and residual quantizers range
 * from 0 through 7. Input record extents and output capacities are caller preconditions, not checked here. Every
 * output and prediction block has at least {@code size * size * 3} entries, with prediction channels from 0 through
 * 1020. Output, scratch and score storage must be exclusive to the calling worker; input arrays remain caller-owned
 * and must stay stable. Scored kernels may return false after writing only part of the output, which must then be
 * discarded. Calls using independent scratch and arrays can run concurrently.
 */
public final class Reconstruction {

  /** Interpolation tables for leaf sizes 8, 16, 32 (index 0..2) and grid widths 1, 2, 4, 8 (index 0..3). */
  private static final int[][][] LOWER = new int[BLOCK_SIZES][GRID_WIDTHS][];

  private static final int[][][] UPPER = new int[BLOCK_SIZES][GRID_WIDTHS][];

  /** The distance to the lower node in units of {@code 1 / (2 size)}. */
  private static final int[][][] WEIGHT = new int[BLOCK_SIZES][GRID_WIDTHS][];

  /**
   * The integer distortions are this many times the reference's weighted squared error {@code (4 Y^2 + Co^2 + Cg^2) /
   * 6}: 16 clears the quarters of Y and Cg, and 6 is its divisor.
   */
  public static final double DISTORTION_SCALE = 96.0;

  /** The weight of luma, and of Co at half of Cg's scale, in the reference's 4:1:1 weighted YCoCg error. */
  private static final int LUMA_WEIGHT = 4;

  /** Predictions are four times the predicted value, which keeps half-pixel averages exact. */
  private static final int PREDICTION_SCALE = 4;

  private static final int PREDICTION_SHIFT = 2;

  /** A gain-and-bias record's gain is in 64ths above one. */
  private static final int GAIN_UNIT = 64;

  /** The bits of the gain unit times the prediction scale. */
  private static final int GAIN_SHIFT = 8;

  /** Where the scratch nodes of the chroma start: after the largest luma grid. */
  private static final int CHROMA_NODES = MAX_GRID * MAX_GRID;

  private static final int NIBBLE_BITS = 4;

  private static final int NIBBLE_MASK = 15;

  private static final int NIBBLES_PER_BYTE = 2;

  /** The luma grid of the 2x2 compact class. */
  private static final int SMALL_GRID = 2;

  /** The luma grid of the other grid compact classes and of the book classes. */
  private static final int COMPACT_GRID = 4;

  /** The columns of a product book's 4x2 vector, the left or right half of the 4x4 grid. */
  private static final int HALF_COLUMNS = 2;

  private static final int BOOK_INDEX_BITS = Integer.numberOfTrailingZeros(ResidualBooks.VECTORS);

  static {
    for (int sizeIndex = 0; sizeIndex < BLOCK_SIZES; sizeIndex++) {
      final int size = SMALLEST_BLOCK << sizeIndex;
      final int span = 2 * size;
      for (int gridIndex = 0; gridIndex < GRID_WIDTHS; gridIndex++) {
        final int grid = 1 << gridIndex;
        final int[] lower = new int[size];
        final int[] upper = new int[size];
        final int[] weight = new int[size];
        for (int pixel = 0; pixel < size; pixel++) {
          // the node position ((pixel + 0.5) * grid) / size - 0.5, clamped to the grid, times 2 * size
          final int position = Math.min(Math.max((2 * pixel + 1) * grid - size, 0), (grid - 1) * span);
          lower[pixel] = position / span;
          upper[pixel] = Math.min(lower[pixel] + 1, grid - 1);
          weight[pixel] = position - lower[pixel] * span;
        }
        LOWER[sizeIndex][gridIndex] = lower;
        UPPER[sizeIndex][gridIndex] = upper;
        WEIGHT[sizeIndex][gridIndex] = weight;
      }
    }
  }

  /**
   * Scratch space for the kernels. A decoder or an encoder worker keeps one and passes it to every kernel it calls;
   * it is not thread-safe.
   */
  public static final class Scratch {

    private final int[] nodes = new int[CHANNELS * MAX_GRID * MAX_GRID];

    private final int[] rows0 = new int[MAX_GRID * ROOT_SIZE];

    private final int[] rows1 = new int[MAX_GRID * ROOT_SIZE];

    private final int[] rows2 = new int[MAX_GRID * ROOT_SIZE];

    private final int[] line0 = new int[ROOT_SIZE];

    private final int[] line1 = new int[ROOT_SIZE];

    private final int[] line2 = new int[ROOT_SIZE];

    /** Constructs new scratch space. */
    public Scratch() {
      // the arrays are the whole state
    }
  }

  /**
   * The encoder's measure of a reconstruction, taken row by row while a kernel reconstructs a block: sixteen times six
   * times the weighted YCoCg squared error against the block's source, an exact integer. A candidate is only worth
   * finishing while it can still be cheaper than the cost it has to beat; the error only grows with every row, so a
   * kernel stops at the first row after which the candidate's distortion plus its rate reaches that cost, and the
   * candidate could never have been chosen. One instance is reused by one encoder worker; it is not thread-safe.
   */
  public static final class Score {

    private int[] source = new int[0];

    private double rate;

    private double limit;

    private long distortion;

    /** Constructs a new score. */
    public Score() {
      // set up by start() for every candidate
    }

    /**
     * Starts measuring a reconstruction.
     *
     * <p>The source array is retained until the next start call. Keep it stable during scoring. Rate and cost
     * use the same unscaled distortion units; the accumulated integer distortion is divided by DISTORTION_SCALE for comparison.
     *
     * @param block the block's source channels, 0..255, three per pixel in row-major order
     * @param bits  the candidate's rate in units of distortion over 96: lambda times its bits
     * @param cost  the cost the candidate must stay below to be chosen
     */
    public void start(final int[] block, final double bits, final double cost) {
      this.source = block;
      this.rate = bits;
      this.limit = cost;
      this.distortion = 0;
    }

    /**
     * Gets the distortion of the last reconstruction the kernel finished.
     *
     * @return the distortion
     */
    public long distortion() {
      return this.distortion;
    }

    /** Adds the error of one row of pixels; false once the candidate can no longer be cheaper than the cost. */
    boolean row(final int[] out, final int from, final int pixels) {
      final int[] source = this.source;
      // a pixel's error is at most 4 * 1020^2 + 4 * 510^2 + 1020^2, about 6.2 million, so a row of 32 fits an int
      int sum = 0;
      final int end = from + pixels * CHANNELS;
      for (int offset = from; offset < end; offset += CHANNELS) {
        sum += pixelError(source[offset] - out[offset], source[offset + 1] - out[offset + 1], source[offset + 2] - out[offset + 2]);
      }
      this.distortion += sum;
      return this.distortion / DISTORTION_SCALE + this.rate < this.limit;
    }
  }

  private Reconstruction() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * The error of one pixel in the units of {@link #DISTORTION_SCALE}: 96 times the reference's weighted squared YCoCg
   * error of the channel differences.
   *
   * @param redDifference the red channel difference, from -255 through 255
   * @param greenDifference the green channel difference, from -255 through 255
   * @param blueDifference the blue channel difference, from -255 through 255
   * @return the error, at most about 6.2 million for differences of 8-bit channels
   */
  public static int pixelError(final int redDifference, final int greenDifference, final int blueDifference) {
    final int luma = redDifference + 2 * greenDifference + blueDifference;
    final int chromaOrange = redDifference - blueDifference;
    final int chromaGreen = 2 * greenDifference - redDifference - blueDifference;
    // luma, chromaOrange and chromaGreen are 4 Y, 2 Co and 4 Cg, so this is 16 times the reference's
    // 4 Y^2 + Co^2 + Cg^2
    return LUMA_WEIGHT * (luma * luma + chromaOrange * chromaOrange) + chromaGreen * chromaGreen;
  }

  /**
   * Rounds a reconstructed channel exactly as the reference does: {@code floor(clamp(value, 0, 255) + 0.5)} in float32.
   *
   * @param value the channel value
   * @return the byte value, 0 to 255
   */
  public static int rgb8(final float value) {
    final float clamped = Math.min(Math.max(value, 0.0f), MAX_CHANNEL);
    return (int) Math.floor(clamped + 0.5f);
  }

  /** {@code floor(clamp(value / 2^shift, 0, 255) + 0.5)} of an exact fixed-point value. */
  private static int round(final int value, final int shift) {
    return Math.min(Math.max((value + (1 << (shift - 1))) >> shift, 0), MAX_CHANNEL);
  }

  /** The shift that undoes the {@code (2 size)^2} scale of interpolated values. */
  private static int shift(final int size) {
    return 2 * (Integer.numberOfTrailingZeros(size) + 1);
  }

  /**
   * The first pass of interpolating a grid of nodes over a block: every row of nodes interpolated across the block's
   * width, times {@code 2 size}, exactly. {@link #vertical} then gives the plane one row at a time.
   *
   * @param nodes  the node values
   * @param offset the index of node (0, 0)
   * @param stride the distance between consecutive nodes
   * @param grid   the grid width, 1, 2, 4 or 8
   * @param size   the block size, 8, 16 or 32
   * @param rows   receives {@code grid * size} values
   */
  private static void horizontal(final int[] nodes, final int offset, final int stride, final int grid, final int size, final int[] rows) {
    final int sizeIndex = sizeIndex(size);
    final int gridIndex = Integer.numberOfTrailingZeros(grid);
    final int[] lower = LOWER[sizeIndex][gridIndex];
    final int[] upper = UPPER[sizeIndex][gridIndex];
    final int[] weight = WEIGHT[sizeIndex][gridIndex];
    final int span = 2 * size;
    for (int nodeRow = 0; nodeRow < grid; nodeRow++) {
      final int row = offset + nodeRow * grid * stride;
      final int at = nodeRow * size;
      for (int column = 0; column < size; column++) {
        final int upperWeight = weight[column];
        rows[at + column] = nodes[row + lower[column] * stride] * (span - upperWeight) + nodes[row + upper[column] * stride] * upperWeight;
      }
    }
  }

  /**
   * One row of an interpolated plane: {@code line[column]} is the bilinear value at pixel (column, row) times
   * {@code (2 size)^2}, exactly.
   *
   * @param rows the first pass, from {@link #horizontal}
   * @param grid the grid width
   * @param size the block size
   * @param row  the row
   * @param line receives {@code size} values
   */
  private static void vertical(final int[] rows, final int grid, final int size, final int row, final int[] line) {
    final int sizeIndex = sizeIndex(size);
    final int gridIndex = Integer.numberOfTrailingZeros(grid);
    final int top = LOWER[sizeIndex][gridIndex][row] * size;
    final int bottom = UPPER[sizeIndex][gridIndex][row] * size;
    final int bottomWeight = WEIGHT[sizeIndex][gridIndex][row];
    final int topWeight = 2 * size - bottomWeight;
    for (int column = 0; column < size; column++) {
      line[column] = rows[top + column] * topWeight + rows[bottom + column] * bottomWeight;
    }
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
   * @param size      the block side, 8, 16 or 32 pixels; pixels outside the picture are predicted too, from clamped coordinates
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
    // whole and half pixels inside the picture need no clamping, and their parity is the same for every pixel
    final int left = blockLeft + (motionX >> 1);
    final int top = blockTop + (motionY >> 1);
    if (left >= 0 && top >= 0 && left + size + (motionX & 1) <= width && top + size + (motionY & 1) <= height) {
      predictInside(reference, width, left, top, size, motionX & 1, motionY & 1, out);
      return;
    }
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
        final int topLeft = (topRow * width + leftColumn) * CHANNELS;
        final int at = (row * size + column) * CHANNELS;
        if (!halfX && !halfY) {
          for (int channel = 0; channel < CHANNELS; channel++) {
            out[at + channel] = 4 * (reference[topLeft + channel] & 0xFF);
          }
        } else if (!halfY) {
          final int topRight = (topRow * width + rightColumn) * CHANNELS;
          for (int channel = 0; channel < CHANNELS; channel++) {
            out[at + channel] = 2 * ((reference[topLeft + channel] & 0xFF) + (reference[topRight + channel] & 0xFF));
          }
        } else if (!halfX) {
          final int bottomLeft = (bottomRow * width + leftColumn) * CHANNELS;
          for (int channel = 0; channel < CHANNELS; channel++) {
            out[at + channel] = 2 * ((reference[topLeft + channel] & 0xFF) + (reference[bottomLeft + channel] & 0xFF));
          }
        } else {
          final int topRight = (topRow * width + rightColumn) * CHANNELS;
          final int bottomLeft = (bottomRow * width + leftColumn) * CHANNELS;
          final int bottomRight = (bottomRow * width + rightColumn) * CHANNELS;
          for (int channel = 0; channel < CHANNELS; channel++) {
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

  /** {@link #predict} of a block whose samples, and the neighbours half pixels average, all lie inside the picture. */
  private static void predictInside(
    final byte[] reference,
    final int width,
    final int left,
    final int top,
    final int size,
    final int halfX,
    final int halfY,
    final int[] out
  ) {
    final int rowLength = size * CHANNELS;
    final int right = halfX * CHANNELS;
    final int below = halfY * width * CHANNELS;
    for (int row = 0; row < size; row++) {
      final int topLeft = ((top + row) * width + left) * CHANNELS;
      final int at = row * rowLength;
      if (halfX == 0 && halfY == 0) {
        for (int offset = 0; offset < rowLength; offset++) {
          out[at + offset] = 4 * (reference[topLeft + offset] & 0xFF);
        }
      } else if (halfY == 0 || halfX == 0) {
        final int neighbour = topLeft + right + below;
        for (int offset = 0; offset < rowLength; offset++) {
          out[at + offset] = 2 * ((reference[topLeft + offset] & 0xFF) + (reference[neighbour + offset] & 0xFF));
        }
      } else {
        final int topRight = topLeft + right;
        final int bottomLeft = topLeft + below;
        final int bottomRight = bottomLeft + right;
        for (int offset = 0; offset < rowLength; offset++) {
          out[at + offset] =
            (reference[topLeft + offset] & 0xFF) +
            (reference[topRight + offset] & 0xFF) +
            (reference[bottomLeft + offset] & 0xFF) +
            (reference[bottomRight + offset] & 0xFF);
        }
      }
    }
  }

  /**
   * Reconstructs a skip or motion block: the prediction, rounded.
   *
   * @param prediction four times the predicted channels
   * @param size       the block side, 8, 16 or 32 pixels
   * @param out        the caller-owned output, at least {@code size * size * 3} RGB channel entries
   */
  public static void predicted(final int[] prediction, final int size, final int[] out) {
    predicted(prediction, size, out, null);
  }

  /**
   * Reconstructs a skip or motion block and measures it.
   *
   * @param prediction four times the predicted channels
   * @param size       the block side, 8, 16 or 32 pixels
   * @param out        the caller-owned output, at least {@code size * size * 3} RGB channel entries
   * @param score      the measure, or null
   * @return whether the block was finished: false when the measure stopped it
   */
  public static boolean predicted(final int[] prediction, final int size, final int[] out, final @Nullable Score score) {
    final int rowLength = size * CHANNELS;
    for (int row = 0; row < size; row++) {
      final int from = row * rowLength;
      for (int offset = from; offset < from + rowLength; offset++) {
        // four times a byte, so rounding needs no clamp
        out[offset] = (prediction[offset] + 2) >> 2;
      }
      if (score != null && !score.row(out, from, size)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reconstructs a flat block.
   *
   * @param color the colour as {@code 0xRRGGBB}
   * @param size  the block side, 8, 16 or 32 pixels
   * @param out   the caller-owned output, at least {@code size * size * 3} RGB channel entries
   */
  public static void solid(final int color, final int size, final int[] out) {
    solid(color, size, out, null);
  }

  /**
   * Reconstructs a flat block and measures it.
   *
   * @param color the colour as {@code 0xRRGGBB}
   * @param size  the block side, 8, 16 or 32 pixels
   * @param out   the caller-owned output, at least {@code size * size * 3} RGB channel entries
   * @param score the measure, or null
   * @return whether the block was finished: false when the measure stopped it
   */
  public static boolean solid(final int color, final int size, final int[] out, final @Nullable Score score) {
    final int red = (color >> 16) & 0xFF;
    final int green = (color >> 8) & 0xFF;
    final int blue = color & 0xFF;
    for (int row = 0; row < size; row++) {
      final int from = row * size * CHANNELS;
      for (int column = 0; column < size; column++) {
        out[from + column * CHANNELS] = red;
        out[from + column * CHANNELS + 1] = green;
        out[from + column * CHANNELS + 2] = blue;
      }
      if (score != null && !score.row(out, from, size)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reconstructs a two-colour palette block from its record: two RGB endpoints, then one selector bit per pixel.
   *
   * @param record the bytes holding the record
   * @param offset the record offset
   * @param size   the block side, 8, 16 or 32 pixels
   * @param out    the caller-owned output, at least {@code size * size * 3} RGB channel entries
   */
  public static void palette(final byte[] record, final int offset, final int size, final int[] out) {
    palette(record, offset, size, out, null);
  }

  /**
   * Reconstructs a two-colour palette block from its record and measures it.
   *
   * @param record the bytes holding the record
   * @param offset the record offset
   * @param size   the block side, 8, 16 or 32 pixels
   * @param out    the caller-owned output, at least {@code size * size * 3} RGB channel entries
   * @param score  the measure, or null
   * @return whether the block was finished: false when the measure stopped it
   */
  public static boolean palette(final byte[] record, final int offset, final int size, final int[] out, final @Nullable Score score) {
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final int pixel = row * size + column;
        final int bit = (record[offset + PALETTE_COLORS * CHANNELS + pixel / Byte.SIZE] >> (pixel % Byte.SIZE)) & 1;
        final int at = offset + bit * CHANNELS;
        out[pixel * CHANNELS] = record[at] & 0xFF;
        out[pixel * CHANNELS + 1] = record[at + 1] & 0xFF;
        out[pixel * CHANNELS + 2] = record[at + 2] & 0xFF;
      }
      if (score != null && !score.row(out, row * size * CHANNELS, size)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reconstructs a pattern palette block.
   *
   * @param pattern the resolved pattern
   * @param size    the block side, 8, 16 or 32 pixels
   * @param out     the caller-owned output, at least {@code size * size * 3} RGB channel entries
   */
  public static void pattern(final PatternRecord pattern, final int size, final int[] out) {
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final int color = pattern.colorAt(column, row);
        final int at = (row * size + column) * CHANNELS;
        out[at] = (color >> 16) & 0xFF;
        out[at + 1] = (color >> 8) & 0xFF;
        out[at + 2] = color & 0xFF;
      }
    }
  }

  /**
   * Reconstructs an RGB intra grid block (modes 4 to 7) from its interleaved unsigned nodes.
   *
   * @param record  the bytes holding the nodes
   * @param offset  the offset of the first node
   * @param grid    the grid width
   * @param size    the block side, 8, 16 or 32 pixels
   * @param scratch exclusive per-worker scratch space, reused and overwritten during the call
   * @param out     the caller-owned output, at least {@code size * size * 3} RGB channel entries
   */
  public static void intraGrid(
    final byte[] record,
    final int offset,
    final int grid,
    final int size,
    final Scratch scratch,
    final int[] out
  ) {
    intraGrid(record, offset, grid, size, scratch, out, null);
  }

  /**
   * Reconstructs an RGB intra grid block (modes 4 to 7) and measures it.
   *
   * @param record  the bytes holding the nodes
   * @param offset  the offset of the first node
   * @param grid    the grid width
   * @param size    the block side, 8, 16 or 32 pixels
   * @param scratch exclusive per-worker scratch space, reused and overwritten during the call
   * @param out     the caller-owned output, at least {@code size * size * 3} RGB channel entries
   * @param score   the measure, or null
   * @return whether the block was finished: false when the measure stopped it
   */
  public static boolean intraGrid(
    final byte[] record,
    final int offset,
    final int grid,
    final int size,
    final Scratch scratch,
    final int[] out,
    final @Nullable Score score
  ) {
    final int[] nodes = scratch.nodes;
    for (int nodeIndex = 0; nodeIndex < CHANNELS * grid * grid; nodeIndex++) {
      nodes[nodeIndex] = record[offset + nodeIndex] & 0xFF;
    }
    horizontal(nodes, 0, CHANNELS, grid, size, scratch.rows0);
    horizontal(nodes, 1, CHANNELS, grid, size, scratch.rows1);
    horizontal(nodes, 2, CHANNELS, grid, size, scratch.rows2);
    final int[] redLine = scratch.line0;
    final int[] greenLine = scratch.line1;
    final int[] blueLine = scratch.line2;
    final int shift = shift(size);
    for (int row = 0; row < size; row++) {
      vertical(scratch.rows0, grid, size, row, redLine);
      vertical(scratch.rows1, grid, size, row, greenLine);
      vertical(scratch.rows2, grid, size, row, blueLine);
      final int from = row * size * CHANNELS;
      for (int column = 0; column < size; column++) {
        final int at = from + column * CHANNELS;
        out[at] = round(redLine[column], shift);
        out[at + 1] = round(greenLine[column], shift);
        out[at + 2] = round(blueLine[column], shift);
      }
      if (score != null && !score.row(out, from, size)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reconstructs a YCoCg residual grid block (modes 8 to 11): signed nodes scaled by {@code 2^quantizer} before
   * interpolation, converted and added to the prediction.
   *
   * @param prediction four times the predicted channels
   * @param record     the bytes holding the nodes
   * @param offset     the offset of the first node, after the motion bytes
   * @param grid       the grid width
   * @param quantizer  the quantizer
   * @param size       the block side, 8, 16 or 32 pixels
   * @param scratch    exclusive per-worker scratch space, reused and overwritten during the call
   * @param out        the caller-owned output, at least {@code size * size * 3} RGB channel entries
   */
  public static void residualGrid(
    final int[] prediction,
    final byte[] record,
    final int offset,
    final int grid,
    final int quantizer,
    final int size,
    final Scratch scratch,
    final int[] out
  ) {
    residualGrid(prediction, record, offset, grid, quantizer, size, scratch, out, null);
  }

  /**
   * Reconstructs a YCoCg residual grid block (modes 8 to 11) and measures it.
   *
   * @param prediction four times the predicted channels
   * @param record     the bytes holding the nodes
   * @param offset     the offset of the first node, after the motion bytes
   * @param grid       the grid width
   * @param quantizer  the quantizer
   * @param size       the block side, 8, 16 or 32 pixels
   * @param scratch    exclusive per-worker scratch space, reused and overwritten during the call
   * @param out        the caller-owned output, at least {@code size * size * 3} RGB channel entries
   * @param score      the measure, or null
   * @return whether the block was finished: false when the measure stopped it
   */
  public static boolean residualGrid(
    final int[] prediction,
    final byte[] record,
    final int offset,
    final int grid,
    final int quantizer,
    final int size,
    final Scratch scratch,
    final int[] out,
    final @Nullable Score score
  ) {
    final int[] nodes = scratch.nodes;
    for (int nodeIndex = 0; nodeIndex < CHANNELS * grid * grid; nodeIndex++) {
      nodes[nodeIndex] = record[offset + nodeIndex] << quantizer;
    }
    horizontal(nodes, 0, CHANNELS, grid, size, scratch.rows0);
    horizontal(nodes, 1, CHANNELS, grid, size, scratch.rows1);
    horizontal(nodes, 2, CHANNELS, grid, size, scratch.rows2);
    final int[] lumaLine = scratch.line0;
    final int[] chromaOrangeLine = scratch.line1;
    final int[] chromaGreenLine = scratch.line2;
    final int shift = shift(size);
    final int quarter = size * size;
    for (int row = 0; row < size; row++) {
      vertical(scratch.rows0, grid, size, row, lumaLine);
      vertical(scratch.rows1, grid, size, row, chromaOrangeLine);
      vertical(scratch.rows2, grid, size, row, chromaGreenLine);
      final int from = row * size * CHANNELS;
      for (int column = 0; column < size; column++) {
        final int at = from + column * CHANNELS;
        out[at] = round(prediction[at] * quarter + (lumaLine[column] + chromaOrangeLine[column] - chromaGreenLine[column]), shift);
        out[at + 1] = round(prediction[at + 1] * quarter + (lumaLine[column] + chromaGreenLine[column]), shift);
        out[at + 2] = round(prediction[at + 2] * quarter + (lumaLine[column] - chromaOrangeLine[column] - chromaGreenLine[column]), shift);
      }
      if (score != null && !score.row(out, from, size)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reconstructs a reduced-chroma block (modes 12 to 15): a luma grid and a coarser interleaved chroma grid, luma
   * unsigned in an intra record and signed in a residual one, a residual record scaled by {@code 2^quantizer} and added
   * to the prediction.
   *
   * @param prediction four times the predicted channels, or null for an intra record
   * @param record     the bytes holding the record
   * @param offset     the offset of the first luma node, after any motion bytes
   * @param luma       the luma grid width
   * @param chroma     the chroma grid width
   * @param quantizer  the quantizer, zero for an intra record
   * @param size       the block side, 8, 16 or 32 pixels
   * @param scratch    exclusive per-worker scratch space, reused and overwritten during the call
   * @param out        the caller-owned output, at least {@code size * size * 3} RGB channel entries
   */
  public static void reduced(
    final int @Nullable [] prediction,
    final byte[] record,
    final int offset,
    final int luma,
    final int chroma,
    final int quantizer,
    final int size,
    final Scratch scratch,
    final int[] out
  ) {
    reduced(prediction, record, offset, luma, chroma, quantizer, size, scratch, out, null);
  }

  /**
   * Reconstructs a reduced-chroma block (modes 12 to 15) and measures it.
   *
   * @param prediction four times the predicted channels, or null for an intra record
   * @param record     the bytes holding the record
   * @param offset     the offset of the first luma node, after any motion bytes
   * @param luma       the luma grid width
   * @param chroma     the chroma grid width
   * @param quantizer  the quantizer, zero for an intra record
   * @param size       the block side, 8, 16 or 32 pixels
   * @param scratch    exclusive per-worker scratch space, reused and overwritten during the call
   * @param out        the caller-owned output, at least {@code size * size * 3} RGB channel entries
   * @param score      the measure, or null
   * @return whether the block was finished: false when the measure stopped it
   */
  public static boolean reduced(
    final int @Nullable [] prediction,
    final byte[] record,
    final int offset,
    final int luma,
    final int chroma,
    final int quantizer,
    final int size,
    final Scratch scratch,
    final int[] out,
    final @Nullable Score score
  ) {
    final int[] nodes = scratch.nodes;
    for (int nodeIndex = 0; nodeIndex < luma * luma; nodeIndex++) {
      nodes[nodeIndex] = prediction != null ? record[offset + nodeIndex] : record[offset + nodeIndex] & 0xFF;
    }
    final int chromaAt = offset + luma * luma;
    for (int nodeIndex = 0; nodeIndex < CHROMA_PLANES * chroma * chroma; nodeIndex++) {
      nodes[CHROMA_NODES + nodeIndex] = record[chromaAt + nodeIndex];
    }
    horizontal(nodes, 0, 1, luma, size, scratch.rows0);
    horizontal(nodes, CHROMA_NODES, CHROMA_PLANES, chroma, size, scratch.rows1);
    horizontal(nodes, CHROMA_NODES + 1, CHROMA_PLANES, chroma, size, scratch.rows2);
    final int[] lumaLine = scratch.line0;
    final int[] chromaOrangeLine = scratch.line1;
    final int[] chromaGreenLine = scratch.line2;
    final int shift = shift(size);
    final int quarter = size * size;
    for (int row = 0; row < size; row++) {
      vertical(scratch.rows0, luma, size, row, lumaLine);
      vertical(scratch.rows1, chroma, size, row, chromaOrangeLine);
      vertical(scratch.rows2, chroma, size, row, chromaGreenLine);
      final int from = row * size * CHANNELS;
      for (int column = 0; column < size; column++) {
        final int at = from + column * CHANNELS;
        if (prediction == null) {
          out[at] = round(lumaLine[column] + chromaOrangeLine[column] - chromaGreenLine[column], shift);
          out[at + 1] = round(lumaLine[column] + chromaGreenLine[column], shift);
          out[at + 2] = round(lumaLine[column] - chromaOrangeLine[column] - chromaGreenLine[column], shift);
        } else {
          out[at] = round(
            prediction[at] * quarter + ((lumaLine[column] + chromaOrangeLine[column] - chromaGreenLine[column]) << quantizer),
            shift
          );
          out[at + 1] = round(prediction[at + 1] * quarter + ((lumaLine[column] + chromaGreenLine[column]) << quantizer), shift);
          out[at + 2] = round(
            prediction[at + 2] * quarter + ((lumaLine[column] - chromaOrangeLine[column] - chromaGreenLine[column]) << quantizer),
            shift
          );
        }
      }
      if (score != null && !score.row(out, from, size)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reconstructs a compact record's body (mode 17) on top of its motion prediction.
   *
   * @param prediction four times the predicted channels, at the record's motion
   * @param record     the bytes holding the record
   * @param body       the offset of the first body byte
   * @param kind       the class, 0 to 8
   * @param quantizer  the quantizer
   * @param size       the block side, 8, 16 or 32 pixels
   * @param scratch    exclusive per-worker scratch space, reused and overwritten during the call
   * @param out        the caller-owned output, at least {@code size * size * 3} RGB channel entries
   */
  public static void compact(
    final int[] prediction,
    final byte[] record,
    final int body,
    final int kind,
    final int quantizer,
    final int size,
    final Scratch scratch,
    final int[] out
  ) {
    compact(prediction, record, body, kind, quantizer, size, scratch, out, null);
  }

  /**
   * Reconstructs a compact record's body (mode 17) and measures it.
   *
   * @param prediction four times the predicted channels, at the record's motion
   * @param record     the bytes holding the record
   * @param body       the offset of the first body byte
   * @param kind       the class, 0 to 8
   * @param quantizer  the quantizer
   * @param size       the block side, 8, 16 or 32 pixels
   * @param scratch    exclusive per-worker scratch space, reused and overwritten during the call
   * @param out        the caller-owned output, at least {@code size * size * 3} RGB channel entries
   * @param score      the measure, or null
   * @return whether the block was finished: false when the measure stopped it
   */
  public static boolean compact(
    final int[] prediction,
    final byte[] record,
    final int body,
    final int kind,
    final int quantizer,
    final int size,
    final Scratch scratch,
    final int[] out,
    final @Nullable Score score
  ) {
    if (kind == CompactRecord.GAIN_BIAS) {
      // p * (1 + gain / 64) + bias, scaled by 256: four times the prediction times (64 + gain), plus 256 times the bias
      final int gain = GAIN_UNIT + record[body];
      final int biasLuma = record[body + 1];
      final int biasChromaOrange = record[body + 2];
      final int biasChromaGreen = record[body + 3];
      final int red = (biasLuma + biasChromaOrange - biasChromaGreen) << GAIN_SHIFT;
      final int green = (biasLuma + biasChromaGreen) << GAIN_SHIFT;
      final int blue = (biasLuma - biasChromaOrange - biasChromaGreen) << GAIN_SHIFT;
      for (int row = 0; row < size; row++) {
        final int from = row * size * CHANNELS;
        for (int at = from; at < from + size * CHANNELS; at += CHANNELS) {
          out[at] = round(prediction[at] * gain + red, GAIN_SHIFT);
          out[at + 1] = round(prediction[at + 1] * gain + green, GAIN_SHIFT);
          out[at + 2] = round(prediction[at + 2] * gain + blue, GAIN_SHIFT);
        }
        if (score != null && !score.row(out, from, size)) {
          return false;
        }
      }
      return true;
    }
    if (kind == CompactRecord.DC_Y) {
      // four times the prediction plus four times the offset
      final int dc = (record[body] << quantizer) * PREDICTION_SCALE;
      for (int row = 0; row < size; row++) {
        final int from = row * size * CHANNELS;
        for (int offset = from; offset < from + size * CHANNELS; offset++) {
          out[offset] = round(prediction[offset] + dc, PREDICTION_SHIFT);
        }
        if (score != null && !score.row(out, from, size)) {
          return false;
        }
      }
      return true;
    }
    final int[] luma = scratch.line0;
    final boolean low2 = kind == CompactRecord.LOW2;
    final int chromaOrange;
    final int chromaGreen;
    final int grid;
    if (low2) {
      chromaOrange = record[body + 3];
      chromaGreen = record[body + 4];
      grid = 0;
    } else {
      chromaOrange = chromaOffset(record, body, kind, 0);
      chromaGreen = chromaOffset(record, body, kind, 1);
      grid = nodes(record, body, kind, scratch.nodes);
      horizontal(scratch.nodes, 0, 1, grid, size, scratch.rows0);
    }
    final int shift = shift(size);
    final int quarter = size * size;
    final int scale = PREDICTION_SCALE * size * size;
    final int red = ((chromaOrange - chromaGreen) * scale) << quantizer;
    final int green = (chromaGreen * scale) << quantizer;
    final int blue = (-(chromaOrange + chromaGreen) * scale) << quantizer;
    if (!low2 && chromaOrange == 0 && chromaGreen == 0) {
      // no chroma, the luma-only class among them: every channel adds the same interpolated luma
      final int half = 1 << (shift - 1);
      for (int row = 0; row < size; row++) {
        vertical(scratch.rows0, grid, size, row, luma);
        final int from = row * size * CHANNELS;
        for (int column = 0; column < size; column++) {
          final int at = from + column * CHANNELS;
          final int scaledLuma = (luma[column] << quantizer) + half;
          out[at] = Math.min(Math.max((prediction[at] * quarter + scaledLuma) >> shift, 0), MAX_CHANNEL);
          out[at + 1] = Math.min(Math.max((prediction[at + 1] * quarter + scaledLuma) >> shift, 0), MAX_CHANNEL);
          out[at + 2] = Math.min(Math.max((prediction[at + 2] * quarter + scaledLuma) >> shift, 0), MAX_CHANNEL);
        }
        if (score != null && !score.row(out, from, size)) {
          return false;
        }
      }
      return true;
    }
    for (int row = 0; row < size; row++) {
      if (low2) {
        // (dc + gradientX * axis[column] + gradientY * axis[row]) * size, with axis[i] = (2 i + 1 - size) / size,
        // times 4 size
        final int dc = record[body] * size;
        final int gradientX = record[body + 1];
        final int gradientY = record[body + 2];
        for (int column = 0; column < size; column++) {
          luma[column] = (dc + gradientX * (2 * column + 1 - size) + gradientY * (2 * row + 1 - size)) * PREDICTION_SCALE * size;
        }
      } else {
        vertical(scratch.rows0, grid, size, row, luma);
      }
      final int from = row * size * CHANNELS;
      for (int column = 0; column < size; column++) {
        final int at = from + column * CHANNELS;
        final int scaledLuma = luma[column] << quantizer;
        out[at] = round(prediction[at] * quarter + scaledLuma + red, shift);
        out[at + 1] = round(prediction[at + 1] * quarter + scaledLuma + green, shift);
        out[at + 2] = round(prediction[at + 2] * quarter + scaledLuma + blue, shift);
      }
      if (score != null && !score.row(out, from, size)) {
        return false;
      }
    }
    return true;
  }

  /**
   * The chroma offset of a grid class, stored after its luma nodes or DC: {@code which} 0 for Co, 1 for Cg; zero for
   * the luma-only class.
   */
  private static int chromaOffset(final byte[] record, final int body, final int kind, final int which) {
    return switch (kind) {
      case CompactRecord.GRID2_YC -> record[body + SMALL_GRID * SMALL_GRID + which];
      case CompactRecord.GRID4_N4_YC -> record[body + (COMPACT_GRID * COMPACT_GRID) / NIBBLES_PER_BYTE + which];
      case CompactRecord.GRID4_YC -> record[body + COMPACT_GRID * COMPACT_GRID + which];
      case CompactRecord.VQ64, CompactRecord.PQ64 -> record[body + 1 + which];
      default -> 0;
    };
  }

  /** Reads a grid class's luma nodes into {@code nodes} and returns the grid width. */
  private static int nodes(final byte[] record, final int body, final int kind, final int[] nodes) {
    return switch (kind) {
      case CompactRecord.GRID2_YC -> {
        for (int nodeIndex = 0; nodeIndex < SMALL_GRID * SMALL_GRID; nodeIndex++) {
          nodes[nodeIndex] = record[body + nodeIndex];
        }
        yield SMALL_GRID;
      }
      case CompactRecord.GRID4_N4_YC, CompactRecord.GRID4_N4_Y -> {
        for (int nodeIndex = 0; nodeIndex < COMPACT_GRID * COMPACT_GRID; nodeIndex++) {
          final int packed = Byte.toUnsignedInt(record[body + nodeIndex / NIBBLES_PER_BYTE]);
          nodes[nodeIndex] = signed((packed >> ((nodeIndex % NIBBLES_PER_BYTE) * NIBBLE_BITS)) & NIBBLE_MASK, NIBBLE_BITS);
        }
        yield COMPACT_GRID;
      }
      case CompactRecord.GRID4_YC -> {
        for (int nodeIndex = 0; nodeIndex < COMPACT_GRID * COMPACT_GRID; nodeIndex++) {
          nodes[nodeIndex] = record[body + nodeIndex];
        }
        yield COMPACT_GRID;
      }
      default -> {
        // VQ64 and PQ64: the DC triple, then one book index, or two packed into twelve bits
        final int dc = record[body];
        final int low = Byte.toUnsignedInt(record[body + 3]);
        final int ids = kind == CompactRecord.PQ64 ? low | (Byte.toUnsignedInt(record[body + 4]) << Byte.SIZE) : low;
        for (int nodeIndex = 0; nodeIndex < COMPACT_GRID * COMPACT_GRID; nodeIndex++) {
          final int row = nodeIndex / COMPACT_GRID;
          final int column = nodeIndex % COMPACT_GRID;
          final int book;
          if (kind == CompactRecord.VQ64) {
            book = ResidualBooks.vq(ids, nodeIndex);
          } else if (column < HALF_COLUMNS) {
            book = ResidualBooks.pq(0, ids & (ResidualBooks.VECTORS - 1), row * HALF_COLUMNS + column);
          } else {
            book = ResidualBooks.pq(1, ids >> BOOK_INDEX_BITS, row * HALF_COLUMNS + column - HALF_COLUMNS);
          }
          nodes[nodeIndex] = book + dc;
        }
        yield COMPACT_GRID;
      }
    };
  }
}
