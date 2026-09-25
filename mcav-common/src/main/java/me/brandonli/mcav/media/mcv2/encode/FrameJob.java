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

/**
 * The state one frame's encode shares between its block workers: the pictures, the trials, and each trial's best
 * candidate per block per level. Every block is written by exactly one worker, so the arrays need no locking; they
 * are read only after all workers have finished.
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
  private final int trials;
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
    final int[] vectorsY
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
    this.trials = vectorsX.length * 2;
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

  /** Trials 2v and 2v+1 use vector v; the even trial keeps full endpoints, the odd one allows RGB565. */
  int vectorMask(final int v) {
    return 3 << (2 * v);
  }

  int coarseMask(final boolean coarse) {
    int mask = 0;
    for (int t = coarse ? 1 : 0; t < this.trials; t += 2) {
      mask |= 1 << t;
    }
    return mask;
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
