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

import static me.brandonli.mcav.media.mcv2.Mcv2Format.BLOCK_SIZES;
import static me.brandonli.mcav.media.mcv2.Mcv2Format.CHANNELS;
import static me.brandonli.mcav.media.mcv2.Mcv2Format.MAX_GRID;
import static me.brandonli.mcav.media.mcv2.Mcv2Format.MOTION_BYTES;
import static me.brandonli.mcav.media.mcv2.Mcv2Format.ROOT_SIZE;
import static me.brandonli.mcav.media.mcv2.Mcv2Format.SMALLEST_BLOCK;

import com.google.common.base.Preconditions;
import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The state one frame's encode shares between its block workers: the pictures, the trials, and each trial's best
 * candidate per block per level. Every block is written by exactly one worker, so the arrays need no locking; they
 * are read only after all workers have finished.
 *
 * <p>A trial is a global vector and an endpoint precision. The reference search tries both precisions of every vector,
 * trials {@code 2v} (full endpoints) and {@code 2v + 1} (RGB565); a live search tries full endpoints only, so trial
 * {@code v} is vector {@code v}. A live search also reads the motion of the previous frame, one vector per 8x8 cell, to
 * seed its local motion search.
 */
final class FrameJob {

  private final EncoderSettings settings;

  private final byte[] source;

  private final byte[] reference;

  private final int width;

  private final int height;

  private final boolean keyframe;

  private final int[] vectorsX;

  private final int[] vectorsY;

  private final int[] steps;

  private final int precisions;

  private final int trials;

  private final int@Nullable[] previousMotion;

  private final byte@Nullable[][] levelPictures;

  private final Buffers buffers;

  private final int[] columns = new int[BLOCK_SIZES];

  private byte@Nullable[] halfReference;

  /** The side of the square cells of a motion field, one vector each: the smallest block. */
  static final int MOTION_CELL = SMALLEST_BLOCK;

  /** The longest record a candidate writes: a 32-pixel residual grid of 8x8 nodes after its two motion bytes. */
  static final int MAX_RECORD = MOTION_BYTES + CHANNELS * MAX_GRID * MAX_GRID;

  /**
   * The arrays one frame's search fills, reused from frame to frame while the size and the number of trials stay the
   * same, so that measuring a candidate allocates nothing: per trial and level, each block's best cost, mode,
   * quantizer, distortion and record, the record in a flat buffer with room for the longest.
   */
  static final class Buffers {

    private final int width;

    private final int height;

    private final int trials;

    private final double[][][] costs;

    private final byte[][][] modes;

    private final byte[][][] quantizers;

    private final byte[][][] records;

    private final byte[][][] lengths;

    private final long[][][] distortions;

    /**
     * Makes the arrays of a size and number of trials.
     *
     * @param width  the width
     * @param height the height
     * @param trials the number of trials
     */
    Buffers(final int width, final int height, final int trials) {
      this.width = width;
      this.height = height;
      this.trials = trials;
      this.costs = new double[trials][BLOCK_SIZES][];
      this.modes = new byte[trials][BLOCK_SIZES][];
      this.quantizers = new byte[trials][BLOCK_SIZES][];
      this.records = new byte[trials][BLOCK_SIZES][];
      this.lengths = new byte[trials][BLOCK_SIZES][];
      this.distortions = new long[trials][BLOCK_SIZES][];
      for (int level = 0; level < BLOCK_SIZES; level++) {
        final int size = ROOT_SIZE >> level;
        final int blocks = ((width + size - 1) / size) * ((height + size - 1) / size);
        for (int t = 0; t < trials; t++) {
          this.costs[t][level] = new double[blocks];
          this.modes[t][level] = new byte[blocks];
          this.quantizers[t][level] = new byte[blocks];
          this.records[t][level] = new byte[blocks * MAX_RECORD];
          this.lengths[t][level] = new byte[blocks];
          this.distortions[t][level] = new long[blocks];
        }
      }
    }

