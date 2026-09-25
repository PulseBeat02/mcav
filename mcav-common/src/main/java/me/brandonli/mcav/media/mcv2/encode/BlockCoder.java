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

import static me.brandonli.mcav.media.mcv2.Mcv2Format.*;

import me.brandonli.mcav.media.mcv2.CompactRecord;
import me.brandonli.mcav.media.mcv2.Reconstruction;

/**
 * Evaluates every candidate record of one block for every trial of a frame, in the reference encoder's order, and
 * keeps each trial's first strictly cheapest candidate.
 *
 * <p>A trial is one of the reference's complete encodes of a frame: a global vector (zero or the estimate) and an
 * endpoint precision (full or RGB565). Intra candidates are the same in every trial and are evaluated once; temporal
 * candidates once per vector; pattern candidates once per precision. A candidate is skipped when its rate alone cannot
 * beat the best cost of any trial it belongs to: distortion is never negative, so such a candidate cannot win and
 * skipping it changes nothing.
 *
 * <p>Every candidate is reconstructed with the decoder's own kernels ({@link Reconstruction}), so the distortion the
 * search minimizes is measured on exactly the pixels the decoder will produce. Instances hold scratch buffers, allocate
 * nothing per candidate, and are confined to one thread.
 */
final class BlockCoder {

  /** Descriptor bits the matched cost model charges per leaf in the derived two-level form: 8 + 80/32. */
  static final double INDEX_BITS = 10.5;

  private static final int[] COMPACT_CLASSES = { 0, 1, 2, 3, 4, 8 };

  private final FrameJob job;
  private final int size;
  private final int count;
  private final int[] source;
  private final float[] ycocg;
  private final float[] target;
  private final int[][] globalPrediction;
  private final int[][] localPrediction;
  private final int[] localVectors;
  private final int[] recon;
  private final byte[] record = new byte[2 + 3 * 64];
  private final byte[] palette = new byte[6 + (32 * 32) / 8];
  private final Reconstruction.Scratch scratch = new Reconstruction.Scratch();
  private final float[] grid = new float[3 * 64];
  private final float[] fit = new float[32];
  private final double[] fitScratch = new double[32 * 8];
  private final int[] colors = new int[6];
  private final byte[] selectors;
  private final float[] axis;
  private final float meanSquare;

  private int level;
  private int block;
  private double rate;

  BlockCoder(final FrameJob job, final int size) {
    this.job = job;
    this.size = size;
    this.count = size * size;
    this.source = new int[this.count * 3];
    this.ycocg = new float[this.count * 3];
    this.target = new float[this.count * 3];
    this.globalPrediction = new int[job.vectorCount()][this.count * 3];
    this.localPrediction = new int[job.vectorCount()][this.count * 3];
    this.localVectors = new int[job.vectorCount()];
    this.recon = new int[this.count * 3];
    this.selectors = new byte[this.count];
    this.axis = new float[size];
    double squares = 0;
    for (int i = 0; i < size; i++) {
      this.axis[i] = ((i + 0.5f) / size) * 2.0f - 1.0f;
      squares += this.axis[i] * this.axis[i];
    }
    this.meanSquare = (float) (squares / size);
  }

