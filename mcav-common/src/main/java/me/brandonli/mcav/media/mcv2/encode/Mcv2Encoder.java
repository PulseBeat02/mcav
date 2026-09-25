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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReferenceArray;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Frame;
import me.brandonli.mcav.media.mcv2.Reconstruction;
import me.brandonli.mcav.media.mcv2.Workers;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The MCV2 encoder: a closed-loop rate-distortion encoder that follows the research codec's {@code TreeEncoder}.
 *
 * <p>For every frame it chooses keyframe or P frame (first frame, key interval, size change, or a scene cut detected
 * as a mean luma change above the threshold after global prediction), estimates the global motion, and evaluates
 * every candidate record of every block at 32, 16 and 8 pixels for every trial: the global vector and, when it is not
 * zero, the zero vector, each with full and with RGB565 pattern endpoints. Each trial keeps, per block, the first
 * strictly cheapest candidate of its own sequence; the block tree is then chosen bottom-up by the same cost, every
 * trial is serialized in the production form, and the trial with the lowest distortion plus lambda times its actual
 * bits is kept, the first on a tie. The kept frame is decoded, and only that decoded picture becomes the reference.
 *
 * <p>Block evaluation runs in parallel. Every block's result depends only on the previous decoded frame, never on a
 * neighbour, and every reduction runs in a fixed order, so the output is the same for any number of threads.
 *
 * <p>Instances are not thread-safe; one encoder encodes one stream.
 */
public final class Mcv2Encoder {

  private final EncoderSettings settings;
  private final Workers workers;
  private final boolean verify;

  private byte@Nullable[] reference;
  private long referenceId;
  private int width;
  private int height;
  private long lastFrameId = -1;
  private int framesSinceKey;
  private int@Nullable[] motion;
  private @Nullable LiveBuffers buffers;
  private FrameJob.@Nullable Buffers jobBuffers;
  private final ArrayDeque<BlockCoder[]> idleCoders = new ArrayDeque<>();
  private final List<BlockCoder[]> busyCoders = new ArrayList<>();
  private long@Nullable[] projections;
  private @Nullable Stats stats;

  /**
   * One frame's outcome.
   *
   * @param bytes      the frame length
   * @param keyframe   whether the frame is independent
   * @param globalX    the global horizontal motion in half pixels
   * @param globalY    the global vertical motion in half pixels
   * @param trial      the index of the kept trial
   * @param leaves     the number of leaves of the kept tree
   * @param nanoseconds the encode time
   */
  public record Stats(int bytes, boolean keyframe, int globalX, int globalY, int trial, int leaves, long nanoseconds) {}

  /**
   * Constructs a new encoder.
   *
   * @param settings the profile
   * @param pool     the pool block evaluation runs on
   * @param threads  how many workers evaluate blocks at once, at least 1
   * @param verify   whether every frame is checked against its own decode before it becomes the reference; keep this
   *                 on unless a measurement has shown the cost matters
   */
  public Mcv2Encoder(final EncoderSettings settings, final ForkJoinPool pool, final int threads, final boolean verify) {
    Preconditions.checkNotNull(settings, "Settings must not be null");
    Preconditions.checkNotNull(pool, "Pool must not be null");
    Preconditions.checkArgument(threads >= 1, "At least one thread is needed");
    this.settings = settings;
    this.workers = new Workers(pool, threads);
    this.verify = verify;
    this.framesSinceKey = settings.keyInterval();
  }

  /**
   * The pictures a live search reuses from frame to frame: one per level, and two decoded pictures, one of which is the
   * reference while the other receives the next frame.
   */
  private static final class LiveBuffers {

    private final int width;
    private final int height;
    private final byte[][] levels;
    private final byte[][] pictures;

    LiveBuffers(final int width, final int height) {
      this.width = width;
      this.height = height;
      this.levels = new byte[3][width * height * 3];
      this.pictures = new byte[2][width * height * 3];
    }

    boolean fits(final int w, final int h) {
      return this.width == w && this.height == h;
    }

    byte[][] levels() {
      return this.levels;
    }

    /**
     * The decoded picture that is not the given reference. Identity is the point: under the keyframe policy the
     * reference stays in one buffer for many frames, so the buffers cannot simply take turns.
     */
    @SuppressWarnings("ReferenceEquality")
    byte[] spare(final byte[] reference) {
      return reference == this.pictures[0] ? this.pictures[1] : this.pictures[0];
    }
  }

