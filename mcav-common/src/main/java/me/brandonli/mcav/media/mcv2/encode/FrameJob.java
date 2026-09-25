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
  private final double[][][] costs;
  private final byte[][][] modes;
  private final byte[][][] quantizers;
  private final byte[][][][] records;
  private final long[][][] distortions;
  private final int[] columns = new int[3];

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
    this.costs = new double[this.trials][3][];
    this.modes = new byte[this.trials][3][];
    this.quantizers = new byte[this.trials][3][];
    this.records = new byte[this.trials][3][][];
    this.distortions = new long[this.trials][3][];
    for (int level = 0; level < 3; level++) {
      final int size = 32 >> level;
      this.columns[level] = (width + size - 1) / size;
      final int blocks = this.columns[level] * ((height + size - 1) / size);
      for (int t = 0; t < this.trials; t++) {
        this.costs[t][level] = new double[blocks];
        Arrays.fill(this.costs[t][level], Double.POSITIVE_INFINITY);
        this.modes[t][level] = new byte[blocks];
        this.quantizers[t][level] = new byte[blocks];
        this.records[t][level] = new byte[blocks][];
        this.distortions[t][level] = new long[blocks];
      }
    }
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
   * The motion of the previous frame at a pixel, as {@code x << 16 | (y & 0xFFFF)} in half pixels.
   *
   * @param x the column, clamped to the picture
   * @param y the row, clamped to the picture
   * @return the vector, or the first global vector when the previous frame's motion is unknown
   */
  int previousMotion(final int x, final int y) {
    final int[] field = this.previousMotion;
    if (field == null) {
      return (this.vectorsX[0] << 16) | (this.vectorsY[0] & 0xFFFF);
    }
    final int cx = Math.min(Math.max(x, 0), this.width - 1) / 8;
    final int cy = Math.min(Math.max(y, 0), this.height - 1) / 8;
    return field[cy * ((this.width + 7) / 8) + cx];
  }

  int allTrials() {
    return (1 << this.trials) - 1;
  }

  int columns(final int level) {
    return this.columns[level];
  }

  double[] cost(final int trial, final int level) {
    return this.costs[trial][level];
  }

  int mode(final int trial, final int level, final int block) {
    return this.modes[trial][level][block];
  }

  int quantizer(final int trial, final int level, final int block) {
    return this.quantizers[trial][level][block];
  }

  byte[] record(final int trial, final int level, final int block) {
    return this.records[trial][level][block];
  }

  long distortion(final int trial, final int level, final int block) {
    return this.distortions[trial][level][block];
  }

  void set(
    final int trial,
    final int level,
    final int block,
    final double cost,
    final int mode,
    final int q,
    final byte[] record,
    final long d16
  ) {
    this.costs[trial][level][block] = cost;
    this.modes[trial][level][block] = (byte) mode;
    this.quantizers[trial][level][block] = (byte) q;
    this.records[trial][level][block] = record;
    this.distortions[trial][level][block] = d16;
  }
}