  /**
   * Evaluates one block.
   *
   * @param level the level, 0 for 32, 1 for 16, 2 for 8
   * @param block the block index in raster order at that level
   * @param x     the block's left edge
   * @param y     the block's top edge
   */
  void code(final int level, final int block, final int x, final int y) {
    this.level = level;
    this.block = block;
    this.loadSource(x, y);
    final FrameJob j = this.job;
    final boolean temporal = !j.isKeyframe();
    if (temporal) {
      for (int v = 0; v < j.vectorCount(); v++) {
        Reconstruction.predict(j.reference(), j.width(), j.height(), x, y, this.size, j.vectorX(v), j.vectorY(v), this.globalPrediction[v]);
        // the first candidate of its trials, so always eligible; the call sets the rate for the score
        this.eligible(MODE_SKIP, 0, j.vectorMask(v));
        Reconstruction.predicted(this.globalPrediction[v], this.size, this.recon);
        this.score(MODE_SKIP, 0, 0, j.vectorMask(v));
      }
      for (int v = 0; v < j.vectorCount(); v++) {
        final int vector = MotionSearch.search(
          j.reference(),
          j.width(),
          j.height(),
          this.source,
          x,
          y,
          this.size,
          j.vectorX(v),
          j.vectorY(v),
          j.settings().motionRange(),
          j.steps()
        );
        this.localVectors[v] = vector;
        Reconstruction.predict(
          j.reference(),
          j.width(),
          j.height(),
          x,
          y,
          this.size,
          vector >> 16,
          (short) vector,
          this.localPrediction[v]
        );
      }
      for (int v = 0; v < j.vectorCount(); v++) {
        if (this.eligible(MODE_MOTION, 2, j.vectorMask(v))) {
          this.setMotionBytes(v);
          Reconstruction.predicted(this.localPrediction[v], this.size, this.recon);
          this.score(MODE_MOTION, 0, 2, j.vectorMask(v));
        }
      }
    }
    this.solid();
    this.palette();
    for (int g = 1; g <= 8; g *= 2) {
      if (g > 1) {
        this.intraGrid(g);
      }
      for (int v = 0; temporal && v < j.vectorCount(); v++) {
        this.residualGrid(v, g);
      }
    }
    for (int luma = 4; luma <= 8; luma *= 2) {
      final int chroma = luma == 4 ? 1 : 2;
      this.reducedIntra(luma, chroma);
      for (int v = 0; temporal && v < j.vectorCount(); v++) {
        this.reducedResidual(v, luma, chroma);
      }
    }
    this.pattern(false);
    this.pattern(true);
    for (int v = 0; temporal && v < j.vectorCount(); v++) {
      this.compact(v, false);
    }
    for (int v = 0; temporal && v < j.vectorCount(); v++) {
      this.compact(v, true);
    }
  }

  private void loadSource(final int x, final int y) {
    final FrameJob j = this.job;
    final byte[] image = j.source();
    for (int py = 0; py < this.size; py++) {
      final int sy = Math.min(y + py, j.height() - 1);
      for (int px = 0; px < this.size; px++) {
        final int sx = Math.min(x + px, j.width() - 1);
        final int from = (sy * j.width() + sx) * 3;
        final int to = (py * this.size + px) * 3;
        final int r = image[from] & 0xFF;
        final int g = image[from + 1] & 0xFF;
        final int b = image[from + 2] & 0xFF;
        this.source[to] = r;
        this.source[to + 1] = g;
        this.source[to + 2] = b;
        this.ycocg[to] = (r + 2 * g + b) * 0.25f;
        this.ycocg[to + 1] = (r - b) * 0.5f;
        this.ycocg[to + 2] = (-r + 2 * g - b) * 0.25f;
      }
    }
  }

  /** Sixteen times six times the weighted YCoCg squared error of the reconstruction, an exact integer. */
  private long distortion() {
    long sum = 0;
    final int[] s = this.source;
    final int[] r = this.recon;
    for (int i = 0; i < s.length; i += 3) {
      final int dr = s[i] - r[i];
      final int dg = s[i + 1] - r[i + 1];
      final int db = s[i + 2] - r[i + 2];
      final int luma = dr + 2 * dg + db;
      final int co = dr - db;
      final int cg = 2 * dg - dr - db;
      sum += 4L * luma * luma + 4L * co * co + (long) cg * cg;
    }
    return sum;
  }

  /**
   * Checks whether a candidate's rate alone leaves it a chance in at least one of its trials, and remembers the rate
   * for {@link #score}.
   */
  private boolean eligible(final int mode, final int length, final int mask) {
    final FrameJob j = this.job;
    final double bits = mode == MODE_SKIP && this.size == ROOT_SIZE ? 1.0 : INDEX_BITS;
    this.rate = j.settings().lambda() * (length * 8 + bits);
    for (int t = 0; t < j.trialCount(); t++) {
      if (((mask >> t) & 1) != 0 && j.cost(t, this.level)[this.block] > this.rate) {
        return true;
      }
    }
    return false;
  }