  /** The live buffers for a size, made again when the size changes. */
  private LiveBuffers buffers(final int width, final int height) {
    final LiveBuffers current = this.buffers;
    if (current != null && current.fits(width, height)) {
      return current;
    }
    final LiveBuffers made = new LiveBuffers(width, height);
    this.buffers = made;
    return made;
  }

  /**
   * Makes the next frame a keyframe, for example because a viewer starts watching and holds no reference yet.
   */
  public void requestKeyframe() {
    this.framesSinceKey = this.settings.keyInterval();
  }

  /**
   * Gets the outcome of the last encoded frame.
   *
   * @return the statistics, or null before the first frame
   */
  public @Nullable Stats getStats() {
    return this.stats;
  }

  /**
   * Gets a copy of the picture the next P frame predicts from.
   *
   * @return the reference, or null before the first frame
   */
  public byte@Nullable[] getReference() {
    final byte[] current = this.reference;
    return current == null ? null : current.clone();
  }

  /**
   * Encodes one frame.
   *
   * @param rgb     the picture, row-major RGB, {@code width * height * 3} bytes
   * @param width   the width, 1 to 4096
   * @param height  the height, 1 to 4096
   * @param frameId the unsigned 32-bit frame id, newer than the previous frame's
   * @return the frame bytes
   * @throws IllegalArgumentException if the picture or the id is invalid
   * @throws IllegalStateException    if the verification finds the frame does not decode to what was chosen
   */
  public byte[] encode(final byte[] rgb, final int width, final int height, final long frameId) {
    Preconditions.checkNotNull(rgb, "Picture must not be null");
    Preconditions.checkArgument(width >= 1 && width <= MAX_DIMENSION && height >= 1 && height <= MAX_DIMENSION, "Invalid dimensions");
    Preconditions.checkArgument(rgb.length == width * height * 3, "Picture size does not match the dimensions");
    Preconditions.checkArgument(frameId >= 0 && frameId <= 0xFFFFFFFFL, "Frame id must be an unsigned 32-bit value");
    if (this.lastFrameId >= 0) {
      final long distance = (frameId - this.lastFrameId) & 0xFFFFFFFFL;
      Preconditions.checkArgument(distance != 0 && distance < 0x80000000L, "Stale or ambiguous frame number");
    }
    final long started = System.nanoTime();
    final byte[] previous = this.reference;
    final LiveSearch live = this.settings.live();
    boolean key = true;
    int motion = 0;
    byte[] predictFrom = new byte[0];
    final boolean predictable = width == this.width && height == this.height && this.framesSinceKey < this.settings.keyInterval();
    if (live == null) {
      if (previous != null && predictable) {
        final int estimate = GlobalMotion.estimate(rgb, previous, width, height, this.workers);
        if (!this.sceneCut(rgb, previous, width, height, estimate)) {
          key = false;
          motion = estimate;
          predictFrom = previous;
        }
      }
    } else {
      // a live frame is encoded once: its vector is estimated from the projections of the source frames and chosen
      // between it and zero by what SKIP would cost with each
      final long[] projections = GlobalMotion.projections(rgb, width, height, this.workers);
      final long[] before = this.projections;
      if (previous != null && predictable) {
        // every live frame, the keyframe before this one included, left its projections
        final int estimate = GlobalMotion.estimateLive(
          rgb,
          previous,
          width,
          height,
          Preconditions.checkNotNull(before),
          projections,
          this.workers
        );
        final int[] candidates = this.settings.compareGlobal() && estimate != 0 ? new int[] { 0, estimate } : new int[] { estimate };
        final LiveAnalysis.Result analysis = LiveAnalysis.analyze(
          rgb,
          previous,
          width,
          height,
          this.settings.lambda(),
          this.settings.sceneThreshold(),
          candidates,
          this.workers
        );
        if (!analysis.sceneCut()) {
          key = false;
          motion = candidates[analysis.vector()];
          predictFrom = previous;
        }
      }
      this.projections = projections;
    }
    final int mx = motion >> 16;
    final int my = (short) motion;
    final int[] vectorsX;
    final int[] vectorsY;
    if (!key && this.settings.compareGlobal() && motion != 0 && live == null) {
      vectorsX = new int[] { 0, mx };
      vectorsY = new int[] { 0, my };
    } else {
      vectorsX = new int[] { mx };
      vectorsY = new int[] { my };
    }
    final FrameJob job = new FrameJob(
      this.settings,
      rgb,
      predictFrom,
      width,
      height,
      key,
      vectorsX,
      vectorsY,
      key ? null : this.motion,
      live == null ? null : this.buffers(width, height).levels(),
      this.jobBuffers
    );
    this.jobBuffers = job.buffers();
    final long referenceId = key ? frameId : this.referenceId;
    byte[] best = new byte[0];
    byte[] bestPicture = new byte[0];
    List<Leaf> bestLeaves = List.of();
    List<TreeNode> bestRoots = List.of();
    int bestTrial = 0;
    if (live == null) {
      this.evaluate(job);
      double bestCost = Double.POSITIVE_INFINITY;
      for (int t = 0; t < job.trialCount(); t++) {
        final List<Leaf> leaves = new ArrayList<>();
        final List<TreeNode> roots = this.select(job, t, leaves);
        final List<TreeNode> serialized = new ArrayList<>(roots.size());
        for (final TreeNode root : roots) {
          serialized.add(TreeReader.withPatterns(root, ROOT_SIZE));
        }
        final boolean coarse = job.isCoarse(t);
        final int vector = job.trialVector(t);
        final byte[] data = FrameWriter.write(
          width,
          height,
          frameId,
          referenceId,
          key,
          vectorsX[vector],
          vectorsY[vector],
          serialized,
          FrameWriter.Options.production(coarse)
        );
        final byte[] picture = decodeChosen(data, predictFrom, this.referenceId, this.workers);
        final double cost = trialError(rgb, picture, width, height) / 96.0 + this.settings.lambda() * 8 * data.length;
        if (cost < bestCost) {
          bestCost = cost;
          best = data;
          bestPicture = picture;
          bestLeaves = leaves;
          bestRoots = roots;
          bestTrial = t;
        }
      }
      if (this.verify) {
        check(job, bestTrial, best, bestPicture, bestLeaves, bestRoots);
      }
    } else {
      final LiveFrame frame = this.evaluateLive(job, live);
      best = FrameWriter.write(
        width,
        height,
        frameId,
        referenceId,
        key,
        mx,
        my,
        frame.serialized(),
        FrameWriter.Options.production(live.coarseEndpoints())
      );
      bestPicture = frame.picture();
      bestLeaves = frame.leaves();
      bestRoots = frame.roots();
      if (this.verify) {
        check(job, 0, best, bestPicture, bestLeaves, bestRoots);
        // the picture was assembled from the search's reconstructions: the decoder must produce it too
        final byte[] decoded = decodeChosen(best, predictFrom, this.referenceId, this.workers);
        Preconditions.checkState(Arrays.equals(bestPicture, decoded), "MCV2 live picture and decoded picture disagree");
      }
    }
    if (key || this.settings.reference() == EncoderSettings.ReferencePolicy.PREVIOUS_FRAME) {
      this.reference = bestPicture;
      this.referenceId = frameId;
    }
    if (live != null) {
      this.motion = motionField(bestRoots, width, height, vectorsX[job.trialVector(bestTrial)], vectorsY[job.trialVector(bestTrial)]);
    }
    this.width = width;
    this.height = height;
    this.lastFrameId = frameId;
    this.framesSinceKey = key ? 1 : this.framesSinceKey + 1;
    this.stats = new Stats(
      best.length,
      key,
      vectorsX[job.trialVector(bestTrial)],
      vectorsY[job.trialVector(bestTrial)],
      bestTrial,
      bestLeaves.size(),
      System.nanoTime() - started
    );
    return best;
  }

