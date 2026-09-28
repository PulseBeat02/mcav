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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHANNELS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHROMA_PLANES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MAX_CHANNEL;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MAX_GRID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_COMPACT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_INTRA;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_INTRA_Y4C1;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_INTRA_Y8C2;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_PALETTE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_PATTERN;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_RESIDUAL;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_RESIDUAL_Y4C1;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_RESIDUAL_Y8C2;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SKIP;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SOLID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MOTION_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.PALETTE_COLORS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ROOT_SIZE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.chromaGrid;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.lumaGrid;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.patternSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.recordSize;

import com.google.common.base.Preconditions;
import java.util.Arrays;
import me.brandonli.mcav.bukkit.media.mcv2.CompactRecord;
import me.brandonli.mcav.bukkit.media.mcv2.Reconstruction;
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

  /** The pixel kernels this coder reconstructs, measures, fits and searches with. */
  private final Kernels kernels;

  private final byte[] record = new byte[FrameJob.MAX_RECORD];

  private final byte[] palette = new byte[recordSize(MODE_PALETTE, ROOT_SIZE)];

  private final float[] grid = new float[CHANNELS * MAX_GRID * MAX_GRID];

  private final float[] fit = new float[MAX_FIT];

  private final int[] colors = new int[PALETTE_COLORS * CHANNELS];

  private final byte[] selectors;

  private final float[] axis;

  private final float meanSquare;

  private final int[] seeds = new int[SEEDS];

  private final int[] halfSeeds = new int[SEEDS];

  /** The block at half resolution, for {@link LiveSearch#HALF_MOTION}. */
  private final int[] halfSource;

  /** The block at a quarter of the resolution, for {@link LiveSearch#QUARTER_MOTION}. */
  private final int[] quarterSource;

  private final int[] quarterSeeds = new int[SEEDS];

  private final float[] clusters = new float[PALETTE_COLORS * CHANNELS];

  private int level;

  private int block;

  private double rate;

  private boolean skipped;

  private boolean hurried;

  private double share;

  private @Nullable BlockCoder root;

  private int left = -ROOT_SIZE;

  private int top = -ROOT_SIZE;

  private boolean clustered;

  private boolean rgbLoaded;

  private boolean celled;

  private boolean ycocgLoaded;

  /** The channel means of {@link #target}, valid while {@link #meansLoaded}. */
  private final float[] means = new float[CHANNELS];

  private boolean meansLoaded;

  /**
   * The 4x4 luma grid fitted to {@link #target}, valid while {@link #gridLoaded}: both compact classes of a 4x4 grid fit
   * the same values to the same target, so the second takes the first's.
   */
  private final float[] lumaGrid = new float[GRID4_NODES];

  private boolean gridLoaded;

  private boolean motionCloser;

  private long skipDistortion;

  BlockCoder(final FrameJob job, final int size) {
    this(job, size, new JavaKernels());
  }

  /**
   * Makes a coder of one block size.
   *
   * @param job     the frame's search state
   * @param size    the block size
   * @param kernels the pixel kernels the coder uses, its own
   */
  BlockCoder(final FrameJob job, final int size, final Kernels kernels) {
    this.kernels = kernels;
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
    this.quarterSource = new int[(size / 4) * (size / 4) * CHANNELS];
    this.job = job;
    this.needsChroma = needsChroma(job);
    this.keepsBest = job.levelPicture(0) != null;
    this.shortcuts = shortcuts(job);
    this.axis = new float[size];
    double squares = 0;
    for (int position = 0; position < size; position++) {
      this.axis[position] = ((position + 0.5f) / size) * 2.0f - 1.0f;
      squares += this.axis[position] * this.axis[position];
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

  /** Lets the coder's kernels go of the frame it evaluated, which an idle coder must not keep alive. */
  void release() {
    this.kernels.forgetArrays();
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
   * @param left  the block's left edge
   * @param top   the block's top edge
   */
  void code(final int level, final int block, final int left, final int top) {
    this.code(level, block, left, top, -1);
  }

  /**
   * Evaluates one block, seeding a live search's local motion with the vector the enclosing block found.
   *
   * @param level  the level, 0 for 32, 1 for 16, 2 for 8
   * @param block  the block index in raster order at that level
   * @param left   the block's left edge
   * @param top    the block's top edge
   * @param parent the enclosing block's local vector of the first global vector, or -1 at the root
   */
  void code(final int level, final int block, final int left, final int top, final int parent) {
    this.code(level, block, left, top, parent, 0);
  }

  /**
   * Evaluates one block of a live search, which may stop after SKIP and local motion when they already cost less than
   * the given share of the enclosing block's cost.
   *
   * @param level  the level, 0 for 32, 1 for 16, 2 for 8
   * @param block  the block index in raster order at that level
   * @param left   the block's left edge
   * @param top    the block's top edge
   * @param parent the enclosing block's local vector of the first global vector, or -1 at the root
   * @param share  the cost below which no dearer candidate is tried, or 0
   */
  void code(final int level, final int block, final int left, final int top, final int parent, final double share) {
    this.share = share;
    this.evaluate(level, block, left, top, parent);
    final byte[] picture = this.job.levelPicture(level);
    if (picture != null) {
      this.store(picture, left, top);
    }
  }

  /** Writes the best reconstruction of a live search's trial into the level's picture, cropped to the picture. */
  private void store(final byte[] picture, final int left, final int top) {
    final int width = this.job.width();
    final int right = Math.min(this.size, width - left);
    final int bottom = Math.min(this.size, this.job.height() - top);
    for (int row = 0; row < bottom; row++) {
      final int from = row * this.size * CHANNELS;
      final int to = ((top + row) * width + left) * CHANNELS;
      for (int offset = 0; offset < right * CHANNELS; offset++) {
        picture[to + offset] = (byte) this.best[from + offset];
      }
    }
  }

  private void evaluate(final int level, final int block, final int left, final int top, final int parent) {
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
    this.loadSource(left, top);
    final FrameJob frame = this.job;
    final LiveSearch live = frame.settings().live();
    final boolean temporal = !frame.isKeyframe();
    if (temporal) {
      for (int vectorIndex = 0; vectorIndex < frame.vectorCount(); vectorIndex++) {
        this.predict(MotionSearch.pack(frame.vectorX(vectorIndex), frame.vectorY(vectorIndex)), this.globalPrediction[vectorIndex]);
        // the first candidate of its trials, so always eligible; the call sets the rate for the score
        this.eligible(MODE_SKIP, 0, frame.vectorMask(vectorIndex));
        // the first candidate of its trials, whose costs are still infinite: the measure never stops it
        this.kernels.predicted(this.globalPrediction[vectorIndex], this.size, this.recon);
        this.score(MODE_SKIP, 0, 0, frame.vectorMask(vectorIndex));
        this.skipDistortion = this.kernels.distortion();
      }
      if (this.hurried || (live != null && frame.cost(0, level)[block] <= live.skipThreshold() * frame.settings().lambda())) {
        this.skipped = true;
        return;
      }
      if (live == null || (live.modes() & LOCAL_MODES) != 0) {
        for (int vectorIndex = 0; vectorIndex < frame.vectorCount(); vectorIndex++) {
          // a block below the live search's smallest searching size predicts with its parent's vector
          this.localVectors[vectorIndex] =
            live != null && this.size < live.searchBlock() && parent >= 0 ? parent : this.search(left, top, vectorIndex, live, parent);
          this.predict(this.localVectors[vectorIndex], this.localPrediction[vectorIndex]);
        }
      } else {
        // no candidate uses a local vector: the global prediction stands in for it
        for (int vectorIndex = 0; vectorIndex < frame.vectorCount(); vectorIndex++) {
          this.localVectors[vectorIndex] = MotionSearch.pack(frame.vectorX(vectorIndex), frame.vectorY(vectorIndex));
          System.arraycopy(this.globalPrediction[vectorIndex], 0, this.localPrediction[vectorIndex], 0, this.count * CHANNELS);
        }
      }
      for (int vectorIndex = 0; vectorIndex < frame.vectorCount() && this.tries(live, MODE_MOTION); vectorIndex++) {
        // at the global vector, a motion record reconstructs SKIP's picture at a higher rate, so it cannot win
        if (!this.isGlobal(vectorIndex) && this.eligible(MODE_MOTION, 2, frame.vectorMask(vectorIndex))) {
          this.setMotionBytes(vectorIndex);
          if (this.kernels.predicted(this.localPrediction[vectorIndex], this.size, this.recon)) {
            this.score(MODE_MOTION, 0, 2, frame.vectorMask(vectorIndex));
            this.motionCloser = this.kernels.distortion() < this.skipDistortion;
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
    if (live != null && temporal && frame.cost(0, level)[block] <= Math.max(live.goodThreshold() * frame.settings().lambda(), this.share)) {
      // good enough: SKIP or local motion codes the block well, so nothing dearer is tried for it
      return;
    }
    if (
      live != null &&
      temporal &&
      this.size == ROOT_SIZE &&
      live.splitAbove() > 0 &&
      frame.cost(0, level)[block] > live.splitAbove() * frame.settings().lambda()
    ) {
      // far from coded by SKIP or local motion: the superblock is split, and its quarters are searched instead
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
    for (int gridSize = 1; gridSize <= MAX_GRID; gridSize *= 2) {
      final int gridExponent = Integer.numberOfTrailingZeros(gridSize);
      if (gridSize > 1 && this.tries(live, MODE_INTRA + gridExponent)) {
        this.intraGrid(gridSize);
      }
      for (
        int vectorIndex = 0;
        temporal && vectorIndex < frame.vectorCount() && this.tries(live, MODE_RESIDUAL + gridExponent);
        vectorIndex++
      ) {
        this.residualGrid(vectorIndex, gridSize);
      }
    }
    for (final int intra : REDUCED_MODES) {
      if (this.tries(live, intra)) {
        this.reducedIntra(intra);
      }
      for (int vectorIndex = 0; temporal && vectorIndex < frame.vectorCount() && this.tries(live, intra + 1); vectorIndex++) {
        this.reducedResidual(vectorIndex, intra + 1);
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
    for (int vectorIndex = 0; temporal && vectorIndex < frame.vectorCount() && this.tries(live, MODE_COMPACT); vectorIndex++) {
      this.compact(vectorIndex, false);
    }
    for (int vectorIndex = 0; temporal && vectorIndex < frame.vectorCount() && this.tries(live, MODE_COMPACT); vectorIndex++) {
      // at the global vector, the local records are the global ones again, which cannot beat themselves
      if (!this.isGlobal(vectorIndex)) {
        this.compact(vectorIndex, true);
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
    final int @Nullable [] from = parent == null ? null : parent.prediction(vector);
    if (parent != null && from != null) {
      final int rowLength = this.size * CHANNELS;
      for (int row = 0; row < this.size; row++) {
        System.arraycopy(
          from,
          ((this.top - parent.top + row) * ROOT_SIZE + (this.left - parent.left)) * CHANNELS,
          out,
          row * rowLength,
          rowLength
        );
      }
      return;
    }
    final FrameJob frame = this.job;
    this.kernels.predict(
      frame.reference(),
      frame.width(),
      frame.height(),
      this.left,
      this.top,
      this.size,
      MotionSearch.unpackX(vector),
      MotionSearch.unpackY(vector),
      out
    );
  }

  /** The superblock's prediction at a vector, when its last evaluation made one, or null. */
  private int @Nullable [] prediction(final int vector) {
    final FrameJob frame = this.job;
    for (int vectorIndex = 0; vectorIndex < frame.vectorCount(); vectorIndex++) {
      if (vector == MotionSearch.pack(frame.vectorX(vectorIndex), frame.vectorY(vectorIndex))) {
        return this.globalPrediction[vectorIndex];
      }
      if (vector == this.localVectors[vectorIndex]) {
        return this.localPrediction[vectorIndex];
      }
    }
    return null;
  }

  /** Whether the local vector found for a global vector is that vector itself. */
  private boolean isGlobal(final int vectorIndex) {
    return this.localVectors[vectorIndex] == MotionSearch.pack(this.job.vectorX(vectorIndex), this.job.vectorY(vectorIndex));
  }

  /** Whether the search tries a mode in this frame: the reference search tries every one. */
  private boolean tries(final @Nullable LiveSearch live, final int mode) {
    return live == null || live.tries(mode, this.job.isKeyframe(), this.size);
  }

  /** Whether the search tries a quantizer. */
  private boolean triesQuantizer(final @Nullable LiveSearch live, final int quantizer) {
    return live == null || live.triesQuantizer(quantizer, this.job.settings().lambda());
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
    final boolean storesNibbles = kind == CompactRecord.GRID4_N4_YC || kind == CompactRecord.GRID4_N4_Y;
    for (int quantizer = 0; quantizer < LiveSearch.COARSEST_QUANTIZER; quantizer++) {
      final int step = 1 << quantizer;
      boolean fits = true;
      for (int valueIndex = 0; valueIndex < values && fits; valueIndex++) {
        final int low = storesNibbles && valueIndex < GRID4_NODES ? NIBBLE_MIN : Byte.MIN_VALUE;
        final int high = storesNibbles && valueIndex < GRID4_NODES ? NIBBLE_MAX : Byte.MAX_VALUE;
        final float scaled = (float) Math.floor(fit[valueIndex] / step + 0.5f);
        fits = scaled >= low && scaled <= high;
      }
      if (fits) {
        return quantizer;
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

  private int search(final int left, final int top, final int vectorIndex, final @Nullable LiveSearch live, final int parent) {
    final FrameJob frame = this.job;
    if (live == null || !live.seededMotion()) {
      return MotionSearch.search(
        frame.reference(),
        frame.width(),
        frame.height(),
        this.source,
        left,
        top,
        this.size,
        frame.vectorX(vectorIndex),
        frame.vectorY(vectorIndex),
        frame.settings().motionRange(),
        frame.steps()
      );
    }
    // the previous frame's motion at the block's centre and just outside its four edges, and the enclosing block's
    final int half = this.size / 2;
    final int[] seeds = this.seeds;
    seeds[0] = frame.previousMotion(left + half, top + half);
    seeds[1] = frame.previousMotion(left - 1, top + half);
    seeds[2] = frame.previousMotion(left + this.size, top + half);
    seeds[3] = frame.previousMotion(left + half, top - 1);
    seeds[4] = frame.previousMotion(left + half, top + this.size);
    seeds[5] = parent < 0 ? seeds[0] : parent;
    if ((this.shortcuts & LiveSearch.HALF_MOTION) != 0 && this.size >= HALF_MOTION_SMALLEST) {
      Arrays.fill(seeds, this.halfResolutionMotion(left, top, vectorIndex));
    }
    return this.kernels.seeded(
      frame.reference(),
      frame.width(),
      frame.height(),
      this.source,
      left,
      top,
      this.size,
      frame.vectorX(vectorIndex),
      frame.vectorY(vectorIndex),
      frame.settings().motionRange(),
      frame.settings().halfPixel(),
      seeds
    );
  }

  /**
   * Searches the block's local motion on the pictures at half resolution, from its seeds halved, and returns the vector
   * found there at full resolution, for the full search to refine: at half resolution a step spans two pixels and a
   * half-pixel step is a whole pixel of the full pictures.
   */
  private int halfResolutionMotion(final int left, final int top, final int vectorIndex) {
    final FrameJob frame = this.job;
    this.kernels.halve(this.source, this.size, this.halfSource);
    if ((this.shortcuts & LiveSearch.QUARTER_MOTION) != 0 && this.size == ROOT_SIZE) {
      Arrays.fill(this.halfSeeds, this.quarterResolutionMotion(left, top, vectorIndex));
    } else {
      for (int seedIndex = 0; seedIndex < this.seeds.length; seedIndex++) {
        this.halfSeeds[seedIndex] = MotionSearch.pack(
          MotionSearch.unpackX(this.seeds[seedIndex]) / 2,
          MotionSearch.unpackY(this.seeds[seedIndex]) / 2
        );
      }
    }
    final int coarse = this.kernels.seeded(
      frame.halfReference(),
      (frame.width() + 1) / 2,
      (frame.height() + 1) / 2,
      this.halfSource,
      left / 2,
      top / 2,
      this.size / 2,
      frame.vectorX(vectorIndex) / 2,
      frame.vectorY(vectorIndex) / 2,
      frame.settings().motionRange() / 2,
      true,
      this.halfSeeds
    );
    return MotionSearch.pack(MotionSearch.unpackX(coarse) * 2, MotionSearch.unpackY(coarse) * 2);
  }

  /**
   * Searches a superblock's local motion on the pictures at a quarter of the resolution, from its seeds quartered, and
   * returns the vector found there in half-resolution units, for the half-resolution search to refine.
   */
  private int quarterResolutionMotion(final int left, final int top, final int vectorIndex) {
    final FrameJob frame = this.job;
    this.kernels.halve(this.halfSource, this.size / 2, this.quarterSource);
    for (int seedIndex = 0; seedIndex < this.seeds.length; seedIndex++) {
      this.quarterSeeds[seedIndex] = MotionSearch.pack(
        MotionSearch.unpackX(this.seeds[seedIndex]) / 4,
        MotionSearch.unpackY(this.seeds[seedIndex]) / 4
      );
    }
    final int halfWidth = (frame.width() + 1) / 2;
    final int halfHeight = (frame.height() + 1) / 2;
    final int coarse = this.kernels.seeded(
      frame.quarterReference(),
      (halfWidth + 1) / 2,
      (halfHeight + 1) / 2,
      this.quarterSource,
      left / 4,
      top / 4,
      this.size / 4,
      frame.vectorX(vectorIndex) / 4,
      frame.vectorY(vectorIndex) / 4,
      frame.settings().motionRange() / 4,
      true,
      this.quarterSeeds
    );
    return MotionSearch.pack(MotionSearch.unpackX(coarse) * 2, MotionSearch.unpackY(coarse) * 2);
  }

  /** Halves a square block of channels: each pixel of the result is the rounded mean of a 2x2 square. */
  static void halve(final int[] block, final int size, final int[] out) {
    final int half = size / 2;
    final int rowLength = size * CHANNELS;
    for (int row = 0; row < half; row++) {
      for (int column = 0; column < half; column++) {
        final int at = (2 * row * size + 2 * column) * CHANNELS;
        for (int channel = 0; channel < CHANNELS; channel++) {
          final int sum =
            block[at + channel] +
            block[at + CHANNELS + channel] +
            block[at + rowLength + channel] +
            block[at + rowLength + CHANNELS + channel];
          out[(row * half + column) * CHANNELS + channel] = (sum + 2) >> 2;
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

  private void loadSource(final int left, final int top) {
    final BlockCoder parent = this.root;
    // a smaller block is always coded right after the superblock it lies in, by the same worker
    if (parent != null) {
      final int rowLength = this.size * CHANNELS;
      for (int row = 0; row < this.size; row++) {
        final int from = ((top - parent.top + row) * ROOT_SIZE + (left - parent.left)) * CHANNELS;
        System.arraycopy(parent.source, from, this.source, row * rowLength, rowLength);
      }
      this.left = left;
      this.top = top;
      return;
    }
    this.left = left;
    this.top = top;
    final FrameJob frame = this.job;
    this.kernels.loadSource(frame.source(), frame.width(), frame.height(), left, top, this.size, this.source);
  }

  /**
   * Sixteen times six times the weighted YCoCg squared error of a reconstruction, an exact integer.
   *
   * @param source        the source channels
   * @param reconstructed the reconstructed channels, as many
   * @return the distortion
   */
  static long distortion(final int[] source, final int[] reconstructed) {
    long sum = 0;
    for (int offset = 0; offset < source.length; offset += CHANNELS) {
      sum += Reconstruction.pixelError(
        source[offset] - reconstructed[offset],
        source[offset + 1] - reconstructed[offset + 1],
        source[offset + 2] - reconstructed[offset + 2]
      );
    }
    return sum;
  }

  /**
   * Checks whether a candidate's rate alone leaves it a chance in at least one of its trials, and remembers the rate
   * for {@link #score}.
   */
  private boolean eligible(final int mode, final int length, final int mask) {
    final FrameJob frame = this.job;
    final double bits = mode == MODE_SKIP && this.size == ROOT_SIZE ? 1.0 : INDEX_BITS;
    this.rate = frame.settings().lambda() * (length * Byte.SIZE + bits);
    // the candidate must be cheaper than the dearest trial it belongs to; the kernel stops measuring once it cannot be
    double dearest = Double.NEGATIVE_INFINITY;
    for (int trial = 0; trial < frame.trialCount(); trial++) {
      if (((mask >> trial) & 1) != 0) {
        dearest = Math.max(dearest, frame.cost(trial, this.level)[this.block]);
      }
    }
    this.kernels.start(this.source, this.rate, dearest);
    return dearest > this.rate;
  }

  /** Scores the reconstruction in {@link #recon} and records it for every trial it improves. */
  private void score(final int mode, final int quantizer, final int length, final int mask) {
    final FrameJob frame = this.job;
    final long distortion = this.kernels.distortion();
    final double cost = distortion / Reconstruction.DISTORTION_SCALE + this.rate;
    for (int trial = 0; trial < frame.trialCount(); trial++) {
      if (((mask >> trial) & 1) != 0 && cost < frame.cost(trial, this.level)[this.block]) {
        if (trial == 0 && this.keepsBest) {
          System.arraycopy(this.recon, 0, this.best, 0, this.recon.length);
        }
        frame.set(trial, this.level, this.block, cost, mode, quantizer, this.record, length, distortion);
      }
    }
  }

  /** The block's clustered palette endpoints, computed once per block for the palette and both pattern precisions. */
  private float[] endpoints() {
    if (!this.clustered) {
      if ((this.shortcuts & LiveSearch.FAST_PALETTES) != 0) {
        this.kernels.cluster(this.source, this.size, this.clusters);
      } else {
        this.kernels.paletteCluster(this.source, this.count, this.clusters);
      }
      this.clustered = true;
    }
    return this.clusters;
  }

  private void setMotionBytes(final int vectorIndex) {
    final int vector = this.localVectors[vectorIndex];
    this.record[0] = (byte) (MotionSearch.unpackX(vector) - this.job.vectorX(vectorIndex));
    this.record[1] = (byte) (MotionSearch.unpackY(vector) - this.job.vectorY(vectorIndex));
  }

  private void solid() {
    final int length = recordSize(MODE_SOLID, this.size);
    if (!this.eligible(MODE_SOLID, length, this.job.allTrials())) {
      return;
    }
    long redSum = 0;
    long greenSum = 0;
    long blueSum = 0;
    if ((this.shortcuts & LiveSearch.FAST_GRIDS) != 0) {
      // the cells' sums, which the fast intra grids need anyway: the same integers, summed in another order
      final int[] sums = this.cells();
      for (int cell = 0; cell < FastFits.CELL_SUMS; cell += CHANNELS) {
        redSum += sums[cell];
        greenSum += sums[cell + 1];
        blueSum += sums[cell + 2];
      }
    } else {
      for (int pixel = 0; pixel < this.count; pixel++) {
        redSum += this.source[pixel * CHANNELS];
        greenSum += this.source[pixel * CHANNELS + 1];
        blueSum += this.source[pixel * CHANNELS + 2];
      }
    }
    final int red = roundMean(redSum, this.count);
    final int green = roundMean(greenSum, this.count);
    final int blue = roundMean(blueSum, this.count);
    this.record[0] = (byte) red;
    this.record[1] = (byte) green;
    this.record[2] = (byte) blue;
    if (this.kernels.solid((red << 16) | (green << 8) | blue, this.size, this.recon)) {
      this.score(MODE_SOLID, 0, length, this.job.allTrials());
    }
  }

  /** The channel sums of the block's 4x4 cells ({@link FastFits#cellSums}), computed on the block's first use. */
  private int[] cells() {
    if (!this.celled) {
      this.kernels.cellSums(this.source, this.size, this.cells);
      this.celled = true;
    }
    return this.cells;
  }

  /** The reference's {@code rgb8} of a float64 mean of integers. */
  private static int roundMean(final long sum, final int count) {
    final double mean = (double) sum / count;
    return (int) Math.floor(Math.min(Math.max(mean, 0.0), MAX_CHANNEL) + 0.5);
  }

  private void writePalette(final byte[] target) {
    for (int index = 0; index < SELECTORS_AT; index++) {
      target[index] = (byte) this.colors[index];
    }
    for (int selectorByte = 0; selectorByte < this.count / Byte.SIZE; selectorByte++) {
      target[SELECTORS_AT + selectorByte] = 0;
    }
    for (int pixel = 0; pixel < this.count; pixel++) {
      target[SELECTORS_AT + pixel / Byte.SIZE] |= (byte) (this.selectors[pixel] << (pixel % Byte.SIZE));
    }
  }

  private void palette() {
    final int length = recordSize(MODE_PALETTE, this.size);
    if (!this.eligible(MODE_PALETTE, length, this.job.allTrials())) {
      return;
    }
    this.kernels.finish(this.source, this.count, this.endpoints(), false, this.colors, this.selectors);
    this.writePalette(this.record);
    if (this.kernels.palette(this.record, 0, this.size, this.recon)) {
      this.score(MODE_PALETTE, 0, length, this.job.allTrials());
    }
  }

  private void pattern(final boolean coarse) {
    final int length = patternSize(this.size, false, false);
    final int mask = this.job.coarseMask(coarse);
    if (!this.eligible(MODE_PATTERN, length, mask)) {
      return;
    }
    if (!this.kernels.finishPattern(this.source, this.size, this.endpoints(), coarse, this.colors, this.selectors)) {
      return;
    }
    this.writePalette(this.palette);
    // the selectors repeat along an axis, so they make a pattern record
    final byte[] pattern = Preconditions.checkNotNull(TreeReader.patternRecord(this.palette, this.size));
    System.arraycopy(pattern, 0, this.record, 0, pattern.length);
    if (this.kernels.palette(this.palette, 0, this.size, this.recon)) {
      this.score(MODE_PATTERN, 0, length, mask);
    }
  }

  private static int quantize(final float value, final int step, final int low, final int high) {
    final float scaled = (float) Math.floor(value / step + 0.5f);
    return (int) Math.min(Math.max(scaled, low), high);
  }

  private void intraGrid(final int gridSize) {
    final int mode = MODE_INTRA + Integer.numberOfTrailingZeros(gridSize);
    final int length = recordSize(mode, this.size);
    if (!this.eligible(mode, length, this.job.allTrials())) {
      return;
    }
    if ((this.shortcuts & LiveSearch.FAST_GRIDS) != 0 && gridSize <= FastFits.CELL_GRID) {
      FastFits.grid(this.cells(), this.size, gridSize, this.grid);
    } else {
      if (!this.rgbLoaded) {
        for (int sample = 0; sample < this.count * CHANNELS; sample++) {
          this.rgb[sample] = this.source[sample];
        }
        this.rgbLoaded = true;
      }
      for (int channel = 0; channel < CHANNELS; channel++) {
        this.kernels.fit(this.rgb, channel, CHANNELS, this.size, gridSize, this.grid, channel, CHANNELS);
      }
    }
    for (int index = 0; index < length; index++) {
      this.record[index] = (byte) Reconstruction.rgb8(this.grid[index]);
    }
    if (this.kernels.intraGrid(this.record, 0, gridSize, this.size, this.recon)) {
      this.score(mode, 0, length, this.job.allTrials());
    }
  }

  /**
   * The YCoCg of the source, converted on the block's first use: most blocks of a live search end before any candidate
   * that needs it. The chroma is converted only when a tried candidate reads it.
   */
  private float[] ycocg() {
    if (!this.ycocgLoaded) {
      this.kernels.ycocg(this.source, this.count, this.needsChroma, this.ycocg);
      this.ycocgLoaded = true;
    }
    return this.ycocg;
  }

  /** The YCoCg residual of the source against a prediction, into {@link #target}. */
  private void residualTarget(final int[] prediction) {
    this.meansLoaded = false;
    this.gridLoaded = false;
    this.kernels.residualTarget(this.ycocg(), prediction, this.count, this.needsChroma, this.target);
  }

  private void residualGrid(final int vectorIndex, final int gridSize) {
    final int mode = MODE_RESIDUAL + Integer.numberOfTrailingZeros(gridSize);
    final int length = recordSize(mode, this.size);
    final int mask = this.job.vectorMask(vectorIndex);
    if (!this.eligible(mode, length, mask)) {
      return;
    }
    this.residualTarget(this.localPrediction[vectorIndex]);
    for (int channel = 0; channel < CHANNELS; channel++) {
      this.kernels.fit(this.target, channel, CHANNELS, this.size, gridSize, this.grid, channel, CHANNELS);
    }
    for (int quantizer = 0; quantizer < RESIDUAL_QUANTIZERS; quantizer++) {
      if (!this.eligible(mode, length, mask)) {
        return;
      }
      if (!this.triesQuantizer(this.job.settings().live(), quantizer)) {
        continue;
      }
      this.setMotionBytes(vectorIndex);
      for (int index = 0; index < CHANNELS * gridSize * gridSize; index++) {
        this.record[MOTION_BYTES + index] = (byte) quantize(this.grid[index], 1 << quantizer, Byte.MIN_VALUE, Byte.MAX_VALUE);
      }
      if (
        this.kernels.residualGrid(this.localPrediction[vectorIndex], this.record, MOTION_BYTES, gridSize, quantizer, this.size, this.recon)
      ) {
        this.score(mode, quantizer, length, mask);
      }
    }
  }

  /**
   * Fits the luma of {@link #target} into the grid's start and its interleaved chroma from {@link #CHROMA_NODES}, by
   * least squares or, with {@link LiveSearch#CELL_FITS}, as the means of the grids' cells.
   */
  private void fitReduced(final int luma, final int chroma) {
    if ((this.shortcuts & LiveSearch.CELL_FITS) != 0) {
      this.cellMeans(0, luma, this.grid, 0, 1);
      this.cellMeans(1, chroma, this.grid, CHROMA_NODES, CHROMA_PLANES);
      this.cellMeans(2, chroma, this.grid, CHROMA_NODES + 1, CHROMA_PLANES);
      return;
    }
    this.kernels.fit(this.target, 0, CHANNELS, this.size, luma, this.grid, 0, 1);
    this.kernels.fit(this.target, 1, CHANNELS, this.size, chroma, this.grid, CHROMA_NODES, CHROMA_PLANES);
    this.kernels.fit(this.target, 2, CHANNELS, this.size, chroma, this.grid, CHROMA_NODES + 1, CHROMA_PLANES);
  }

  /** One channel of {@link #target} as the means of a grid's cells. */
  private void cellMeans(final int channel, final int gridSize, final float[] out, final int outOffset, final int outStride) {
    this.kernels.cellMeans(this.target, this.size, channel, gridSize, out, outOffset, outStride);
  }

  private void reducedIntra(final int mode) {
    final int luma = lumaGrid(mode);
    final int chroma = chromaGrid(mode);
    final int length = recordSize(mode, this.size);
    if (!this.eligible(mode, length, this.job.allTrials())) {
      return;
    }
    System.arraycopy(this.ycocg(), 0, this.target, 0, this.count * CHANNELS);
    this.meansLoaded = false;
    this.gridLoaded = false;
    this.fitReduced(luma, chroma);
    for (int index = 0; index < luma * luma; index++) {
      this.record[index] = (byte) quantize(this.grid[index], 1, 0, MAX_CHANNEL);
    }
    for (int index = 0; index < CHROMA_PLANES * chroma * chroma; index++) {
      this.record[luma * luma + index] = (byte) quantize(this.grid[CHROMA_NODES + index], 1, Byte.MIN_VALUE, Byte.MAX_VALUE);
    }
    if (this.kernels.reduced(null, this.record, 0, luma, chroma, 0, this.size, this.recon)) {
      this.score(mode, 0, length, this.job.allTrials());
    }
  }

  private void reducedResidual(final int vectorIndex, final int mode) {
    final int luma = lumaGrid(mode);
    final int chroma = chromaGrid(mode);
    final int length = recordSize(mode, this.size);
    final int mask = this.job.vectorMask(vectorIndex);
    if (!this.eligible(mode, length, mask)) {
      return;
    }
    this.residualTarget(this.localPrediction[vectorIndex]);
    this.fitReduced(luma, chroma);
    for (int quantizer = 0; quantizer < RESIDUAL_QUANTIZERS; quantizer++) {
      if (!this.eligible(mode, length, mask)) {
        return;
      }
      if (!this.triesQuantizer(this.job.settings().live(), quantizer)) {
        continue;
      }
      this.setMotionBytes(vectorIndex);
      for (int index = 0; index < luma * luma; index++) {
        this.record[MOTION_BYTES + index] = (byte) quantize(this.grid[index], 1 << quantizer, Byte.MIN_VALUE, Byte.MAX_VALUE);
      }
      for (int index = 0; index < CHROMA_PLANES * chroma * chroma; index++) {
        final int at = MOTION_BYTES + luma * luma + index;
        this.record[at] = (byte) quantize(this.grid[CHROMA_NODES + index], 1 << quantizer, Byte.MIN_VALUE, Byte.MAX_VALUE);
      }
      final int[] prediction = this.localPrediction[vectorIndex];
      if (this.kernels.reduced(prediction, this.record, MOTION_BYTES, luma, chroma, quantizer, this.size, this.recon)) {
        this.score(mode, quantizer, length, mask);
      }
    }
  }

  private void compact(final int vectorIndex, final boolean local) {
    final int[] prediction = local ? this.localPrediction[vectorIndex] : this.globalPrediction[vectorIndex];
    final int deltaX = local ? MotionSearch.unpackX(this.localVectors[vectorIndex]) - this.job.vectorX(vectorIndex) : 0;
    final int deltaY = local ? MotionSearch.unpackY(this.localVectors[vectorIndex]) - this.job.vectorY(vectorIndex) : 0;
    final int form = compactForm(deltaX, deltaY);
    final int mask = this.job.vectorMask(vectorIndex);
    boolean targeted = false;
    final LiveSearch live = this.job.settings().live();
    for (final int kind : COMPACT_CLASSES) {
      final int length = 1 + form + CompactRecord.bodyBytes(kind);
      if (!triesClass(live, kind) || !this.eligible(MODE_COMPACT, length, mask)) {
        continue;
      }
      final int values;
      if ((this.shortcuts & LiveSearch.FAST_COMPACT) != 0 && kind == CompactRecord.GRID4_N4_Y) {
        this.kernels.lumaResidual(this.source, prediction, this.size, this.fit);
        values = GRID4_NODES;
      } else {
        if (!targeted) {
          this.residualTarget(prediction);
          targeted = true;
        }
        values = this.compactFit(kind);
      }
      final int body = 1 + form;
      final boolean fitted = (this.shortcuts & LiveSearch.FIT_ONE) != 0;
      final int needed = fitted ? neededQuantizer(kind, this.fit, values) : 0;
      for (int quantizer = 0; quantizer <= LiveSearch.COARSEST_QUANTIZER; quantizer++) {
        if (!this.eligible(MODE_COMPACT, length, mask)) {
          break;
        }
        if (!this.triesQuantizer(live, quantizer) || (fitted && quantizer != needed)) {
          continue;
        }
        this.record[0] = (byte) (kind | (form << NIBBLE_BITS));
        if (form == CompactRecord.FORM_NIBBLES) {
          this.record[1] = (byte) ((deltaX & NIBBLE_MASK) | ((deltaY & NIBBLE_MASK) << NIBBLE_BITS));
        } else if (form == CompactRecord.FORM_BYTES) {
          this.record[1] = (byte) deltaX;
          this.record[2] = (byte) deltaY;
        }
        this.compactBody(kind, values, quantizer, body);
        if (this.kernels.compact(prediction, this.record, body, kind, quantizer, this.size, this.recon)) {
          this.score(MODE_COMPACT, quantizer, length, mask);
        }
      }
    }
  }

  /** The motion form of a compact record whose local vector differs from the global one by deltaX, deltaY half pixels. */
  private static int compactForm(final int deltaX, final int deltaY) {
    if (deltaX == 0 && deltaY == 0) {
      return CompactRecord.FORM_GLOBAL;
    }
    final boolean fitsNibbles = deltaX >= NIBBLE_MIN && deltaX <= NIBBLE_MAX && deltaY >= NIBBLE_MIN && deltaY <= NIBBLE_MAX;
    return fitsNibbles ? CompactRecord.FORM_NIBBLES : CompactRecord.FORM_BYTES;
  }

  /** Fits a compact class to {@link #target} into {@link #fit}; returns how many values it holds. */
  private int compactFit(final int kind) {
    final float[] target = this.target;
    if (kind == CompactRecord.DC_Y) {
      this.fit[0] = this.mean(0);
      return 1;
    }
    if (kind == CompactRecord.LOW2) {
      double momentX = 0;
      double momentY = 0;
      for (int row = 0; row < this.size; row++) {
        for (int column = 0; column < this.size; column++) {
          final float luma = target[(row * this.size + column) * CHANNELS];
          momentX += luma * this.axis[column];
          momentY += luma * this.axis[row];
        }
      }
      this.fit[0] = this.mean(0);
      this.fit[1] = (float) (momentX / this.count) / this.meanSquare;
      this.fit[2] = (float) (momentY / this.count) / this.meanSquare;
      this.fit[3] = this.mean(1);
      this.fit[4] = this.mean(2);
      return CompactRecord.bodyBytes(CompactRecord.LOW2);
    }
    final int gridSize = kind == CompactRecord.GRID2_YC ? SMALL_GRID : FastFits.CELL_GRID;
    final boolean cells = (this.shortcuts & LiveSearch.CELL_FITS) != 0;
    if (gridSize == SMALL_GRID) {
      if (cells) {
        this.cellMeans(0, gridSize, this.fit, 0, 1);
      } else {
        this.kernels.fit(target, 0, CHANNELS, this.size, gridSize, this.fit, 0, 1);
      }
    } else {
      if (!this.gridLoaded) {
        if (cells) {
          this.cellMeans(0, gridSize, this.lumaGrid, 0, 1);
        } else {
          this.kernels.fit(target, 0, CHANNELS, this.size, gridSize, this.lumaGrid, 0, 1);
        }
        this.gridLoaded = true;
      }
      System.arraycopy(this.lumaGrid, 0, this.fit, 0, GRID4_NODES);
    }
    if (kind == CompactRecord.GRID4_N4_Y) {
      // luma alone: no chroma offsets to fit
      return gridSize * gridSize;
    }
    this.fit[gridSize * gridSize] = this.mean(1);
    this.fit[gridSize * gridSize + 1] = this.mean(2);
    return gridSize * gridSize + 2;
  }

  /**
   * The mean of a channel of {@link #target}, each channel summed in double in index order: the three sums in one pass,
   * once per target, since the compact classes ask for them one after another.
   */
  private float mean(final int channel) {
    if (!this.meansLoaded) {
      final float[] target = this.target;
      double lumaSum = 0;
      double chromaOrangeSum = 0;
      double chromaGreenSum = 0;
      for (int offset = 0; offset < target.length; offset += CHANNELS) {
        lumaSum += target[offset];
        chromaOrangeSum += target[offset + 1];
        chromaGreenSum += target[offset + 2];
      }
      this.means[0] = (float) (lumaSum / this.count);
      this.means[1] = (float) (chromaOrangeSum / this.count);
      this.means[2] = (float) (chromaGreenSum / this.count);
      this.meansLoaded = true;
    }
    return this.means[channel];
  }

  private void compactBody(final int kind, final int values, final int quantizer, final int body) {
    final int step = 1 << quantizer;
    if (kind == CompactRecord.GRID4_N4_YC || kind == CompactRecord.GRID4_N4_Y) {
      final int nibbleBytes = GRID4_NODES / 2;
      for (int index = 0; index < nibbleBytes; index++) {
        final int low = quantize(this.fit[2 * index], step, NIBBLE_MIN, NIBBLE_MAX) & NIBBLE_MASK;
        final int high = quantize(this.fit[2 * index + 1], step, NIBBLE_MIN, NIBBLE_MAX) & NIBBLE_MASK;
        this.record[body + index] = (byte) (low | (high << NIBBLE_BITS));
      }
      if (kind == CompactRecord.GRID4_N4_YC) {
        this.record[body + nibbleBytes] = (byte) quantize(this.fit[GRID4_NODES], step, Byte.MIN_VALUE, Byte.MAX_VALUE);
        this.record[body + nibbleBytes + 1] = (byte) quantize(this.fit[GRID4_NODES + 1], step, Byte.MIN_VALUE, Byte.MAX_VALUE);
      }
      return;
    }
    for (int index = 0; index < values; index++) {
      this.record[body + index] = (byte) quantize(this.fit[index], step, Byte.MIN_VALUE, Byte.MAX_VALUE);
    }
  }
}