  /** Scores the reconstruction in {@link #recon} and records it for every trial it improves. */
  private void score(final int mode, final int q, final int length, final int mask) {
    final FrameJob j = this.job;
    final long d16 = this.distortion();
    final double cost = d16 / 96.0 + this.rate;
    byte[] copy = null;
    for (int t = 0; t < j.trialCount(); t++) {
      if (((mask >> t) & 1) != 0 && cost < j.cost(t, this.level)[this.block]) {
        if (copy == null) {
          copy = new byte[length];
          System.arraycopy(this.record, 0, copy, 0, length);
        }
        j.set(t, this.level, this.block, cost, mode, q, copy, d16);
      }
    }
  }

  private void setMotionBytes(final int v) {
    final int vector = this.localVectors[v];
    this.record[0] = (byte) ((vector >> 16) - this.job.vectorX(v));
    this.record[1] = (byte) ((short) vector - this.job.vectorY(v));
  }

  private void solid() {
    if (!this.eligible(MODE_SOLID, 3, this.job.allTrials())) {
      return;
    }
    long r = 0;
    long g = 0;
    long b = 0;
    for (int i = 0; i < this.count; i++) {
      r += this.source[i * 3];
      g += this.source[i * 3 + 1];
      b += this.source[i * 3 + 2];
    }
    final int color = (roundMean(r, this.count) << 16) | (roundMean(g, this.count) << 8) | roundMean(b, this.count);
    this.record[0] = (byte) (color >> 16);
    this.record[1] = (byte) (color >> 8);
    this.record[2] = (byte) color;
    Reconstruction.solid(color, this.size, this.recon);
    this.score(MODE_SOLID, 0, 3, this.job.allTrials());
  }

  /** The reference's {@code rgb8} of a float64 mean of integers. */
  private static int roundMean(final long sum, final int count) {
    final double mean = (double) sum / count;
    return (int) Math.floor(Math.min(Math.max(mean, 0.0), 255.0) + 0.5);
  }

  private void writePalette(final byte[] target) {
    for (int i = 0; i < 6; i++) {
      target[i] = (byte) this.colors[i];
    }
    for (int i = 0; i < this.count / 8; i++) {
      target[6 + i] = 0;
    }
    for (int i = 0; i < this.count; i++) {
      target[6 + i / 8] |= (byte) (this.selectors[i] << (i & 7));
    }
  }

  private void palette() {
    final int length = 6 + this.count / 8;
    if (!this.eligible(MODE_PALETTE, length, this.job.allTrials())) {
      return;
    }
    PaletteFit.fit(this.source, this.count, false, this.colors, this.selectors);
    this.writePalette(this.record);
    Reconstruction.palette(this.record, 0, this.size, this.recon);
    this.score(MODE_PALETTE, 0, length, this.job.allTrials());
  }

  private void pattern(final boolean coarse) {
    final int length = 7 + this.size / 8;
    final int mask = this.job.coarseMask(coarse);
    if (!this.eligible(MODE_PATTERN, length, mask)) {
      return;
    }
    PaletteFit.fit(this.source, this.count, coarse, this.colors, this.selectors);
    this.writePalette(this.palette);
    final byte[] pattern = TreeReader.patternRecord(this.palette, this.size);
    if (pattern == null) {
      return;
    }
    System.arraycopy(pattern, 0, this.record, 0, pattern.length);
    Reconstruction.palette(this.palette, 0, this.size, this.recon);
    this.score(MODE_PATTERN, 0, length, mask);
  }

  private static int quantize(final float value, final int step, final int low, final int high) {
    final float scaled = (float) Math.floor(value / step + 0.5f);
    return (int) Math.min(Math.max(scaled, low), high);
  }

  private void intraGrid(final int g) {
    final int length = 3 * g * g;
    final int mode = MODE_INTRA + Integer.numberOfTrailingZeros(g);
    if (!this.eligible(mode, length, this.job.allTrials())) {
      return;
    }
    for (int i = 0; i < this.count * 3; i++) {
      this.target[i] = this.source[i];
    }
    for (int c = 0; c < 3; c++) {
      Fits.fit(this.target, c, 3, this.size, g, this.fitScratch, this.grid, c, 3);
    }
    for (int i = 0; i < length; i++) {
      this.record[i] = (byte) Reconstruction.rgb8(this.grid[i]);
    }
    Reconstruction.intraGrid(this.record, 0, g, this.size, this.scratch, this.recon);
    this.score(mode, 0, length, this.job.allTrials());
  }