  /**
   * Decodes a frame the encoder has just written.
   *
   * @throws IllegalStateException if the decoder rejects it, which would be an encoder defect
   */
  static byte[] decodeChosen(final byte[] data, final byte[] reference, final long referenceId, final Workers workers) {
    try {
      final Mcv2Frame frame = FrameParser.parse(data);
      return Mcv2Decoder.decode(frame, frame.isKeyframe() ? null : reference, referenceId, workers);
    } catch (final Mcv2Exception exception) {
      throw new IllegalStateException("The encoder wrote a frame the decoder rejects", exception);
    }
  }

  /** Whether the mean absolute luma change after global prediction exceeds the scene threshold, exactly. */
  private boolean sceneCut(final byte[] rgb, final byte[] previous, final int width, final int height, final int motion) {
    final int columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
    final int rows = (height + ROOT_SIZE - 1) / ROOT_SIZE;
    final int[] prediction = new int[ROOT_SIZE * ROOT_SIZE * 3];
    long sum = 0;
    for (int by = 0; by < rows; by++) {
      for (int bx = 0; bx < columns; bx++) {
        Reconstruction.predict(
          previous,
          width,
          height,
          bx * ROOT_SIZE,
          by * ROOT_SIZE,
          ROOT_SIZE,
          motion >> 16,
          (short) motion,
          prediction
        );
        for (int py = 0; py < ROOT_SIZE; py++) {
          final int sy = Math.min(by * ROOT_SIZE + py, height - 1);
          for (int px = 0; px < ROOT_SIZE; px++) {
            final int sx = Math.min(bx * ROOT_SIZE + px, width - 1);
            final int s = (sy * width + sx) * 3;
            final int p = (py * ROOT_SIZE + px) * 3;
            final int luma16 = 4 * ((rgb[s] & 0xFF) + 2 * (rgb[s + 1] & 0xFF) + (rgb[s + 2] & 0xFF));
            sum += Math.abs(luma16 - (prediction[p] + 2 * prediction[p + 1] + prediction[p + 2]));
          }
        }
      }
    }
    final double pixels = (double) columns * rows * ROOT_SIZE * ROOT_SIZE;
    return sum / 16.0 / pixels > this.settings.sceneThreshold();
  }