    /**
     * Checks whether these arrays serve a frame.
     *
     * @param w the frame's width
     * @param h the frame's height
     * @param t the frame's number of trials
     * @return true if they do
     */
    boolean fits(final int w, final int h, final int t) {
      return this.width == w && this.height == h && this.trials == t;
    }
  }

  FrameJob(
    final EncoderSettings settings,
    final byte[] source,
    final byte[] reference,
    final int width,
    final int height,
    final boolean keyframe,
    final int[] vectorsX,
    final int[] vectorsY,
    final int@Nullable[] previousMotion,
    final byte@Nullable[][] levelPictures
  ) {
    this(settings, source, reference, width, height, keyframe, vectorsX, vectorsY, previousMotion, levelPictures, null);
  }

  /**
   * Constructs a frame's search state, reusing the arrays of an earlier frame of the same size and trials.
   *
   * @param settings       the profile
   * @param source         the picture
   * @param reference      the picture P frames predict from, empty for a keyframe
   * @param width          the width
   * @param height         the height
   * @param keyframe       whether the frame is a keyframe
   * @param vectorsX       the global vectors' horizontal parts
   * @param vectorsY       the global vectors' vertical parts
   * @param previousMotion the previous frame's motion, one vector per 8x8 cell, or null
   * @param levelPictures  the pictures of a live search's best reconstructions per level, or null
   * @param reuse          arrays to reuse when they fit the frame, or null
   */
  FrameJob(
    final EncoderSettings settings,
    final byte[] source,
    final byte[] reference,
    final int width,
    final int height,
    final boolean keyframe,
    final int[] vectorsX,
    final int[] vectorsY,
    final int@Nullable[] previousMotion,
    final byte@Nullable[][] levelPictures,
    final @Nullable Buffers reuse
  ) {
    this.settings = settings;
    this.source = source;
    this.reference = reference;
    this.width = width;
    this.height = height;
    this.keyframe = keyframe;
    this.vectorsX = vectorsX;
    this.vectorsY = vectorsY;
    this.steps = MotionSearch.steps(settings.motionRange(), settings.halfPixel());
    this.precisions = settings.live() == null ? 2 : 1;
    this.trials = vectorsX.length * this.precisions;
    this.previousMotion = previousMotion;
    this.levelPictures = levelPictures;
    this.buffers = reuse != null && reuse.fits(width, height, this.trials) ? reuse : new Buffers(width, height, this.trials);
    for (int level = 0; level < BLOCK_SIZES; level++) {
      final int size = ROOT_SIZE >> level;
      this.columns[level] = (width + size - 1) / size;
      for (int t = 0; t < this.trials; t++) {
        // no block is evaluated yet; the other arrays are only read at blocks that were
        Arrays.fill(this.buffers.costs[t][level], Double.POSITIVE_INFINITY);
      }
    }
  }

  /**
   * Gets the arrays this frame fills, for the next frame to reuse.
   *
   * @return the arrays
   */
  Buffers buffers() {
    return this.buffers;
  }

  EncoderSettings settings() {
    return this.settings;
  }

  byte[] source() {
    return this.source;
  }

  byte[] reference() {
    return this.reference;
  }

  int width() {
    return this.width;
  }

  int height() {
    return this.height;
  }

  boolean isKeyframe() {
    return this.keyframe;
  }

  int[] steps() {
    return this.steps;
  }

  int vectorCount() {
    return this.vectorsX.length;
  }

  int vectorX(final int v) {
    return this.vectorsX[v];
  }

  int vectorY(final int v) {
    return this.vectorsY[v];
  }

  int trialCount() {
    return this.trials;
  }

  /**
   * The picture a live search keeps per level: at every block it evaluated, the reconstruction of the block's best
   * candidate, from which the frame's decoded picture is assembled without decoding it.
   *
   * @param level the level
   * @return the picture, or null for the reference search, which decodes its trials instead
   */
  byte@Nullable[] levelPicture(final int level) {
    final byte[][] pictures = this.levelPictures;
    return pictures == null ? null : pictures[level];
  }