  /** The YCoCg residual of the source against a prediction, into {@link #target}. */
  private void residualTarget(final int[] prediction) {
    for (int i = 0; i < this.count * 3; i += 3) {
      final float r = prediction[i] * 0.25f;
      final float g = prediction[i + 1] * 0.25f;
      final float b = prediction[i + 2] * 0.25f;
      this.target[i] = this.ycocg[i] - (r + 2 * g + b) * 0.25f;
      this.target[i + 1] = this.ycocg[i + 1] - (r - b) * 0.5f;
      this.target[i + 2] = this.ycocg[i + 2] - (-r + 2 * g - b) * 0.25f;
    }
  }

  private void residualGrid(final int v, final int g) {
    final int length = 2 + 3 * g * g;
    final int mode = MODE_RESIDUAL + Integer.numberOfTrailingZeros(g);
    final int mask = this.job.vectorMask(v);
    if (!this.eligible(mode, length, mask)) {
      return;
    }
    this.residualTarget(this.localPrediction[v]);
    for (int c = 0; c < 3; c++) {
      Fits.fit(this.target, c, 3, this.size, g, this.fitScratch, this.grid, c, 3);
    }
    for (int q = 0; q < 4; q++) {
      if (!this.eligible(mode, length, mask)) {
        return;
      }
      this.setMotionBytes(v);
      for (int i = 0; i < 3 * g * g; i++) {
        this.record[2 + i] = (byte) quantize(this.grid[i], 1 << q, -128, 127);
      }
      Reconstruction.residualGrid(this.localPrediction[v], this.record, 2, g, q, this.size, this.scratch, this.recon);
      this.score(mode, q, length, mask);
    }
  }

  /** Fits the luma of {@link #target} into grid[0..] and its interleaved chroma into grid[64..]. */
  private void fitReduced(final int luma, final int chroma) {
    Fits.fit(this.target, 0, 3, this.size, luma, this.fitScratch, this.grid, 0, 1);
    Fits.fit(this.target, 1, 3, this.size, chroma, this.fitScratch, this.grid, 64, 2);
    Fits.fit(this.target, 2, 3, this.size, chroma, this.fitScratch, this.grid, 65, 2);
  }

  private void reducedIntra(final int luma, final int chroma) {
    final int length = luma * luma + 2 * chroma * chroma;
    final int mode = luma == 4 ? MODE_INTRA_Y4C1 : MODE_INTRA_Y8C2;
    if (!this.eligible(mode, length, this.job.allTrials())) {
      return;
    }
    System.arraycopy(this.ycocg, 0, this.target, 0, this.count * 3);
    this.fitReduced(luma, chroma);
    for (int i = 0; i < luma * luma; i++) {
      this.record[i] = (byte) quantize(this.grid[i], 1, 0, 255);
    }
    for (int i = 0; i < 2 * chroma * chroma; i++) {
      this.record[luma * luma + i] = (byte) quantize(this.grid[64 + i], 1, -128, 127);
    }
    Reconstruction.reduced(null, this.record, 0, luma, chroma, 0, this.size, this.scratch, this.recon);
    this.score(mode, 0, length, this.job.allTrials());
  }

  private void reducedResidual(final int v, final int luma, final int chroma) {
    final int length = 2 + luma * luma + 2 * chroma * chroma;
    final int mode = luma == 4 ? MODE_RESIDUAL_Y4C1 : MODE_RESIDUAL_Y8C2;
    final int mask = this.job.vectorMask(v);
    if (!this.eligible(mode, length, mask)) {
      return;
    }
    this.residualTarget(this.localPrediction[v]);
    this.fitReduced(luma, chroma);
    for (int q = 0; q < 4; q++) {
      if (!this.eligible(mode, length, mask)) {
        return;
      }
      this.setMotionBytes(v);
      for (int i = 0; i < luma * luma; i++) {
        this.record[2 + i] = (byte) quantize(this.grid[i], 1 << q, -128, 127);
      }
      for (int i = 0; i < 2 * chroma * chroma; i++) {
        this.record[2 + luma * luma + i] = (byte) quantize(this.grid[64 + i], 1 << q, -128, 127);
      }
      Reconstruction.reduced(this.localPrediction[v], this.record, 2, luma, chroma, q, this.size, this.scratch, this.recon);
      this.score(mode, q, length, mask);
    }
  }