  /** Evaluates every block of every level on the workers, each worker pulling superblocks from a shared counter. */
  private void evaluate(final FrameJob job) {
    final int columns = job.columns(0);
    final int superblocks = columns * ((job.height() + ROOT_SIZE - 1) / ROOT_SIZE);
    this.workers.forEach(
        superblocks,
        () -> this.coders(job),
        (coders, index) -> superblock(job, coders, (index % columns) * ROOT_SIZE, (index / columns) * ROOT_SIZE)
      );
    this.releaseCoders();
  }

  /**
   * What a live search produced: one tree per superblock in raster order, the same trees in the form the writer takes,
   * their leaves, and the decoded picture.
   */
  private static final class LiveFrame {

    private final List<TreeNode> roots;
    private final List<TreeNode> serialized;
    private final List<Leaf> leaves;
    private final byte[] picture;

    LiveFrame(final List<TreeNode> roots, final List<TreeNode> serialized, final List<Leaf> leaves, final byte[] picture) {
      this.roots = roots;
      this.serialized = serialized;
      this.leaves = leaves;
      this.picture = picture;
    }

    List<TreeNode> roots() {
      return this.roots;
    }

    List<TreeNode> serialized() {
      return this.serialized;
    }

    List<Leaf> leaves() {
      return this.leaves;
    }

    byte[] picture() {
      return this.picture;
    }
  }

