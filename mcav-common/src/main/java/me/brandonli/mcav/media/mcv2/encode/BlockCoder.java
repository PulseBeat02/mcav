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

import com.google.common.base.Preconditions;
import java.util.Arrays;
import me.brandonli.mcav.media.mcv2.CompactRecord;
import me.brandonli.mcav.media.mcv2.Reconstruction;
import org.checkerframework.checker.nullness.qual.Nullable;

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

  /** The modes whose candidates read the source in YCoCg. */
  private static final int YCOCG_MODES =
    (0xF << MODE_RESIDUAL) |
    (1 << MODE_INTRA_Y4C1) |
    (1 << MODE_RESIDUAL_Y4C1) |
    (1 << MODE_INTRA_Y8C2) |
    (1 << MODE_RESIDUAL_Y8C2) |
    (1 << MODE_COMPACT);

  /** The modes whose candidates read the chroma of the source in YCoCg, whatever their classes. */
  private static final int CHROMA_MODES = YCOCG_MODES & ~(1 << MODE_COMPACT);

  /** The compact classes that fit luma alone: the DC and the luma-only 4x4 grid. */
  private static final int LUMA_CLASSES = (1 << CompactRecord.DC_Y) | (1 << CompactRecord.GRID4_N4_Y);

  /**
   * A vector no search produces, x and y both -32768 half pixels, far outside the largest motion range: the local
   * vectors of a block that has not predicted locally.
   */
  private static final int NO_VECTOR = 0x80008000;

  /** The modes whose records predict at the local vector, so they need the local motion search. */
  private static final int LOCAL_MODES =
    (1 << MODE_MOTION) | (0xF << MODE_RESIDUAL) | (1 << MODE_RESIDUAL_Y4C1) | (1 << MODE_RESIDUAL_Y8C2) | (1 << MODE_COMPACT);

  /** The smallest block whose motion is first searched at half resolution, where an 8-pixel block would be 4x4 pixels. */
  private static final int HALF_MOTION_SMALLEST = 16;

  /** The reduced-chroma intra modes, each followed by its residual mode. */
  private static final int[] REDUCED_MODES = { MODE_INTRA_Y4C1, MODE_INTRA_Y8C2 };

  /** The quantizers the reference tries for residual grids: 0 to 3. */
  private static final int RESIDUAL_QUANTIZERS = 4;

  /** Where the fitted chroma of the reduced-chroma modes starts in {@link #grid}: after the largest luma grid. */
  private static final int CHROMA_NODES = MAX_GRID * MAX_GRID;

  private static final int NIBBLE_MIN = -8;

  private static final int NIBBLE_MAX = 7;

  private static final int NIBBLE_BITS = 4;

  private static final int NIBBLE_MASK = 15;

  private static final int SMALL_GRID = 2;

  /** The luma nodes of the 4x4 compact grids, sixteen nibbles in eight bytes. */
  private static final int GRID4_NODES = 16;

  /** A compact record's values at most: a 4x4 luma grid and a chroma pair. */
  private static final int MAX_FIT = GRID4_NODES + CHROMA_PLANES;

  /** The seeds of a seeded motion search: the block's centre, just outside its four edges, and the enclosing block. */
  private static final int SEEDS = 6;

  /** Where a palette record's selector bits start: after its two colours. */
  private static final int SELECTORS_AT = PALETTE_COLORS * CHANNELS;

  private FrameJob job;

  private final int size;

  private final int count;

  private final int[] source;

  private final float[] ycocg;

  private final float[] target;

  /** The source channels as floats, for the intra grid fits, loaded once per block. */
  private final float[] rgb;

  /** Whether a candidate the search tries reads the chroma of the source in YCoCg: all but the luma-only compact classes do. */
  private boolean needsChroma;

  private final int[][] globalPrediction;

  private final int[][] localPrediction;

  private final int[] localVectors;

  private final int[] recon;

  /** The reconstruction of the best candidate of trial 0 so far. */
  private final int[] best;

  private boolean keepsBest;

  /** The shortcuts of a live search, {@link LiveSearch#shortcuts}. */
  private int shortcuts;

  private final int[] cells = new int[FastFits.CELL_SUMS];

  private final byte[] record = new byte[FrameJob.MAX_RECORD];

  private final byte[] palette = new byte[recordSize(MODE_PALETTE, ROOT_SIZE)];

  private final Reconstruction.Scratch scratch = new Reconstruction.Scratch();

  private final Reconstruction.Score measure = new Reconstruction.Score();

  private final float[] grid = new float[CHANNELS * MAX_GRID * MAX_GRID];

  private final float[] fit = new float[MAX_FIT];

  private final double[] fitScratch = new double[ROOT_SIZE * MAX_GRID];

  private final int[] colors = new int[PALETTE_COLORS * CHANNELS];

  private final byte[] selectors;

  private final float[] axis;

  private final float meanSquare;

  private final int[] seeds = new int[SEEDS];

  private final int[] halfSeeds = new int[SEEDS];

  /** The block at half resolution, for {@link LiveSearch#HALF_MOTION}. */
  private final int[] halfSource;

  private final float[] clusters = new float[PALETTE_COLORS * CHANNELS];

  private final long[] clusterSums = new long[FastFits.CLUSTER_SUMS];

  private int level;

  private int block;

  private double rate;

  private boolean skipped;

  private boolean hurried;

  private double share;

  private @Nullable BlockCoder root;

  private int x = -ROOT_SIZE;

  private int y = -ROOT_SIZE;

  private boolean clustered;

  private boolean rgbLoaded;

  private boolean celled;

  private boolean ycocgLoaded;

  private boolean motionCloser;

  private long skipDistortion;

  BlockCoder(final FrameJob job, final int size) {
    this.job = job;
    this.size = size;
    this.count = size * size;
    this.source = new int[this.count * CHANNELS];
    this.ycocg = new float[this.count * CHANNELS];
    this.target = new float[this.count * CHANNELS];
    this.rgb = new float[this.count * CHANNELS];
    // the reference search tries at most two global vectors
    this.globalPrediction = new int[2][this.count * CHANNELS];
    this.localPrediction = new int[2][this.count * CHANNELS];
    this.localVectors = new int[2];
    this.recon = new int[this.count * CHANNELS];
    this.best = new int[this.count * CHANNELS];
    this.selectors = new byte[this.count];
    this.halfSource = new int[(size / 2) * (size / 2) * CHANNELS];
    this.job = job;
    this.needsChroma = needsChroma(job);
    this.keepsBest = job.levelPicture(0) != null;
    this.shortcuts = shortcuts(job);
    this.axis = new float[size];
    double squares = 0;
    for (int i = 0; i < size; i++) {
      this.axis[i] = ((i + 0.5f) / size) * 2.0f - 1.0f;
      squares += this.axis[i] * this.axis[i];
    }
    this.meanSquare = (float) (squares / size);
  }

  /**
   * Makes the coder evaluate the blocks of another frame, keeping its scratch space.
   *
   * @param frame the frame's search state
   */
  void bind(final FrameJob frame) {
    this.job = frame;
    this.needsChroma = needsChroma(frame);
    this.keepsBest = frame.levelPicture(0) != null;
    this.shortcuts = shortcuts(frame);
  }

  /** The modes a frame's search tries: every one in the reference search. */
  private static int modes(final FrameJob frame) {
    final LiveSearch live = frame.settings().live();
    return live == null ? LiveSearch.ALL_MODES : frame.isKeyframe() ? live.keyModes() : live.modes();
  }

  private static boolean needsChroma(final FrameJob frame) {
    final LiveSearch live = frame.settings().live();
    final int modes = modes(frame);
    final int classes = live == null ? LiveSearch.ALL_CLASSES : live.compactClasses();
    return (modes & CHROMA_MODES) != 0 || (((modes >> MODE_COMPACT) & 1) != 0 && (classes & ~LUMA_CLASSES) != 0);
  }

  private static int shortcuts(final FrameJob frame) {
    final LiveSearch live = frame.settings().live();
    return live == null ? 0 : live.shortcuts();
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
    this.code(level, block, x, y, -1);
  }

  /**
   * Evaluates one block, seeding a live search's local motion with the vector the enclosing block found.
   *
   * @param level  the level, 0 for 32, 1 for 16, 2 for 8
   * @param block  the block index in raster order at that level
   * @param x      the block's left edge
   * @param y      the block's top edge
   * @param parent the enclosing block's local vector of the first global vector, or -1 at the root
   */
  void code(final int level, final int block, final int x, final int y, final int parent) {
    this.code(level, block, x, y, parent, 0);
  }

  /**
   * Evaluates one block of a live search, which may stop after SKIP and local motion when they already cost less than
   * the given share of the enclosing block's cost.
   *
   * @param level  the level, 0 for 32, 1 for 16, 2 for 8
   * @param block  the block index in raster order at that level
   * @param x      the block's left edge
   * @param y      the block's top edge
   * @param parent the enclosing block's local vector of the first global vector, or -1 at the root
   * @param share  the cost below which no dearer candidate is tried, or 0
   */
  void code(final int level, final int block, final int x, final int y, final int parent, final double share) {
    this.share = share;
    this.evaluate(level, block, x, y, parent);
    final byte[] picture = this.job.levelPicture(level);
    if (picture != null) {
      this.store(picture, x, y);
    }
  }

  /** Writes the best reconstruction of a live search's trial into the level's picture, cropped to the picture. */
  private void store(final byte[] picture, final int x, final int y) {
    final int width = this.job.width();
    final int right = Math.min(this.size, width - x);
    final int bottom = Math.min(this.size, this.job.height() - y);
    for (int py = 0; py < bottom; py++) {
      final int from = py * this.size * CHANNELS;
      final int to = ((y + py) * width + x) * CHANNELS;
      for (int i = 0; i < right * CHANNELS; i++) {
        picture[to + i] = (byte) this.best[from + i];
      }
    }
  }

  private void evaluate(final int level, final int block, final int x, final int y, final int parent) {
    this.clustered = false;
    this.rgbLoaded = false;
    this.celled = false;
    this.ycocgLoaded = false;
    // no local prediction yet: a smaller block never copies one this block did not make
    Arrays.fill(this.localVectors, NO_VECTOR);
    this.motionCloser = false;
    this.level = level;
    this.block = block;
    this.skipped = false;
    this.loadSource(x, y);
    final FrameJob j = this.job;
    final LiveSearch live = j.settings().live();
    final boolean temporal = !j.isKeyframe();
    if (temporal) {
      for (int v = 0; v < j.vectorCount(); v++) {
        this.predict(MotionSearch.pack(j.vectorX(v), j.vectorY(v)), this.globalPrediction[v]);
        // the first candidate of its trials, so always eligible; the call sets the rate for the score
        this.eligible(MODE_SKIP, 0, j.vectorMask(v));
        // the first candidate of its trials, whose costs are still infinite: the measure never stops it
        Reconstruction.predicted(this.globalPrediction[v], this.size, this.recon, this.measure);
        this.score(MODE_SKIP, 0, 0, j.vectorMask(v));
        this.skipDistortion = this.measure.distortion();
      }
      if (this.hurried || (live != null && j.cost(0, level)[block] <= live.skipThreshold() * j.settings().lambda())) {
        this.skipped = true;
        return;
      }
      if (live == null || (live.modes() & LOCAL_MODES) != 0) {
        for (int v = 0; v < j.vectorCount(); v++) {
          // a block below the live search's smallest searching size predicts with its parent's vector
          this.localVectors[v] = live != null && this.size < live.searchBlock() && parent >= 0
            ? parent
            : this.search(x, y, v, live, parent);
          this.predict(this.localVectors[v], this.localPrediction[v]);
        }
      } else {
        // no candidate uses a local vector: the global prediction stands in for it
        for (int v = 0; v < j.vectorCount(); v++) {
          this.localVectors[v] = MotionSearch.pack(j.vectorX(v), j.vectorY(v));
          System.arraycopy(this.globalPrediction[v], 0, this.localPrediction[v], 0, this.count * CHANNELS);
        }
      }
      for (int v = 0; v < j.vectorCount() && this.tries(live, MODE_MOTION); v++) {
        // at the global vector, a motion record reconstructs SKIP's picture at a higher rate, so it cannot win
        if (!this.isGlobal(v) && this.eligible(MODE_MOTION, 2, j.vectorMask(v))) {
          this.setMotionBytes(v);
          if (Reconstruction.predicted(this.localPrediction[v], this.size, this.recon, this.measure)) {
            this.score(MODE_MOTION, 0, 2, j.vectorMask(v));
            this.motionCloser = this.measure.distortion() < this.skipDistortion;
          }
        }
      }
    }
    if (this.hurried) {
      // a keyframe past its time budget: one solid colour, the cheapest leaf a keyframe has
      this.solid();
      this.skipped = true;
      return;
    }
    if (live != null && temporal && j.cost(0, level)[block] <= Math.max(live.goodThreshold() * j.settings().lambda(), this.share)) {
      // good enough: SKIP or local motion codes the block well, so nothing dearer is tried for it
      return;
    }
    final boolean closer = (this.shortcuts & LiveSearch.ONE_PREDICTION) != 0 && temporal && this.tries(live, MODE_COMPACT);
    if (closer) {
      // the likeliest winner on a moving block after local motion, measured before the dearer intra candidates so the
      // measure stops those sooner; a live search is not bound to the reference's order
      this.compact(0, this.motionCloser);
    }
    if (this.tries(live, MODE_SOLID)) {
      this.solid();
    }
    if (this.tries(live, MODE_PALETTE)) {
      this.palette();
    }
    for (int g = 1; g <= MAX_GRID; g *= 2) {
      final int k = Integer.numberOfTrailingZeros(g);
      if (g > 1 && this.tries(live, MODE_INTRA + k)) {
        this.intraGrid(g);
      }
      for (int v = 0; temporal && v < j.vectorCount() && this.tries(live, MODE_RESIDUAL + k); v++) {
        this.residualGrid(v, g);
      }
    }
    for (final int intra : REDUCED_MODES) {
      if (this.tries(live, intra)) {
        this.reducedIntra(intra);
      }
      for (int v = 0; temporal && v < j.vectorCount() && this.tries(live, intra + 1); v++) {
        this.reducedResidual(v, intra + 1);
      }
    }
    if (this.tries(live, MODE_PATTERN)) {
      this.pattern(false);
      this.pattern(true);
    }
    if (closer) {
      // one prediction only, measured above: the local one where local motion measured closer than SKIP
      return;
    }
    for (int v = 0; temporal && v < j.vectorCount() && this.tries(live, MODE_COMPACT); v++) {
      this.compact(v, false);
    }
    for (int v = 0; temporal && v < j.vectorCount() && this.tries(live, MODE_COMPACT); v++) {
      // at the global vector, the local records are the global ones again, which cannot beat themselves
      if (!this.isGlobal(v)) {
        this.compact(v, true);
      }
    }
  }

  /**
   * Predicts the block from the reference at a vector. A smaller block copies its pixels from the superblock's
   * prediction when the superblock predicted at the same vector: the prediction of a pixel depends only on its position
   * and the vector, so the copy is the same prediction.
   */
  private void predict(final int vector, final int[] out) {
    final BlockCoder parent = this.root;
    final int@Nullable[] from = parent == null ? null : parent.prediction(vector);
    if (parent != null && from != null) {
      final int n = this.size * CHANNELS;
      for (int py = 0; py < this.size; py++) {
        System.arraycopy(from, ((this.y - parent.y + py) * ROOT_SIZE + (this.x - parent.x)) * CHANNELS, out, py * n, n);
      }
      return;
    }
    final FrameJob j = this.job;
    Reconstruction.predict(
      j.reference(),
      j.width(),
      j.height(),
      this.x,
      this.y,
      this.size,
      MotionSearch.unpackX(vector),
      MotionSearch.unpackY(vector),
      out
    );
  }

  /** The superblock's prediction at a vector, when its last evaluation made one, or null. */
  private int@Nullable[] prediction(final int vector) {
    final FrameJob j = this.job;
    for (int v = 0; v < j.vectorCount(); v++) {
      if (vector == MotionSearch.pack(j.vectorX(v), j.vectorY(v))) {
        return this.globalPrediction[v];
      }
      if (vector == this.localVectors[v]) {
        return this.localPrediction[v];
      }
    }
    return null;
  }

  /** Whether the local vector found for global vector v is that vector itself. */
  private boolean isGlobal(final int v) {
    return this.localVectors[v] == MotionSearch.pack(this.job.vectorX(v), this.job.vectorY(v));
  }

  /** Whether the search tries a mode in this frame: the reference search tries every one. */
  private boolean tries(final @Nullable LiveSearch live, final int mode) {
    return live == null || live.tries(mode, this.job.isKeyframe(), this.size);
  }

  /** Whether the search tries a quantizer. */
  private boolean triesQuantizer(final @Nullable LiveSearch live, final int q) {
    return live == null || live.triesQuantizer(q, this.job.settings().lambda());
  }

  /**
   * The finest quantizer that holds a compact class's fitted values without clipping them, or 4 when none does: the
   * luma nibbles of the 4x4 grid classes are -8 to 7 steps, every other value -128 to 127.
   *
   * @param kind   the compact class
   * @param fit    the fitted values, the luma grid's first
   * @param values how many there are
   * @return the quantizer, 0 to 4
   */
  static int neededQuantizer(final int kind, final float[] fit, final int values) {
    final boolean nibbles = kind == CompactRecord.GRID4_N4_YC || kind == CompactRecord.GRID4_N4_Y;
    for (int q = 0; q < LiveSearch.COARSEST_QUANTIZER; q++) {
      final int step = 1 << q;
      boolean fits = true;
      for (int i = 0; i < values && fits; i++) {
        final int low = nibbles && i < GRID4_NODES ? NIBBLE_MIN : Byte.MIN_VALUE;
        final int high = nibbles && i < GRID4_NODES ? NIBBLE_MAX : Byte.MAX_VALUE;
        final float scaled = (float) Math.floor(fit[i] / step + 0.5f);
        fits = scaled >= low && scaled <= high;
      }
      if (fits) {
        return q;
      }
    }
    return LiveSearch.COARSEST_QUANTIZER;
  }

  /** Whether the search tries a compact class. */
  private static boolean triesClass(final @Nullable LiveSearch live, final int kind) {
    return live == null || ((live.compactClasses() >> kind) & 1) != 0;
  }

  /**
   * Whether the last {@link #code} ended without a search - at an early SKIP, or at the cheapest choice of a hurried
   * block - so nothing inside the block needs one.
   *
   * @return true after an early SKIP or a hurried block
   */
  boolean isSkipped() {
    return this.skipped;
  }

  /**
   * Makes the blocks this coder evaluates next take the cheapest choice without a search, for a live frame that ran
   * past its time budget: SKIP with the frame's vector in a P frame, one solid colour in a keyframe.
   *
   * @param hurried whether to take the cheapest choice
   */
  void setHurried(final boolean hurried) {
    this.hurried = hurried;
  }

  /**
   * Gets the local vector the last {@link #code} found for the first global vector.
   *
   * @return the vector as {@code x << 16 | (y & 0xFFFF)}, in half pixels
   */
  int localVector() {
    return this.localVectors[0];
  }

  private int search(final int x, final int y, final int v, final @Nullable LiveSearch live, final int parent) {
    final FrameJob j = this.job;
    if (live == null || !live.seededMotion()) {
      return MotionSearch.search(
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
    }
    // the previous frame's motion at the block's centre and just outside its four edges, and the enclosing block's
    final int half = this.size / 2;
    final int[] seeds = this.seeds;
    seeds[0] = j.previousMotion(x + half, y + half);
    seeds[1] = j.previousMotion(x - 1, y + half);
    seeds[2] = j.previousMotion(x + this.size, y + half);
    seeds[3] = j.previousMotion(x + half, y - 1);
    seeds[4] = j.previousMotion(x + half, y + this.size);
    seeds[5] = parent < 0 ? seeds[0] : parent;
    if ((this.shortcuts & LiveSearch.HALF_MOTION) != 0 && this.size >= HALF_MOTION_SMALLEST) {
      Arrays.fill(seeds, this.halfResolutionMotion(x, y, v));
    }
    return MotionSearch.seeded(
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
      j.settings().halfPixel(),
      seeds
    );
  }

  /**
   * Searches the block's local motion on the pictures at half resolution, from its seeds halved, and returns the vector
   * found there at full resolution, for the full search to refine: at half resolution a step spans two pixels and a
   * half-pixel step is a whole pixel of the full pictures.
   */
  private int halfResolutionMotion(final int x, final int y, final int v) {
    final FrameJob j = this.job;
    halve(this.source, this.size, this.halfSource);
    for (int k = 0; k < this.seeds.length; k++) {
      this.halfSeeds[k] = MotionSearch.pack(MotionSearch.unpackX(this.seeds[k]) / 2, MotionSearch.unpackY(this.seeds[k]) / 2);
    }
    final int coarse = MotionSearch.seeded(
      j.halfReference(),
      (j.width() + 1) / 2,
      (j.height() + 1) / 2,
      this.halfSource,
      x / 2,
      y / 2,
      this.size / 2,
      j.vectorX(v) / 2,
      j.vectorY(v) / 2,
      j.settings().motionRange() / 2,
      true,
      this.halfSeeds
    );
    return MotionSearch.pack(MotionSearch.unpackX(coarse) * 2, MotionSearch.unpackY(coarse) * 2);
  }

  /** Halves a square block of channels: each pixel of the result is the rounded mean of a 2x2 square. */
  static void halve(final int[] block, final int size, final int[] out) {
    final int half = size / 2;
    final int row = size * CHANNELS;
    for (int py = 0; py < half; py++) {
      for (int px = 0; px < half; px++) {
        final int at = (2 * py * size + 2 * px) * CHANNELS;
        for (int c = 0; c < CHANNELS; c++) {
          final int sum = block[at + c] + block[at + CHANNELS + c] + block[at + row + c] + block[at + row + CHANNELS + c];
          out[(py * half + px) * CHANNELS + c] = (sum + 2) >> 2;
        }
      }
    }
  }

  /**
   * Makes a coder of a smaller level load its blocks from the superblock's coder, which holds every pixel of them already
   * edge-padded, instead of from the picture.
   *
   * @param coder the coder of 32-pixel blocks of the same worker
   */
  void loadFrom(final BlockCoder coder) {
    this.root = coder;
  }

  private void loadSource(final int x, final int y) {
    final BlockCoder parent = this.root;
    // a smaller block is always coded right after the superblock it lies in, by the same worker
    if (parent != null) {
      final int n = this.size * CHANNELS;
      for (int py = 0; py < this.size; py++) {
        final int from = ((y - parent.y + py) * ROOT_SIZE + (x - parent.x)) * CHANNELS;
        System.arraycopy(parent.source, from, this.source, py * n, n);
      }
      this.x = x;
      this.y = y;
      return;
    }
    this.x = x;
    this.y = y;
    final FrameJob j = this.job;
    final byte[] image = j.source();
    for (int py = 0; py < this.size; py++) {
      final int sy = Math.min(y + py, j.height() - 1);
      for (int px = 0; px < this.size; px++) {
        final int sx = Math.min(x + px, j.width() - 1);
        final int from = (sy * j.width() + sx) * CHANNELS;
        final int to = (py * this.size + px) * CHANNELS;
        final int r = image[from] & 0xFF;
        final int g = image[from + 1] & 0xFF;
        final int b = image[from + 2] & 0xFF;
        this.source[to] = r;
        this.source[to + 1] = g;
        this.source[to + 2] = b;
      }
    }
  }

  /**
   * Sixteen times six times the weighted YCoCg squared error of a reconstruction, an exact integer.
   *
   * @param s the source channels
   * @param r the reconstructed channels, as many
   * @return the distortion
   */
  static long distortion(final int[] s, final int[] r) {
    long sum = 0;
    for (int i = 0; i < s.length; i += CHANNELS) {
      sum += Reconstruction.pixelError(s[i] - r[i], s[i + 1] - r[i + 1], s[i + 2] - r[i + 2]);
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
    this.rate = j.settings().lambda() * (length * Byte.SIZE + bits);
    // the candidate must be cheaper than the dearest trial it belongs to; the kernel stops measuring once it cannot be
    double dearest = Double.NEGATIVE_INFINITY;
    for (int t = 0; t < j.trialCount(); t++) {
      if (((mask >> t) & 1) != 0) {
        dearest = Math.max(dearest, j.cost(t, this.level)[this.block]);
      }
    }
    this.measure.start(this.source, this.rate, dearest);
    return dearest > this.rate;
  }

  /** Scores the reconstruction in {@link #recon} and records it for every trial it improves. */
  private void score(final int mode, final int q, final int length, final int mask) {
    final FrameJob j = this.job;
    final long d16 = this.measure.distortion();
    final double cost = d16 / Reconstruction.DISTORTION_SCALE + this.rate;
    for (int t = 0; t < j.trialCount(); t++) {
      if (((mask >> t) & 1) != 0 && cost < j.cost(t, this.level)[this.block]) {
        if (t == 0 && this.keepsBest) {
          System.arraycopy(this.recon, 0, this.best, 0, this.recon.length);
        }
        j.set(t, this.level, this.block, cost, mode, q, this.record, length, d16);
      }
    }
  }

  /** The block's clustered palette endpoints, computed once per block for the palette and both pattern precisions. */
  private float[] endpoints() {
    if (!this.clustered) {
      if ((this.shortcuts & LiveSearch.FAST_PALETTES) != 0) {
        FastFits.cluster(this.source, this.size, this.clusterSums, this.clusters);
      } else {
        PaletteFit.cluster(this.source, this.count, this.clusters);
      }
      this.clustered = true;
    }
    return this.clusters;
  }

  private void setMotionBytes(final int v) {
    final int vector = this.localVectors[v];
    this.record[0] = (byte) (MotionSearch.unpackX(vector) - this.job.vectorX(v));
    this.record[1] = (byte) (MotionSearch.unpackY(vector) - this.job.vectorY(v));
  }

  private void solid() {
    final int length = recordSize(MODE_SOLID, this.size);
    if (!this.eligible(MODE_SOLID, length, this.job.allTrials())) {
      return;
    }
    long r = 0;
    long g = 0;
    long b = 0;
    for (int i = 0; i < this.count; i++) {
      r += this.source[i * CHANNELS];
      g += this.source[i * CHANNELS + 1];
      b += this.source[i * CHANNELS + 2];
    }
    final int red = roundMean(r, this.count);
    final int green = roundMean(g, this.count);
    final int blue = roundMean(b, this.count);
    this.record[0] = (byte) red;
    this.record[1] = (byte) green;
    this.record[2] = (byte) blue;
    if (Reconstruction.solid((red << 16) | (green << 8) | blue, this.size, this.recon, this.measure)) {
      this.score(MODE_SOLID, 0, length, this.job.allTrials());
    }
  }

  /** The reference's {@code rgb8} of a float64 mean of integers. */
  private static int roundMean(final long sum, final int count) {
    final double mean = (double) sum / count;
    return (int) Math.floor(Math.min(Math.max(mean, 0.0), MAX_CHANNEL) + 0.5);
  }

  private void writePalette(final byte[] target) {
    for (int i = 0; i < SELECTORS_AT; i++) {
      target[i] = (byte) this.colors[i];
    }
    for (int i = 0; i < this.count / Byte.SIZE; i++) {
      target[SELECTORS_AT + i] = 0;
    }
    for (int i = 0; i < this.count; i++) {
      target[SELECTORS_AT + i / Byte.SIZE] |= (byte) (this.selectors[i] << (i % Byte.SIZE));
    }
  }

  private void palette() {
    final int length = recordSize(MODE_PALETTE, this.size);
    if (!this.eligible(MODE_PALETTE, length, this.job.allTrials())) {
      return;
    }
    PaletteFit.finish(this.source, this.count, this.endpoints(), false, this.colors, this.selectors);
    this.writePalette(this.record);
    if (Reconstruction.palette(this.record, 0, this.size, this.recon, this.measure)) {
      this.score(MODE_PALETTE, 0, length, this.job.allTrials());
    }
  }

  private void pattern(final boolean coarse) {
    final int length = patternSize(this.size, false, false);
    final int mask = this.job.coarseMask(coarse);
    if (!this.eligible(MODE_PATTERN, length, mask)) {
      return;
    }
    if (!PaletteFit.finishPattern(this.source, this.size, this.endpoints(), coarse, this.colors, this.selectors)) {
      return;
    }
    this.writePalette(this.palette);
    // the selectors repeat along an axis, so they make a pattern record
    final byte[] pattern = Preconditions.checkNotNull(TreeReader.patternRecord(this.palette, this.size));
    System.arraycopy(pattern, 0, this.record, 0, pattern.length);
    if (Reconstruction.palette(this.palette, 0, this.size, this.recon, this.measure)) {
      this.score(MODE_PATTERN, 0, length, mask);
    }
  }

  private static int quantize(final float value, final int step, final int low, final int high) {
    final float scaled = (float) Math.floor(value / step + 0.5f);
    return (int) Math.min(Math.max(scaled, low), high);
  }

  private void intraGrid(final int g) {
    final int mode = MODE_INTRA + Integer.numberOfTrailingZeros(g);
    final int length = recordSize(mode, this.size);
    if (!this.eligible(mode, length, this.job.allTrials())) {
      return;
    }
    if ((this.shortcuts & LiveSearch.FAST_GRIDS) != 0 && g <= FastFits.CELL_GRID) {
      if (!this.celled) {
        FastFits.cellSums(this.source, this.size, this.cells);
        this.celled = true;
      }
      FastFits.grid(this.cells, this.size, g, this.grid);
    } else {
      if (!this.rgbLoaded) {
        for (int i = 0; i < this.count * CHANNELS; i++) {
          this.rgb[i] = this.source[i];
        }
        this.rgbLoaded = true;
      }
      for (int c = 0; c < CHANNELS; c++) {
        Fits.fit(this.rgb, c, CHANNELS, this.size, g, this.fitScratch, this.grid, c, CHANNELS);
      }
    }
    for (int i = 0; i < length; i++) {
      this.record[i] = (byte) Reconstruction.rgb8(this.grid[i]);
    }
    if (Reconstruction.intraGrid(this.record, 0, g, this.size, this.scratch, this.recon, this.measure)) {
      this.score(mode, 0, length, this.job.allTrials());
    }
  }

  /**
   * The YCoCg of the source, converted on the block's first use: most blocks of a live search end before any candidate
   * that needs it. The chroma is converted only when a tried candidate reads it.
   */
  private float[] ycocg() {
    if (!this.ycocgLoaded) {
      final int[] s = this.source;
      for (int i = 0; i < this.count * CHANNELS; i += CHANNELS) {
        final int r = s[i];
        final int g = s[i + 1];
        final int b = s[i + 2];
        this.ycocg[i] = (r + 2 * g + b) * 0.25f;
        if (this.needsChroma) {
          this.ycocg[i + 1] = (r - b) * 0.5f;
          this.ycocg[i + 2] = (-r + 2 * g - b) * 0.25f;
        }
      }
      this.ycocgLoaded = true;
    }
    return this.ycocg;
  }

  /** The YCoCg residual of the source against a prediction, into {@link #target}. */
  private void residualTarget(final int[] prediction) {
    final float[] source = this.ycocg();
    for (int i = 0; i < this.count * CHANNELS; i += CHANNELS) {
      final float r = prediction[i] * 0.25f;
      final float g = prediction[i + 1] * 0.25f;
      final float b = prediction[i + 2] * 0.25f;
      this.target[i] = source[i] - (r + 2 * g + b) * 0.25f;
      if (this.needsChroma) {
        this.target[i + 1] = source[i + 1] - (r - b) * 0.5f;
        this.target[i + 2] = source[i + 2] - (-r + 2 * g - b) * 0.25f;
      }
    }
  }

  private void residualGrid(final int v, final int g) {
    final int mode = MODE_RESIDUAL + Integer.numberOfTrailingZeros(g);
    final int length = recordSize(mode, this.size);
    final int mask = this.job.vectorMask(v);
    if (!this.eligible(mode, length, mask)) {
      return;
    }
    this.residualTarget(this.localPrediction[v]);
    for (int c = 0; c < CHANNELS; c++) {
      Fits.fit(this.target, c, CHANNELS, this.size, g, this.fitScratch, this.grid, c, CHANNELS);
    }
    for (int q = 0; q < RESIDUAL_QUANTIZERS; q++) {
      if (!this.eligible(mode, length, mask)) {
        return;
      }
      if (!this.triesQuantizer(this.job.settings().live(), q)) {
        continue;
      }
      this.setMotionBytes(v);
      for (int i = 0; i < CHANNELS * g * g; i++) {
        this.record[MOTION_BYTES + i] = (byte) quantize(this.grid[i], 1 << q, Byte.MIN_VALUE, Byte.MAX_VALUE);
      }
      if (
        Reconstruction.residualGrid(
          this.localPrediction[v],
          this.record,
          MOTION_BYTES,
          g,
          q,
          this.size,
          this.scratch,
          this.recon,
          this.measure
        )
      ) {
        this.score(mode, q, length, mask);
      }
    }
  }

  /** Fits the luma of {@link #target} into the grid's start and its interleaved chroma from {@link #CHROMA_NODES}. */
  private void fitReduced(final int luma, final int chroma) {
    Fits.fit(this.target, 0, CHANNELS, this.size, luma, this.fitScratch, this.grid, 0, 1);
    Fits.fit(this.target, 1, CHANNELS, this.size, chroma, this.fitScratch, this.grid, CHROMA_NODES, CHROMA_PLANES);
    Fits.fit(this.target, 2, CHANNELS, this.size, chroma, this.fitScratch, this.grid, CHROMA_NODES + 1, CHROMA_PLANES);
  }

  private void reducedIntra(final int mode) {
    final int luma = lumaGrid(mode);
    final int chroma = chromaGrid(mode);
    final int length = recordSize(mode, this.size);
    if (!this.eligible(mode, length, this.job.allTrials())) {
      return;
    }
    System.arraycopy(this.ycocg(), 0, this.target, 0, this.count * CHANNELS);
    this.fitReduced(luma, chroma);
    for (int i = 0; i < luma * luma; i++) {
      this.record[i] = (byte) quantize(this.grid[i], 1, 0, MAX_CHANNEL);
    }
    for (int i = 0; i < CHROMA_PLANES * chroma * chroma; i++) {
      this.record[luma * luma + i] = (byte) quantize(this.grid[CHROMA_NODES + i], 1, Byte.MIN_VALUE, Byte.MAX_VALUE);
    }
    if (Reconstruction.reduced(null, this.record, 0, luma, chroma, 0, this.size, this.scratch, this.recon, this.measure)) {
      this.score(mode, 0, length, this.job.allTrials());
    }
  }

  private void reducedResidual(final int v, final int mode) {
    final int luma = lumaGrid(mode);
    final int chroma = chromaGrid(mode);
    final int length = recordSize(mode, this.size);
    final int mask = this.job.vectorMask(v);
    if (!this.eligible(mode, length, mask)) {
      return;
    }
    this.residualTarget(this.localPrediction[v]);
    this.fitReduced(luma, chroma);
    for (int q = 0; q < RESIDUAL_QUANTIZERS; q++) {
      if (!this.eligible(mode, length, mask)) {
        return;
      }
      if (!this.triesQuantizer(this.job.settings().live(), q)) {
        continue;
      }
      this.setMotionBytes(v);
      for (int i = 0; i < luma * luma; i++) {
        this.record[MOTION_BYTES + i] = (byte) quantize(this.grid[i], 1 << q, Byte.MIN_VALUE, Byte.MAX_VALUE);
      }
      for (int i = 0; i < CHROMA_PLANES * chroma * chroma; i++) {
        final int at = MOTION_BYTES + luma * luma + i;
        this.record[at] = (byte) quantize(this.grid[CHROMA_NODES + i], 1 << q, Byte.MIN_VALUE, Byte.MAX_VALUE);
      }
      final int[] prediction = this.localPrediction[v];
      if (
        Reconstruction.reduced(prediction, this.record, MOTION_BYTES, luma, chroma, q, this.size, this.scratch, this.recon, this.measure)
      ) {
        this.score(mode, q, length, mask);
      }
    }
  }

  private void compact(final int v, final boolean local) {
    final int[] prediction = local ? this.localPrediction[v] : this.globalPrediction[v];
    final int dx = local ? MotionSearch.unpackX(this.localVectors[v]) - this.job.vectorX(v) : 0;
    final int dy = local ? MotionSearch.unpackY(this.localVectors[v]) - this.job.vectorY(v) : 0;
    final int form = compactForm(dx, dy);
    final int mask = this.job.vectorMask(v);
    boolean targeted = false;
    final LiveSearch live = this.job.settings().live();
    for (final int kind : COMPACT_CLASSES) {
      final int length = 1 + form + CompactRecord.bodyBytes(kind);
      if (!triesClass(live, kind) || !this.eligible(MODE_COMPACT, length, mask)) {
        continue;
      }
      final int values;
      if ((this.shortcuts & LiveSearch.FAST_COMPACT) != 0 && kind == CompactRecord.GRID4_N4_Y) {
        FastFits.lumaResidual(this.source, prediction, this.size, this.cells, this.fit);
        values = GRID4_NODES;
      } else {
        if (!targeted) {
          this.residualTarget(prediction);
          targeted = true;
        }
        values = this.compactFit(kind);
      }
      final int body = 1 + form;
      final boolean fitted = (this.shortcuts & LiveSearch.FIT_PAIR) != 0;
      final int needed = fitted ? neededQuantizer(kind, this.fit, values) : 0;
      for (int q = 0; q <= LiveSearch.COARSEST_QUANTIZER; q++) {
        if (!this.eligible(MODE_COMPACT, length, mask)) {
          break;
        }
        if (!this.triesQuantizer(live, q) || (fitted && (q < needed - 1 || q > needed))) {
          continue;
        }
        this.record[0] = (byte) (kind | (form << NIBBLE_BITS));
        if (form == CompactRecord.FORM_NIBBLES) {
          this.record[1] = (byte) ((dx & NIBBLE_MASK) | ((dy & NIBBLE_MASK) << NIBBLE_BITS));
        } else if (form == CompactRecord.FORM_BYTES) {
          this.record[1] = (byte) dx;
          this.record[2] = (byte) dy;
        }
        this.compactBody(kind, values, q, body);
        if (Reconstruction.compact(prediction, this.record, body, kind, q, this.size, this.scratch, this.recon, this.measure)) {
          this.score(MODE_COMPACT, q, length, mask);
        }
      }
    }
  }

  /** The motion form of a compact record whose local vector differs from the global one by dx, dy half pixels. */
  private static int compactForm(final int dx, final int dy) {
    if (dx == 0 && dy == 0) {
      return CompactRecord.FORM_GLOBAL;
    }
    final boolean nibbles = dx >= NIBBLE_MIN && dx <= NIBBLE_MAX && dy >= NIBBLE_MIN && dy <= NIBBLE_MAX;
    return nibbles ? CompactRecord.FORM_NIBBLES : CompactRecord.FORM_BYTES;
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
          final float luma = t[(py * this.size + px) * CHANNELS];
          sx += luma * this.axis[px];
          sy += luma * this.axis[py];
        }
      }
      this.fit[0] = this.mean(0);
      this.fit[1] = (float) (sx / this.count) / this.meanSquare;
      this.fit[2] = (float) (sy / this.count) / this.meanSquare;
      this.fit[3] = this.mean(1);
      this.fit[4] = this.mean(2);
      return CompactRecord.bodyBytes(CompactRecord.LOW2);
    }
    final int g = kind == CompactRecord.GRID2_YC ? SMALL_GRID : FastFits.CELL_GRID;
    Fits.fit(t, 0, CHANNELS, this.size, g, this.fitScratch, this.fit, 0, 1);
    if (kind == CompactRecord.GRID4_N4_Y) {
      // luma alone: no chroma offsets to fit
      return g * g;
    }
    this.fit[g * g] = this.mean(1);
    this.fit[g * g + 1] = this.mean(2);
    return g * g + 2;
  }

  private float mean(final int channel) {
    double sum = 0;
    for (int i = channel; i < this.target.length; i += CHANNELS) {
      sum += this.target[i];
    }
    return (float) (sum / this.count);
  }

  private void compactBody(final int kind, final int values, final int q, final int body) {
    final int step = 1 << q;
    if (kind == CompactRecord.GRID4_N4_YC || kind == CompactRecord.GRID4_N4_Y) {
      final int nibbleBytes = GRID4_NODES / 2;
      for (int i = 0; i < nibbleBytes; i++) {
        final int low = quantize(this.fit[2 * i], step, NIBBLE_MIN, NIBBLE_MAX) & NIBBLE_MASK;
        final int high = quantize(this.fit[2 * i + 1], step, NIBBLE_MIN, NIBBLE_MAX) & NIBBLE_MASK;
        this.record[body + i] = (byte) (low | (high << NIBBLE_BITS));
      }
      if (kind == CompactRecord.GRID4_N4_YC) {
        this.record[body + nibbleBytes] = (byte) quantize(this.fit[GRID4_NODES], step, Byte.MIN_VALUE, Byte.MAX_VALUE);
        this.record[body + nibbleBytes + 1] = (byte) quantize(this.fit[GRID4_NODES + 1], step, Byte.MIN_VALUE, Byte.MAX_VALUE);
      }
      return;
    }
    for (int i = 0; i < values; i++) {
      this.record[body + i] = (byte) quantize(this.fit[i], step, Byte.MIN_VALUE, Byte.MAX_VALUE);
    }
  }
}