  private void compact(final int v, final boolean local) {
    final int[] prediction = local ? this.localPrediction[v] : this.globalPrediction[v];
    final int dx = local ? (this.localVectors[v] >> 16) - this.job.vectorX(v) : 0;
    final int dy = local ? (short) this.localVectors[v] - this.job.vectorY(v) : 0;
    final int form = dx == 0 && dy == 0 ? 0 : (dx >= -8 && dx <= 7 && dy >= -8 && dy <= 7 ? 1 : 2);
    final int mask = this.job.vectorMask(v);
    boolean targeted = false;
    for (final int kind : COMPACT_CLASSES) {
      final int length = 1 + form + CompactRecord.bodyBytes(kind);
      if (!this.eligible(MODE_COMPACT, length, mask)) {
        continue;
      }
      if (!targeted) {
        this.residualTarget(prediction);
        targeted = true;
      }
      final int values = this.compactFit(kind);
      final int body = 1 + form;
      for (int q = 0; q < 5; q++) {
        if (!this.eligible(MODE_COMPACT, length, mask)) {
          break;
        }
        this.record[0] = (byte) (kind | (form << 4));
        if (form == 1) {
          this.record[1] = (byte) ((dx & 15) | ((dy & 15) << 4));
        } else if (form == 2) {
          this.record[1] = (byte) dx;
          this.record[2] = (byte) dy;
        }
        this.compactBody(kind, values, q, body);
        Reconstruction.compact(prediction, this.record, body, kind, q, this.size, this.scratch, this.recon);
        this.score(MODE_COMPACT, q, length, mask);
      }
    }
  }

  /** Fits a compact class to {@link #target} into {@link #fit}; returns how many values it holds. */
  private int compactFit(final int kind) {
    final float[] t = this.target;
    if (kind == CompactRecord.DC_Y) {
      this.fit[0] = this.mean(0);
      return 1;
    }
    if (kind == CompactRecord.LOW2) {
      double sx = 0;
      double sy = 0;
      for (int py = 0; py < this.size; py++) {
        for (int px = 0; px < this.size; px++) {
          final float luma = t[(py * this.size + px) * 3];
          sx += luma * this.axis[px];
          sy += luma * this.axis[py];
        }
      }
      this.fit[0] = this.mean(0);
      this.fit[1] = (float) (sx / this.count) / this.meanSquare;
      this.fit[2] = (float) (sy / this.count) / this.meanSquare;
      this.fit[3] = this.mean(1);
      this.fit[4] = this.mean(2);
      return 5;
    }
    final int g = kind == CompactRecord.GRID2_YC ? 2 : 4;
    Fits.fit(t, 0, 3, this.size, g, this.fitScratch, this.fit, 0, 1);
    this.fit[g * g] = this.mean(1);
    this.fit[g * g + 1] = this.mean(2);
    return g * g + 2;
  }

  private float mean(final int channel) {
    double sum = 0;
    for (int i = channel; i < this.target.length; i += 3) {
      sum += this.target[i];
    }
    return (float) (sum / this.count);
  }

  private void compactBody(final int kind, final int values, final int q, final int body) {
    final int step = 1 << q;
    if (kind == CompactRecord.GRID4_N4_YC || kind == CompactRecord.GRID4_N4_Y) {
      for (int i = 0; i < 8; i++) {
        final int low = quantize(this.fit[2 * i], step, -8, 7) & 15;
        final int high = quantize(this.fit[2 * i + 1], step, -8, 7) & 15;
        this.record[body + i] = (byte) (low | (high << 4));
      }
      if (kind == CompactRecord.GRID4_N4_YC) {
        this.record[body + 8] = (byte) quantize(this.fit[16], step, -128, 127);
        this.record[body + 9] = (byte) quantize(this.fit[17], step, -128, 127);
      }
      return;
    }
    for (int i = 0; i < values; i++) {
      this.record[body + i] = (byte) quantize(this.fit[i], step, -128, 127);
    }
  }
}