  /**
   * Evaluates the blocks of a live search on the workers: every superblock from the top, a block's quarters only when
   * it did not end at an early SKIP, the smallest block size is not reached, and its best cost is above the split
   * threshold. A block that is not evaluated keeps an infinite cost, which {@link #select} never chooses. Each worker
   * then chooses its superblock's tree and copies the reconstructions of the chosen leaves, which are the decoder's
   * own, into the frame's picture, so the frame needs no decode.
   */
  private LiveFrame evaluateLive(final FrameJob job, final LiveSearch live) {
    final int columns = job.columns(0);
    final int superblocks = columns * ((job.height() + ROOT_SIZE - 1) / ROOT_SIZE);
    final int deepest = Integer.numberOfTrailingZeros(ROOT_SIZE / live.smallestBlock());
    final double[] split = { live.splitThreshold() * this.settings.lambda(), live.fineThreshold() * this.settings.lambda() };
    final TreeNode[] roots = new TreeNode[superblocks];
    final TreeNode[] serialized = new TreeNode[superblocks];
    final AtomicReferenceArray<List<Leaf>> leaves = new AtomicReferenceArray<>(superblocks);
    // the other of the two pictures: the one the reference is not, which this frame's reconstruction replaces
    final byte[] picture = Preconditions.checkNotNull(this.buffers).spare(job.reference());
    this.workers.forEach(
        superblocks,
        () -> this.coders(job),
        (coders, index) -> {
          final int x = (index % columns) * ROOT_SIZE;
          final int y = (index / columns) * ROOT_SIZE;
          descend(job, coders, 0, x, y, -1, deepest, split, live.childGate(), 0);
          final List<Leaf> chosen = new ArrayList<>();
          roots[index] = Preconditions.checkNotNull(this.select(job, 0, x, y, 0, chosen)).node();
          serialized[index] = TreeReader.withPatterns(roots[index], ROOT_SIZE);
          for (final Leaf leaf : chosen) {
            assemble(job, leaf, picture);
          }
          leaves.set(index, chosen);
        }
      );
    this.releaseCoders();
    final List<Leaf> all = new ArrayList<>();
    for (int index = 0; index < superblocks; index++) {
      all.addAll(leaves.get(index));
    }
    return new LiveFrame(List.of(roots), List.of(serialized), all, picture);
  }

  /** Copies a chosen leaf's reconstruction from the picture of its level, cropped to the frame. */
  private static void assemble(final FrameJob job, final Leaf leaf, final byte[] picture) {
    final byte[] source = Preconditions.checkNotNull(job.levelPicture(leaf.level()));
    final int width = job.width();
    final int right = Math.min(leaf.x() + leaf.size(), width);
    final int bottom = Math.min(leaf.y() + leaf.size(), job.height());
    for (int y = leaf.y(); y < bottom; y++) {
      final int at = (y * width + leaf.x()) * 3;
      System.arraycopy(source, at, picture, at, (right - leaf.x()) * 3);
    }
  }

  private static void descend(
    final FrameJob job,
    final BlockCoder[] coders,
    final int level,
    final int x,
    final int y,
    final int parent,
    final int deepest,
    final double[] split,
    final double gate,
    final double share
  ) {
    if (x >= job.width() || y >= job.height()) {
      return;
    }
    final int size = ROOT_SIZE >> level;
    final int block = (y / size) * job.columns(level) + x / size;
    final BlockCoder coder = coders[level];
    coder.code(level, block, x, y, parent, share);
    final double cost = job.cost(0, level)[block];
    if (level == deepest || coder.isSkipped() || cost <= split[level]) {
      return;
    }
    final int vector = coder.localVector();
    final int half = size / 2;
    final double quarter = (cost / 4) * gate;
    for (int i = 0; i < 4; i++) {
      descend(job, coders, level + 1, x + (i % 2) * half, y + (i / 2) * half, vector, deepest, split, gate, quarter);
    }
  }

  /**
   * The motion of a coded frame, one vector per 8x8 cell in raster order, for the next frame's live search: the vector
   * each leaf predicts from, and the global vector for a leaf without prediction.
   */
  static int[] motionField(final List<TreeNode> roots, final int width, final int height, final int globalX, final int globalY) {
    final int columns = (width + 7) / 8;
    final int[] field = new int[columns * ((height + 7) / 8)];
    final int rootColumns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
    for (int i = 0; i < roots.size(); i++) {
      fill(
        field,
        columns,
        width,
        height,
        roots.get(i),
        (i % rootColumns) * ROOT_SIZE,
        (i / rootColumns) * ROOT_SIZE,
        ROOT_SIZE,
        globalX,
        globalY
      );
    }
    return field;
  }

  private static void fill(
    final int[] field,
    final int columns,
    final int width,
    final int height,
    final TreeNode node,
    final int x,
    final int y,
    final int size,
    final int globalX,
    final int globalY
  ) {
    if (node.isSplit()) {
      final int half = size / 2;
      for (int i = 0; i < 4; i++) {
        fill(field, columns, width, height, node.getChild(i), x + (i % 2) * half, y + (i / 2) * half, half, globalX, globalY);
      }
      return;
    }
    final int vector = leafVector(node, globalX, globalY);
    for (int cy = y; cy < Math.min(y + size, height); cy += 8) {
      for (int cx = x; cx < Math.min(x + size, width); cx += 8) {
        field[(cy / 8) * columns + cx / 8] = vector;
      }
    }
  }