  /** The trials of vector v: 2v and 2v+1 in the reference search, v in a live one. */
  int vectorMask(final int v) {
    return ((1 << this.precisions) - 1) << (this.precisions * v);
  }

  /** The trials with RGB565 endpoints, or with full ones. */
  int coarseMask(final boolean coarse) {
    int mask = 0;
    for (int t = 0; t < this.trials; t++) {
      if (this.isCoarse(t) == coarse) {
        mask |= 1 << t;
      }
    }
    return mask;
  }

  /** The vector a trial uses. */
  int trialVector(final int trial) {
    return trial / this.precisions;
  }

  /** Whether a trial allows RGB565 pattern endpoints. */
  boolean isCoarse(final int trial) {
    final LiveSearch live = this.settings.live();
    return live == null ? (trial & 1) != 0 : live.coarseEndpoints();
  }

  /**
   * The columns of a motion field over a picture.
   *
   * @param width the picture's width
   * @return its cells per row
   */
  static int motionColumns(final int width) {
    return (width + MOTION_CELL - 1) / MOTION_CELL;
  }

  /**
   * The motion of the previous frame at a pixel, as {@code x << 16 | (y & 0xFFFF)} in half pixels.
   *
   * @param x the column, clamped to the picture
   * @param y the row, clamped to the picture
   * @return the vector, or the first global vector when the previous frame's motion is unknown
   */
  int previousMotion(final int x, final int y) {
    final int[] field = this.previousMotion;
    if (field == null) {
      return MotionSearch.pack(this.vectorsX[0], this.vectorsY[0]);
    }
    final int cx = Math.min(Math.max(x, 0), this.width - 1) / MOTION_CELL;
    final int cy = Math.min(Math.max(y, 0), this.height - 1) / MOTION_CELL;
    return field[cy * motionColumns(this.width) + cx];
  }

  /**
   * Gives the search the reference at half resolution, for {@link LiveSearch#HALF_MOTION}.
   *
   * @param picture the reference, {@code ceil(width / 2) * ceil(height / 2)} RGB pixels, each the mean of a 2x2 square
   */
  void halfReference(final byte[] picture) {
    this.halfReference = picture;
  }

  /**
   * The reference at half resolution.
   *
   * @return the picture {@link #halfReference(byte[])} gave
   */
  byte[] halfReference() {
    return Preconditions.checkNotNull(this.halfReference);
  }

  int allTrials() {
    return (1 << this.trials) - 1;
  }

  int columns(final int level) {
    return this.columns[level];
  }

  double[] cost(final int trial, final int level) {
    return this.buffers.costs[trial][level];
  }

  int mode(final int trial, final int level, final int block) {
    return this.buffers.modes[trial][level][block];
  }

  int quantizer(final int trial, final int level, final int block) {
    return this.buffers.quantizers[trial][level][block];
  }

  /** A copy of the block's best record. */
  byte[] record(final int trial, final int level, final int block) {
    final int at = block * MAX_RECORD;
    return Arrays.copyOfRange(this.buffers.records[trial][level], at, at + (this.buffers.lengths[trial][level][block] & 0xFF));
  }

  long distortion(final int trial, final int level, final int block) {
    return this.buffers.distortions[trial][level][block];
  }

  /**
   * Records a block's new best candidate.
   *
   * @param trial  the trial
   * @param level  the level
   * @param block  the block
   * @param cost   the candidate's cost
   * @param mode   its mode
   * @param q      its quantizer
   * @param record its record, copied
   * @param length the record's length
   * @param d16    its distortion
   */
  void set(
    final int trial,
    final int level,
    final int block,
    final double cost,
    final int mode,
    final int q,
    final byte[] record,
    final int length,
    final long d16
  ) {
    final Buffers b = this.buffers;
    b.costs[trial][level][block] = cost;
    b.modes[trial][level][block] = (byte) mode;
    b.quantizers[trial][level][block] = (byte) q;
    System.arraycopy(record, 0, b.records[trial][level], block * MAX_RECORD, length);
    b.lengths[trial][level][block] = (byte) length;
    b.distortions[trial][level][block] = d16;
  }
}