  /** The vector a leaf predicts from, as {@code x << 16 | (y & 0xFFFF)} in half pixels. */
  static int leafVector(final TreeNode leaf, final int globalX, final int globalY) {
    final int mode = leaf.getMode();
    final byte[] record = leaf.getRecord();
    int dx = 0;
    int dy = 0;
    if (mode == MODE_MOTION || isResidual(mode)) {
      dx = record[0];
      dy = record[1];
    } else if (mode == MODE_COMPACT) {
      final int form = (record[0] & 0xFF) >> 4;
      if (form == 1) {
        dx = signed(record[1] & 15, 4);
        dy = signed((record[1] & 0xFF) >> 4, 4);
      } else if (form == 2) {
        dx = record[1];
        dy = record[2];
      }
    }
    return ((globalX + dx) << 16) | ((globalY + dy) & 0xFFFF);
  }

  /**
   * One worker's coders for a frame, the smaller ones loading their blocks from the superblock's: the coders of an
   * earlier frame bound to this one, or new ones.
   */
  private BlockCoder[] coders(final FrameJob job) {
    BlockCoder[] coders;
    synchronized (this.idleCoders) {
      coders = this.idleCoders.poll();
      if (coders == null) {
        coders = newCoders(job);
      }
      this.busyCoders.add(coders);
    }
    for (final BlockCoder coder : coders) {
      coder.bind(job);
    }
    return coders;
  }

  private static BlockCoder[] newCoders(final FrameJob job) {
    final BlockCoder[] coders = { new BlockCoder(job, 32), new BlockCoder(job, 16), new BlockCoder(job, 8) };
    coders[1].loadFrom(coders[0]);
    coders[2].loadFrom(coders[0]);
    return coders;
  }

  /** Makes the coders of a finished frame's workers available to the next frame. */
  private void releaseCoders() {
    synchronized (this.idleCoders) {
      this.idleCoders.addAll(this.busyCoders);
      this.busyCoders.clear();
    }
  }

  private static void superblock(final FrameJob job, final BlockCoder[] coders, final int x, final int y) {
    for (int level = 0; level < 3; level++) {
      final int size = ROOT_SIZE >> level;
      final int span = ROOT_SIZE / size;
      for (int j = 0; j < span; j++) {
        for (int i = 0; i < span; i++) {
          final int bx = x + i * size;
          final int by = y + j * size;
          if (bx < job.width() && by < job.height()) {
            coders[level].code(level, (by / size) * job.columns(level) + bx / size, bx, by);
          }
        }
      }
    }
  }

  /** A chosen leaf, remembered for the verification. */
  record Leaf(int x, int y, int size, int level, int block) {}

  /** The node, its cost and its leaves, chosen bottom-up like the reference's {@code select}. */
  private record Choice(TreeNode node, double cost) {}

  private List<TreeNode> select(final FrameJob job, final int trial, final List<Leaf> leaves) {
    final List<TreeNode> roots = new ArrayList<>();
    for (int y = 0; y < job.height(); y += ROOT_SIZE) {
      for (int x = 0; x < job.width(); x += ROOT_SIZE) {
        roots.add(Preconditions.checkNotNull(this.select(job, trial, x, y, 0, leaves)).node());
      }
    }
    return roots;
  }

  private @Nullable Choice select(final FrameJob job, final int trial, final int x, final int y, final int level, final List<Leaf> leaves) {
    final double lambda = this.settings.lambda();
    if (x >= job.width() || y >= job.height()) {
      return new Choice(TreeNode.leaf(MODE_SOLID, 0, new byte[3]), lambda * 56);
    }
    final int size = ROOT_SIZE >> level;
    final int block = (y / size) * job.columns(level) + x / size;
    final double cost = job.cost(trial, level)[block];
    if (cost == Double.POSITIVE_INFINITY) {
      // a live search did not evaluate the block
      return null;
    }
    final TreeNode leaf = TreeNode.leaf(job.mode(trial, level, block), job.quantizer(trial, level, block), job.record(trial, level, block));
    if (level == 2) {
      leaves.add(new Leaf(x, y, size, level, block));
      return new Choice(leaf, cost);
    }
    final List<Leaf> childLeaves = new ArrayList<>();
    final int half = size / 2;
    final Choice[] children = new Choice[4];
    double splitCost = lambda * BlockCoder.INDEX_BITS;
    for (int i = 0; i < 4; i++) {
      final Choice child = this.select(job, trial, x + (i % 2) * half, y + (i / 2) * half, level + 1, childLeaves);
      if (child == null) {
        splitCost = Double.POSITIVE_INFINITY;
        break;
      }
      children[i] = child;
      splitCost += child.cost();
    }
    if (splitCost < cost) {
      leaves.addAll(childLeaves);
      return new Choice(TreeNode.split(children[0].node(), children[1].node(), children[2].node(), children[3].node()), splitCost);
    }
    leaves.add(new Leaf(x, y, size, level, block));
    return new Choice(leaf, cost);
  }

  /** The reference's trial error: the weighted squared error over the frame padded to 32-pixel blocks by replication. */
  private static long trialError(final byte[] source, final byte[] picture, final int width, final int height) {
    final int extraX = (ROOT_SIZE - (width % ROOT_SIZE)) % ROOT_SIZE;
    final int extraY = (ROOT_SIZE - (height % ROOT_SIZE)) % ROOT_SIZE;
    long sum = 0;
    for (int y = 0; y < height; y++) {
      final int weightY = y == height - 1 ? 1 + extraY : 1;
      for (int x = 0; x < width; x++) {
        final int weight = weightY * (x == width - 1 ? 1 + extraX : 1);
        final int at = (y * width + x) * 3;
        final int dr = (source[at] & 0xFF) - (picture[at] & 0xFF);
        final int dg = (source[at + 1] & 0xFF) - (picture[at + 1] & 0xFF);
        final int db = (source[at + 2] & 0xFF) - (picture[at + 2] & 0xFF);
        final int luma = dr + 2 * dg + db;
        final int co = dr - db;
        final int cg = 2 * dg - dr - db;
        sum += weight * (4L * luma * luma + 4L * co * co + (long) cg * cg);
      }
    }
    return sum;
  }

  /**
   * Checks the kept frame: its bytes must describe exactly the chosen tree, and every leaf inside the picture must
   * decode to exactly the distortion the search measured for it.
   */
  static void check(
    final FrameJob job,
    final int trial,
    final byte[] data,
    final byte[] picture,
    final List<Leaf> leaves,
    final List<TreeNode> roots
  ) {
    final int width = job.width();
    final int height = job.height();
    final List<TreeNode> written = new ArrayList<>(roots.size());
    final List<TreeNode> expected = new ArrayList<>(roots.size());
    try {
      written.addAll(TreeReader.roots(FrameParser.parse(data)));
      for (final TreeNode root : roots) {
        expected.add(TreeReader.withPalettes(root, ROOT_SIZE));
      }
    } catch (final Mcv2Exception exception) {
      throw new IllegalStateException("The encoder wrote a frame the parser rejects", exception);
    }
    if (!written.equals(expected)) {
      throw new IllegalStateException("MCV2 encoder and serializer disagree about the tree");
    }
    for (final Leaf leaf : leaves) {
      if (leaf.x() + leaf.size() > width || leaf.y() + leaf.size() > height) {
        continue;
      }
      long d16 = 0;
      for (int py = leaf.y(); py < leaf.y() + leaf.size(); py++) {
        for (int px = leaf.x(); px < leaf.x() + leaf.size(); px++) {
          final int at = (py * width + px) * 3;
          final int dr = (job.source()[at] & 0xFF) - (picture[at] & 0xFF);
          final int dg = (job.source()[at + 1] & 0xFF) - (picture[at + 1] & 0xFF);
          final int db = (job.source()[at + 2] & 0xFF) - (picture[at + 2] & 0xFF);
          final int luma = dr + 2 * dg + db;
          final int co = dr - db;
          final int cg = 2 * dg - dr - db;
          d16 += 4L * luma * luma + 4L * co * co + (long) cg * cg;
        }
      }
      if (d16 != job.distortion(trial, leaf.level(), leaf.block())) {
        throw new IllegalStateException("MCV2 encoder/decoder disagreement at " + leaf.x() + "," + leaf.y() + " size " + leaf.size());
      }
    }
  }
}
