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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.BLOCK_SIZES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHANNELS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MAX_DIMENSION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MAX_U32;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_COMPACT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SOLID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.QUARTERS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ROOT_SIZE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SMALLEST_BLOCK;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.follows;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.isResidual;

import com.google.common.base.Preconditions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import me.brandonli.mcav.bukkit.media.mcv2.CompactRecord;
import me.brandonli.mcav.bukkit.media.mcv2.FrameParser;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Frame;
import me.brandonli.mcav.bukkit.media.mcv2.Reconstruction;
import me.brandonli.mcav.bukkit.media.mcv2.Workers;
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
 * <p>A frame is encoded in two steps: {@link #begin} analyses, searches and writes it and leaves the encoder ready for
 * the next frame, whose reference is this frame's reconstruction; {@link #finish} verifies it. {@link #encode} does
 * both. A pipelined caller verifies frame N on one thread while it searches frame N+1 on another, one frame in flight
 * at most: it calls {@code finish} for every frame, in order, and begins frame N+2 only after frame N is finished.
 *
 * <p>Instances are not thread-safe beyond that: one encoder encodes one stream.
 *
 * <p>The encoder borrows its pool and has no close operation. Keep that pool running for the stream and
 * close it through its owner after all encoders finish. Input RGB arrays are read synchronously by begin and may
 * be reused after it returns; do not mutate them during encoding. Configure settings, limits and keyframe requests
 * on the search thread between begin calls. Finish only pending frames from this encoder, exactly once and in
 * creation order. A verification failure permanently invalidates the stream; create a new encoder afterward.
 */
public final class Mcv2Encoder {

  /** The rate, in bits at lambda, the reference's selection charges a quarter outside the picture. */
  private static final int OUTSIDE_BITS = 56;

  /** The bands the verification compares the decoded picture in. */
  private static final int COMPARE_BANDS = 16;

  /** The decoded pictures a live encoder keeps: the reference, a frame being verified, and the next frame. */
  private static final int PICTURES = 3;

  /** The deepest level: 8-pixel leaves. */
  private static final int DEEPEST = BLOCK_SIZES - 1;

  /** The scene cut's luma differences are 16 times Y's: (r + 2 g + b) is four Y, against four-times predictions. */
  static final double SCENE_LUMA_SCALE = 16.0;

  private static final String STOPPED = "The encoder stopped after a frame failed its verification";

  /** No picture: what a keyframe predicts from, and what no frame in flight holds. */
  private static final byte[] NONE = new byte[0];

  /** How often a live frame over its byte bound is searched again at twice the lambda: up to sixteen times it. */
  static final int LIMIT_RETRIES = 4;

  /** The profile the next frame is coded with, which a live encoder may switch to another live profile. */
  private EncoderSettings settings;

  private final Workers workers;

  private final boolean shouldVerify;

  /** The picture the live search's check decodes into, kept from frame to frame. */
  private byte @Nullable [] verified;

  /** How long a live frame may search, in nanoseconds, or 0 for as long as it takes. */
  private long frameBudget;

  /** The most bytes a live frame may take, or 0 for any number. */
  private int frameLimit;

  private byte @Nullable [] reference;

  private long referenceId;

  private int width;

  private int height;

  private long lastFrameId = -1;

  private int framesSinceKey;

  private int @Nullable [] motion;

  private @Nullable LiveBuffers buffers;

  private FrameJob.@Nullable Buffers jobBuffers;

  private final ArrayDeque<BlockCoder[]> idleCoders = new ArrayDeque<>();

  private final List<BlockCoder[]> busyCoders = new ArrayList<>();

  private long @Nullable [] projections;

  /** Which superblocks the previous live frame split, in raster order, updated by every live frame. */
  private boolean @Nullable [] splitBefore;

  private volatile @Nullable Stats stats;

  /** Whether a frame failed its verification, which stops the encoder. */
  private volatile boolean failed;

  /** The frame begun last and the one before it, which must be finished before another begins. */
  private @Nullable Pending newer;

  private @Nullable Pending older;

  /** Makes the pixel kernels of each coder: the native ones for a live search where they load, else Java's. */
  private final Kernels.Factory kernels;

  /** The lambda of each frame from the motion of the source, when the live search asks for it. */
  private @Nullable MotionLambda motionLambda;

  /** Whether an adaptive profile's source moves: its frames are coded with the adaptive search. */
  private boolean moving;

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
   * @param lambda      the lambda the frame was searched with
   */
  public record Stats(int bytes, boolean keyframe, int globalX, int globalY, int trial, int leaves, long nanoseconds, double lambda) {}

  /** A verified frame: its bytes and its outcome. */
  public static final class Encoded {

    private final byte[] data;

    private final Stats stats;

    private Encoded(final byte[] data, final Stats stats) {
      this.data = data;
      this.stats = stats;
    }

    /**
     * Gets the frame bytes.
     *
     * @return the retained array without a copy; the caller may keep it, but must not mutate it while
     *         other consumers use this encoded frame
     */
    public byte[] getData() {
      return this.data;
    }

    /**
     * Gets the frame's outcome.
     *
     * @return the statistics
     */
    public Stats getStats() {
      return this.stats;
    }
  }

  /**
   * A frame {@link #begin} searched and wrote, which {@link #finish} verifies: its bytes, and what the check reads, none
   * of which the next frame's search changes.
   */
  public static final class Pending {

    private final byte[] data;

    /** Whether {@link #finish} checks the frame: a live frame with verification on. */
    private final boolean checked;

    private final List<TreeNode> roots;

    /** The picture the search assembled from its reconstructions, the next frame's reference. */
    final byte[] picture;

    private final byte[] predictFrom;

    private final long predictFromId;

    private final boolean isKeyframe;

    private final int globalX;

    private final int globalY;

    private final int trial;

    private final int leaves;

    private final double lambda;

    private long searchNanos;

    private volatile boolean finished;

    private Pending(
      final byte[] data,
      final boolean checked,
      final List<TreeNode> roots,
      final byte[] picture,
      final byte[] predictFrom,
      final long predictFromId,
      final boolean isKeyframe,
      final int globalX,
      final int globalY,
      final int trial,
      final int leaves,
      final double lambda
    ) {
      this.data = data;
      this.checked = checked;
      this.roots = roots;
      this.picture = picture;
      this.predictFrom = predictFrom;
      this.predictFromId = predictFromId;
      this.isKeyframe = isKeyframe;
      this.globalX = globalX;
      this.globalY = globalY;
      this.trial = trial;
      this.leaves = leaves;
      this.lambda = lambda;
    }

    /**
     * Checks whether the frame is a keyframe.
     *
     * @return true for a keyframe
     */
    public boolean isKeyframe() {
      return this.isKeyframe;
    }
  }

  /**
   * Constructs a new encoder.
   *
   * @param settings     the profile
   * @param pool         the pool block evaluation runs on
   * @param threads      how many workers evaluate blocks at once, at least 1
   * @param shouldVerify whether to compare each frame with its decode; live begin advances the reference
   *                     before finish checks it, so callers must finish successfully before transmitting it
   * @throws NullPointerException if settings or pool is null
   * @throws IllegalArgumentException if threads is nonpositive
   */
  public Mcv2Encoder(final EncoderSettings settings, final ForkJoinPool pool, final int threads, final boolean shouldVerify) {
    this(settings, pool, threads, shouldVerify, settings.live() == null ? JavaKernels.FACTORY : Mcv2Natives.factory());
  }

  /**
   * Constructs a new encoder whose coders use the given kernels.
   *
   * @param settings     the profile
   * @param pool         the pool block evaluation runs on
   * @param threads      how many workers evaluate blocks at once, at least 1
   * @param shouldVerify whether every frame is checked against its own decode
   * @param kernels      makes the kernels of each coder
   */
  Mcv2Encoder(
    final EncoderSettings settings,
    final ForkJoinPool pool,
    final int threads,
    final boolean shouldVerify,
    final Kernels.Factory kernels
  ) {
    Preconditions.checkNotNull(settings, "Settings must not be null");
    Preconditions.checkNotNull(pool, "Pool must not be null");
    Preconditions.checkArgument(threads >= 1, "At least one thread is needed");
    this.settings = settings;
    this.workers = new Workers(pool, threads);
    this.shouldVerify = shouldVerify;
    this.kernels = kernels;
    this.framesSinceKey = settings.keyInterval();
    final LiveSearch live = settings.live();
    this.motionLambda = live != null && live.motionLambda() ? new MotionLambda() : null;
  }

  /**
   * The pictures a live search reuses from frame to frame: one per level, and three decoded pictures: the reference,
   * the picture of the frame being verified meanwhile, and the one that receives the next frame.
   */
  private static final class LiveBuffers {

    private final int width;

    private final int height;

    private final byte[][] levels;

    private final byte[][] pictures;

    /** The reference at half and at a quarter of the resolution, for the motion search's first levels. */
    private final byte[] half;

    private final byte[] quarter;

    private LiveBuffers(final int width, final int height) {
      this.width = width;
      this.height = height;
      this.levels = new byte[BLOCK_SIZES][width * height * CHANNELS];
      this.pictures = new byte[PICTURES][width * height * CHANNELS];
      final int halfWidth = (width + 1) / 2;
      final int halfHeight = (height + 1) / 2;
      this.half = new byte[halfWidth * halfHeight * CHANNELS];
      this.quarter = new byte[((halfWidth + 1) / 2) * ((halfHeight + 1) / 2) * CHANNELS];
    }

    boolean fits(final int width, final int height) {
      return this.width == width && this.height == height;
    }

    byte[][] levels() {
      return this.levels;
    }

    /**
     * The first decoded picture that is none of the given ones: the reference, and the picture and the reference of a
     * frame that may still be verified. Identity is the point: under the keyframe policy the reference stays in one
     * buffer for many frames, so the buffers cannot simply take turns.
     */
    @SuppressWarnings("ReferenceEquality")
    private byte[] spare(final byte[] reference, final byte[] verifying, final byte[] verifyingReference) {
      for (int index = 0; index < PICTURES - 1; index++) {
        final byte[] picture = this.pictures[index];
        if (picture != reference && picture != verifying && picture != verifyingReference) {
          return picture;
        }
      }
      // the frame being verified and its reference take two pictures at most, and the next reference is one of them
      return this.pictures[PICTURES - 1];
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
   * Bounds how long the search of a live frame may take. A frame that has searched this long since its encode began
   * gives each superblock not yet searched the cheapest choice instead - SKIP with the frame's vector in a P frame, one
   * solid colour in a keyframe - which the frames after it refine, so a frame that runs long, such as a keyframe or a
   * scene cut on a busy machine, ends soon after its budget. What such a frame contains depends on how fast the
   * machine searched it, so the budget is off unless set. The reference's search takes no budget.
   *
   * <p>The budget is checked during live search, not an interrupting deadline. Already running work, writing
   * and verification may extend beyond it; serialized frame-size retries also share the elapsed search time.
   *
   * @param nanoseconds the budget of a frame, or 0 for none
   * @throws IllegalArgumentException if the budget is negative
   */
  public void setFrameBudget(final long nanoseconds) {
    Preconditions.checkArgument(nanoseconds >= 0, "The frame budget must not be negative");
    this.frameBudget = nanoseconds;
  }

  /**
   * Bounds the bytes of a live frame, for a screen whose page slots carry only so many: a frame that would take more is
   * searched again at twice the lambda, from the same history, up to {@value #LIMIT_RETRIES} times, and only the last
   * search counts. A gameplay keyframe at a live lambda can take more than a screen's slots carry, and a frame that
   * does not fit is not sent, so without the bound such a stream would never show again. The reference's search takes
   * no bound.
   *
   * <p>This is a search target, not a hard output cap: the last retry can still exceed it. Callers must check
   * the returned frame length and arrange a later keyframe if a frame is discarded.
   *
   * @param bytes the most bytes of a frame, or 0 for any number
   * @throws IllegalArgumentException if the bound is negative
   */
  public void setFrameLimit(final int bytes) {
    Preconditions.checkArgument(bytes >= 0, "The frame limit must not be negative");
    this.frameLimit = bytes;
  }

  /**
   * Makes the next frame a keyframe, for example because a viewer starts watching and holds no reference yet.
   */
  public void requestKeyframe() {
    this.framesSinceKey = this.settings.keyInterval();
  }

  /**
   * Gets the settings the encoder codes the next frame with: the ones it was made with, or the last it switched to.
   *
   * @return the settings
   */
  public EncoderSettings getSettings() {
    return this.settings;
  }

  /**
   * Codes the frames begun from now on with other live settings, continuing the stream without a keyframe: every live
   * search writes the same format and predicts from the same pictures, so a receiver needs nothing new and the frames
   * decode as any others do. The motion measured so far is kept; settings that do not measure it stop measuring.
   *
   * @param next the settings, a live profile's
   * @throws IllegalArgumentException if these or the next settings are not a live profile's
   * @throws NullPointerException if {@code next} is null
   */
  public void switchTo(final EncoderSettings next) {
    Preconditions.checkNotNull(next, "Settings must not be null");
    final LiveSearch live = next.live();
    if (live == null || this.settings.live() == null) {
      throw new IllegalArgumentException("Only live settings switch without a keyframe");
    }
    this.settings = next;
    if (!live.motionLambda()) {
      this.motionLambda = null;
    } else if (this.motionLambda == null) {
      this.motionLambda = new MotionLambda();
    }
  }

  /**
   * Gets the outcome of the last frame successfully finished.
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
  public byte @Nullable [] getReference() {
    final byte[] current = this.reference;
    return current == null ? null : current.clone();
  }

  /**
   * Encodes one frame: {@link #begin} and {@link #finish} at once.
   *
   * @param rgb     the picture, row-major RGB, {@code width * height * 3} bytes
   * @param width   the width, 1 to 4096
   * @param height  the height, 1 to 4096
   * @param frameId the unsigned 32-bit frame id, newer than the previous frame's
   * @return the frame bytes
   * @throws IllegalArgumentException if the picture or the id is invalid
   * @throws IllegalStateException    if the verification finds the frame does not decode to what was chosen, or an
   *                                  earlier frame's did
   * @throws NullPointerException if rgb is null
   */
  public byte[] encode(final byte[] rgb, final int width, final int height, final long frameId) {
    return this.finish(this.begin(rgb, width, height, frameId)).getData();
  }

  /**
   * Searches and writes one frame, and makes its reconstruction the reference of the frame after it; {@link #finish}
   * verifies it. The reference's search verifies here already: its check reads the frame's job, which the next frame
   * reuses. A keyframe request made after this call and before the next begin applies to that next begin call.
   *
   * @param rgb     the picture, row-major RGB, {@code width * height * 3} bytes
   * @param width   the width, 1 to 4096
   * @param height  the height, 1 to 4096
   * @param frameId the unsigned 32-bit frame id, newer than the previous frame's
   * @return the frame, which {@link #finish} verifies
   * @throws IllegalArgumentException if the picture or the id is invalid
   * @throws IllegalStateException    if a verification failed, or the frame before the one begun last is not finished
   * @throws NullPointerException if {@code rgb} is null
   */
  public Pending begin(final byte[] rgb, final int width, final int height, final long frameId) {
    Preconditions.checkState(!this.failed, STOPPED);
    Preconditions.checkState(this.older == null || this.older.finished, "Two frames are in flight already");
    Preconditions.checkNotNull(rgb, "Picture must not be null");
    Preconditions.checkArgument(width >= 1 && width <= MAX_DIMENSION && height >= 1 && height <= MAX_DIMENSION, "Invalid dimensions");
    Preconditions.checkArgument(rgb.length == width * height * CHANNELS, "Picture size does not match the dimensions");
    Preconditions.checkArgument(frameId >= 0 && frameId <= MAX_U32, "Frame id must be an unsigned 32-bit value");
    Preconditions.checkArgument(this.lastFrameId < 0 || follows(frameId, this.lastFrameId), "Stale or ambiguous frame number");
    final long started = System.nanoTime();
    final byte[] previous = this.reference;
    final MotionLambda control = this.motionLambda;
    final EncoderSettings.Adaptive adaptive = this.settings.adaptive();
    if (control != null && adaptive != null) {
      // the motion of the frames before this one: the search of this frame depends on the source alone
      this.moving = control.moving(this.moving, adaptive.enter(), adaptive.leave());
    }
    final EncoderSettings chosen = this.settings.frame(this.moving);
    final EncoderSettings settings = control == null ? chosen : chosen.withLambda(control.lambda(chosen.lambda()));
    final LiveSearch live = settings.live();
    boolean isKeyframe = true;
    int motion = 0;
    byte[] predictFrom = NONE;
    final boolean predictable = width == this.width && height == this.height && this.framesSinceKey < settings.keyInterval();
    if (live == null) {
      if (previous != null && predictable) {
        final int estimate = GlobalMotion.estimate(rgb, previous, width, height, this.workers);
        if (!this.sceneCut(rgb, previous, width, height, estimate)) {
          isKeyframe = false;
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
        final int[] candidates = settings.compareGlobal() && estimate != 0 ? new int[] { 0, estimate } : new int[] { estimate };
        final LiveAnalysis.Result analysis = LiveAnalysis.analyze(
          rgb,
          previous,
          width,
          height,
          settings.lambda(),
          settings.sceneThreshold(),
          candidates,
          this.workers
        );
        if (!analysis.sceneCut()) {
          isKeyframe = false;
          motion = candidates[analysis.vector()];
          predictFrom = previous;
        }
      }
      this.projections = projections;
      if (control != null) {
        // the next frame's lambda; a scene cut starts the motion over, a keyframe on the clock does not
        control.observe(rgb, width, height, predictable && isKeyframe, this.workers);
      }
    }
    final int motionX = MotionSearch.unpackX(motion);
    final int motionY = MotionSearch.unpackY(motion);
    final int[] vectorsX;
    final int[] vectorsY;
    if (!isKeyframe && settings.compareGlobal() && motion != 0 && live == null) {
      vectorsX = new int[] { 0, motionX };
      vectorsY = new int[] { 0, motionY };
    } else {
      vectorsX = new int[] { motionX };
      vectorsY = new int[] { motionY };
    }
    final FrameJob job = new FrameJob(
      settings,
      rgb,
      predictFrom,
      width,
      height,
      isKeyframe,
      vectorsX,
      vectorsY,
      isKeyframe ? null : this.motion,
      live == null ? null : this.buffers(width, height).levels(),
      this.jobBuffers
    );
    this.jobBuffers = job.buffers();
    final long referenceId = isKeyframe ? frameId : this.referenceId;
    byte[] best = new byte[0];
    byte[] bestPicture = new byte[0];
    List<Leaf> bestLeaves = List.of();
    List<TreeNode> bestRoots = List.of();
    int bestTrial = 0;
    int[] liveMotion = null;
    double lambda = settings.lambda();
    if (live == null) {
      this.evaluate(job);
      double bestCost = Double.POSITIVE_INFINITY;
      for (int trial = 0; trial < job.trialCount(); trial++) {
        final List<Leaf> leaves = new ArrayList<>();
        final List<TreeNode> roots = this.select(job, trial, leaves);
        final List<TreeNode> serialized = new ArrayList<>(roots.size());
        for (final TreeNode root : roots) {
          serialized.add(TreeReader.withPatterns(root, ROOT_SIZE));
        }
        final boolean coarse = job.isCoarse(trial);
        final int vector = job.trialVector(trial);
        final byte[] data = FrameWriter.write(
          width,
          height,
          frameId,
          referenceId,
          isKeyframe,
          vectorsX[vector],
          vectorsY[vector],
          serialized,
          FrameWriter.Options.production(coarse)
        );
        final byte[] picture = decodeChosen(data, predictFrom, this.referenceId, this.workers);
        final double cost =
          trialError(rgb, picture, width, height) / Reconstruction.DISTORTION_SCALE + settings.lambda() * Byte.SIZE * data.length;
        if (cost < bestCost) {
          bestCost = cost;
          best = data;
          bestPicture = picture;
          bestLeaves = leaves;
          bestRoots = roots;
          bestTrial = trial;
        }
      }
      if (this.shouldVerify) {
        check(job, bestTrial, best, bestPicture, bestLeaves, bestRoots, this.workers);
      }
    } else {
      // a search that must be redone at a higher lambda starts from the same history of splits
      final boolean @Nullable [] history = this.frameLimit > 0 && this.splitBefore != null ? this.splitBefore.clone() : null;
      FrameJob searched = job;
      LiveFrame frame = this.evaluateLive(searched, live, started);
      best = writeLive(searched, frame, frameId, referenceId, motionX, motionY, live);
      for (int retry = 0; this.frameLimit > 0 && best.length > this.frameLimit && retry < LIMIT_RETRIES; retry++) {
        this.splitBefore = history == null ? null : history.clone();
        searched = new FrameJob(
          searched.settings().withLambda(searched.settings().lambda() * 2),
          rgb,
          predictFrom,
          width,
          height,
          isKeyframe,
          vectorsX,
          vectorsY,
          isKeyframe ? null : this.motion,
          this.buffers(width, height).levels(),
          this.jobBuffers
        );
        this.jobBuffers = searched.buffers();
        frame = this.evaluateLive(searched, live, started);
        best = writeLive(searched, frame, frameId, referenceId, motionX, motionY, live);
      }
      lambda = searched.settings().lambda();
      bestPicture = frame.picture();
      bestLeaves = frame.leaves();
      bestRoots = frame.roots();
      liveMotion = frame.motion();
    }
    final Pending pending = new Pending(
      best,
      live != null && this.shouldVerify,
      bestRoots,
      bestPicture,
      predictFrom,
      this.referenceId,
      isKeyframe,
      vectorsX[job.trialVector(bestTrial)],
      vectorsY[job.trialVector(bestTrial)],
      bestTrial,
      bestLeaves.size(),
      lambda
    );
    if (isKeyframe || settings.reference() == EncoderSettings.ReferencePolicy.PREVIOUS_FRAME) {
      this.reference = bestPicture;
      this.referenceId = frameId;
    }
    if (liveMotion != null) {
      this.motion = liveMotion;
    }
    this.width = width;
    this.height = height;
    this.lastFrameId = frameId;
    this.framesSinceKey = isKeyframe ? 1 : this.framesSinceKey + 1;
    this.older = this.newer;
    this.newer = pending;
    pending.searchNanos = System.nanoTime() - started;
    return pending;
  }

  /** Writes a live frame's chosen trees. */
  private static byte[] writeLive(
    final FrameJob job,
    final LiveFrame frame,
    final long frameId,
    final long referenceId,
    final int motionX,
    final int motionY,
    final LiveSearch live
  ) {
    return FrameWriter.write(
      job.width(),
      job.height(),
      frameId,
      referenceId,
      job.isKeyframe(),
      motionX,
      motionY,
      frame.serialized(),
      FrameWriter.Options.production(live.coarseEndpoints())
    );
  }

  /**
   * Verifies a frame {@link #begin} searched and wrote, on the budget's workers - possibly while the next frame is
   * searched on another thread: the bytes must describe the chosen tree, and the picture assembled from the search's
   * reconstructions must be the one the decoder produces, decoded into a picture the encoder keeps for the check alone:
   * the picture a client decodes. The distortions the search measured only steered its choices, so they are not
   * measured again here, where a pass over every pixel costs a live frame more than its decode; the encoder's tests
   * check them. A frame that fails stops the encoder: the frame after it, begun already, predicts from its picture.
   *
   * @param pending the frame
   * @return the frame's bytes and statistics
   * @throws IllegalStateException if the verification finds the frame does not decode to what was chosen, if an
   *                               earlier frame's did, or if the frame is finished already
   * @throws NullPointerException if {@code pending} is null
   */
  public Encoded finish(final Pending pending) {
    Preconditions.checkNotNull(pending, "Frame must not be null");
    Preconditions.checkState(!this.failed, STOPPED);
    Preconditions.checkState(!pending.finished, "The frame is finished already");
    final long started = System.nanoTime();
    if (pending.checked) {
      try {
        final Mcv2Frame written = parseChosen(pending.data);
        checkTree(written, pending.roots, this.workers);
        final byte[] decoded = decodeChosen(written, pending.predictFrom, pending.predictFromId, this.workers, this.verified);
        this.verified = decoded;
        Preconditions.checkState(same(pending.picture, decoded, this.workers), "MCV2 live picture and decoded picture disagree");
      } catch (final IllegalStateException exception) {
        this.failed = true;
        throw exception;
      }
    }
    pending.finished = true;
    final Stats stats = new Stats(
      pending.data.length,
      pending.isKeyframe,
      pending.globalX,
      pending.globalY,
      pending.trial,
      pending.leaves,
      pending.searchNanos + System.nanoTime() - started,
      pending.lambda
    );
    this.stats = stats;
    return new Encoded(pending.data, stats);
  }

  /**
   * Compares two pictures, a band of each on every worker: a 1080p picture is 6 MB, which one thread compares in about
   * as long as the workers decode it.
   *
   * @param picture one picture
   * @param other   the other
   * @param workers the workers
   * @return whether they are equal
   */
  static boolean same(final byte[] picture, final byte[] other, final Workers workers) {
    if (picture.length != other.length) {
      return false;
    }
    final AtomicBoolean equal = new AtomicBoolean(true);
    workers.forEach(
      COMPARE_BANDS,
      () -> equal,
      (flag, band) -> {
        final int from = (int) (((long) picture.length * band) / COMPARE_BANDS);
        final int to = (int) (((long) picture.length * (band + 1)) / COMPARE_BANDS);
        if (!Arrays.equals(picture, from, to, other, from, to)) {
          flag.set(false);
        }
      }
    );
    return equal.get();
  }

  /**
   * Decodes a frame the encoder has just written.
   *
   * @throws IllegalStateException if the parser or the decoder rejects it, which would be an encoder defect
   */
  static byte[] decodeChosen(final byte[] data, final byte[] reference, final long referenceId, final Workers workers) {
    return decodeChosen(parseChosen(data), reference, referenceId, workers, null);
  }

  /** Decodes a frame the encoder wrote and parsed, into a picture it may reuse; see {@link Mcv2Decoder#decode}. */
  static byte[] decodeChosen(
    final Mcv2Frame frame,
    final byte[] reference,
    final long referenceId,
    final Workers workers,
    final byte @Nullable [] into
  ) {
    try {
      return Mcv2Decoder.decode(frame, frame.isKeyframe() ? null : reference, referenceId, workers, into);
    } catch (final Mcv2Exception exception) {
      throw new IllegalStateException("The encoder wrote a frame the decoder rejects", exception);
    }
  }

  /**
   * Parses a frame the encoder wrote.
   *
   * @throws IllegalStateException if the parser rejects it, which would be an encoder defect
   */
  private static Mcv2Frame parseChosen(final byte[] data) {
    try {
      return FrameParser.parse(data);
    } catch (final Mcv2Exception exception) {
      throw new IllegalStateException("The encoder wrote a frame the parser rejects", exception);
    }
  }

  /** Whether the mean absolute luma change after global prediction exceeds the scene threshold, exactly. */
  private boolean sceneCut(final byte[] rgb, final byte[] previous, final int width, final int height, final int motion) {
    final int columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
    final int rows = (height + ROOT_SIZE - 1) / ROOT_SIZE;
    final int[] prediction = new int[ROOT_SIZE * ROOT_SIZE * CHANNELS];
    long sum = 0;
    for (int blockRow = 0; blockRow < rows; blockRow++) {
      for (int blockColumn = 0; blockColumn < columns; blockColumn++) {
        Reconstruction.predict(
          previous,
          width,
          height,
          blockColumn * ROOT_SIZE,
          blockRow * ROOT_SIZE,
          ROOT_SIZE,
          MotionSearch.unpackX(motion),
          MotionSearch.unpackY(motion),
          prediction
        );
        for (int row = 0; row < ROOT_SIZE; row++) {
          final int sourceRow = Math.min(blockRow * ROOT_SIZE + row, height - 1);
          for (int column = 0; column < ROOT_SIZE; column++) {
            final int sourceColumn = Math.min(blockColumn * ROOT_SIZE + column, width - 1);
            final int sourceOffset = (sourceRow * width + sourceColumn) * CHANNELS;
            final int predictionOffset = (row * ROOT_SIZE + column) * CHANNELS;
            final int luma16 = 4 * ((rgb[sourceOffset] & 0xFF) + 2 * (rgb[sourceOffset + 1] & 0xFF) + (rgb[sourceOffset + 2] & 0xFF));
            sum += Math.abs(
              luma16 - (prediction[predictionOffset] + 2 * prediction[predictionOffset + 1] + prediction[predictionOffset + 2])
            );
          }
        }
      }
    }
    final double pixels = (double) columns * rows * ROOT_SIZE * ROOT_SIZE;
    return sum / SCENE_LUMA_SCALE / pixels > this.settings.sceneThreshold();
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
   * their leaves, the decoded picture, and the motion field the next frame's search starts from.
   */
  private static final class LiveFrame {

    private final List<TreeNode> roots;

    private final List<TreeNode> serialized;

    private final List<Leaf> leaves;

    private final byte[] picture;

    private final int[] motion;

    private LiveFrame(
      final List<TreeNode> roots,
      final List<TreeNode> serialized,
      final List<Leaf> leaves,
      final byte[] picture,
      final int[] motion
    ) {
      this.roots = roots;
      this.serialized = serialized;
      this.leaves = leaves;
      this.picture = picture;
      this.motion = motion;
    }

    private List<TreeNode> roots() {
      return this.roots;
    }

    private List<TreeNode> serialized() {
      return this.serialized;
    }

    private List<Leaf> leaves() {
      return this.leaves;
    }

    private byte[] picture() {
      return this.picture;
    }

    private int[] motion() {
      return this.motion;
    }
  }

  /**
   * Evaluates the blocks of a live search on the workers: every superblock from the top, a block's quarters only when
   * it did not end at an early SKIP, the smallest block size is not reached, and its best cost is above the split
   * threshold, the steady one for a superblock of a P frame that the previous frame coded whole. A block that is not evaluated keeps an infinite cost, which {@link #select} never chooses. Each worker
   * then chooses its superblock's tree and copies the reconstructions of the chosen leaves, which are the decoder's
   * own, into the frame's picture, so the frame needs no decode.
   */
  private LiveFrame evaluateLive(final FrameJob job, final LiveSearch live, final long started) {
    final int columns = job.columns(0);
    final int superblocks = columns * ((job.height() + ROOT_SIZE - 1) / ROOT_SIZE);
    final int deepest = Integer.numberOfTrailingZeros(ROOT_SIZE / live.smallestBlock());
    final double lambda = job.settings().lambda();
    // the thresholds of the 32- and 16-pixel levels; the 8-pixel level is never split
    final double[] split = { live.splitThreshold() * lambda, live.fineThreshold() * lambda, 0 };
    final double steady = live.steadySplitThreshold() * lambda;
    boolean[] before = this.splitBefore;
    if (before == null || before.length != superblocks) {
      before = new boolean[superblocks];
      this.splitBefore = before;
    }
    // a keyframe is not compared with the frame before it; its own splits still guide the next frame
    final boolean steadyApplies = !job.isKeyframe();
    final boolean[] splits = before;
    final TreeNode[] roots = new TreeNode[superblocks];
    final TreeNode[] serialized = new TreeNode[superblocks];
    final AtomicReferenceArray<List<Leaf>> leaves = new AtomicReferenceArray<>(superblocks);
    // the other of the two pictures: the one the reference is not, which this frame's reconstruction replaces
    // the frame begun before this one may still be verified: its picture and its reference stay as they are
    final Pending verifying = this.newer;
    final byte[] picture = Preconditions.checkNotNull(this.buffers).spare(
      job.reference(),
      verifying == null ? NONE : verifying.picture,
      verifying == null ? NONE : verifying.predictFrom
    );
    final long budget = this.frameBudget;
    final int[] motion = new int[FrameJob.motionColumns(job.width()) * FrameJob.motionColumns(job.height())];
    if ((live.shortcuts() & LiveSearch.HALF_MOTION) != 0 && !job.isKeyframe()) {
      final LiveBuffers pyramid = Preconditions.checkNotNull(this.buffers);
      final byte[] halfReference = half(job.reference(), job.width(), job.height(), this.workers, pyramid.half);
      job.halfReference(halfReference);
      if ((live.shortcuts() & LiveSearch.QUARTER_MOTION) != 0) {
        job.quarterReference(half(halfReference, (job.width() + 1) / 2, (job.height() + 1) / 2, this.workers, pyramid.quarter));
      }
    }
    this.workers.forEach(
      superblocks,
      () -> this.coders(job),
      (coders, index) -> {
        final int left = (index % columns) * ROOT_SIZE;
        final int top = (index / columns) * ROOT_SIZE;
        final double threshold = steadyApplies && !splits[index] ? steady : split[0];
        final boolean late = budget > 0 && System.nanoTime() - started >= budget;
        for (final BlockCoder coder : coders) {
          coder.setHurried(late);
        }
        descend(job, coders, 0, left, top, BlockCoder.NO_VECTOR, deepest, threshold, split, live.childGate(), 0);
        final List<Leaf> chosen = new ArrayList<>();
        roots[index] = Preconditions.checkNotNull(this.select(job, 0, left, top, 0, chosen)).node();
        // only this task reads and writes the superblock's entry
        splits[index] = roots[index].isSplit();
        serialized[index] = TreeReader.withPatterns(roots[index], ROOT_SIZE);
        fillMotion(motion, job.width(), job.height(), roots[index], index, job.vectorX(0), job.vectorY(0));
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
    return new LiveFrame(List.of(roots), List.of(serialized), all, picture, motion);
  }

  /**
   * A picture at half resolution: each pixel the rounded mean of a 2x2 square, the last row and column of an odd size
   * repeated.
   *
   * @param out receives the picture, {@code ((width + 1) / 2) * ((height + 1) / 2) * 3} bytes
   * @return {@code out}
   */
  static byte[] half(final byte[] picture, final int width, final int height, final Workers workers, final byte[] out) {
    final int halfWidth = (width + 1) / 2;
    final int halfHeight = (height + 1) / 2;
    workers.forEach(
      halfHeight,
      () -> out,
      (target, row) -> {
        final int topRow = 2 * row;
        final int bottomRow = Math.min(topRow + 1, height - 1);
        for (int column = 0; column < halfWidth; column++) {
          final int leftColumn = 2 * column;
          final int rightColumn = Math.min(leftColumn + 1, width - 1);
          for (int channel = 0; channel < CHANNELS; channel++) {
            final int sum =
              (picture[(topRow * width + leftColumn) * CHANNELS + channel] & 0xFF) +
              (picture[(topRow * width + rightColumn) * CHANNELS + channel] & 0xFF) +
              (picture[(bottomRow * width + leftColumn) * CHANNELS + channel] & 0xFF) +
              (picture[(bottomRow * width + rightColumn) * CHANNELS + channel] & 0xFF);
            target[(row * halfWidth + column) * CHANNELS + channel] = (byte) ((sum + 2) >> 2);
          }
        }
      }
    );
    return out;
  }

  /** Copies a chosen leaf's reconstruction from the picture of its level, cropped to the frame. */
  private static void assemble(final FrameJob job, final Leaf leaf, final byte[] picture) {
    final byte[] source = Preconditions.checkNotNull(job.levelPicture(leaf.level()));
    final int width = job.width();
    final int right = Math.min(leaf.left() + leaf.size(), width);
    final int bottom = Math.min(leaf.top() + leaf.size(), job.height());
    for (int row = leaf.top(); row < bottom; row++) {
      final int at = (row * width + leaf.left()) * CHANNELS;
      System.arraycopy(source, at, picture, at, (right - leaf.left()) * CHANNELS);
    }
  }

  /**
   * Evaluates a block and, where the search goes deeper, its quarters, and returns the cost {@link #select} gives the
   * block: its leaf's, or its split's when that is cheaper. The quarters are evaluated in order only while the split can
   * still be cheaper than the leaf: once the split's index and the quarters already evaluated cost as much as the leaf,
   * select keeps the leaf whatever the other quarters cost, so they are not evaluated. The sums are the ones select
   * makes, in its order, so this changes no choice.
   */
  private static double descend(
    final FrameJob job,
    final BlockCoder[] coders,
    final int level,
    final int left,
    final int top,
    final int parent,
    final int deepest,
    final double threshold,
    final double[] split,
    final double gate,
    final double share
  ) {
    final double lambda = job.settings().lambda();
    if (left >= job.width() || top >= job.height()) {
      return lambda * OUTSIDE_BITS;
    }
    final int size = ROOT_SIZE >> level;
    final int block = (top / size) * job.columns(level) + left / size;
    final BlockCoder coder = coders[level];
    coder.code(level, block, left, top, parent, share);
    final double cost = job.cost(0, level)[block];
    if (level == deepest || coder.isSkipped() || cost <= threshold) {
      return cost;
    }
    final int vector = coder.localVector();
    final int half = size / 2;
    final double quarter = (cost / QUARTERS) * gate;
    double splitCost = lambda * BlockCoder.INDEX_BITS;
    for (int childIndex = 0; childIndex < QUARTERS && splitCost < cost; childIndex++) {
      splitCost += descend(
        job,
        coders,
        level + 1,
        left + (childIndex % 2) * half,
        top + (childIndex / 2) * half,
        vector,
        deepest,
        split[level + 1],
        split,
        gate,
        quarter
      );
    }
    return Math.min(cost, splitCost);
  }

  /**
   * Fills one superblock's part of a coded frame's motion, one vector per 8x8 cell in raster order, for the next frame's
   * live search: the vector each leaf predicts from, and the global vector for a leaf without prediction.
   *
   * @param field   the frame's field, {@code ceil(width / 8) * ceil(height / 8)} vectors
   * @param width   the frame's width
   * @param height  the frame's height
   * @param root    the superblock's tree
   * @param index   the superblock's index in raster order
   * @param globalX the global vector's horizontal part, in half pixels
   * @param globalY the global vector's vertical part, in half pixels
   */
  static void fillMotion(
    final int[] field,
    final int width,
    final int height,
    final TreeNode root,
    final int index,
    final int globalX,
    final int globalY
  ) {
    final int rootColumns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
    fill(
      field,
      FrameJob.motionColumns(width),
      width,
      height,
      root,
      (index % rootColumns) * ROOT_SIZE,
      (index / rootColumns) * ROOT_SIZE,
      ROOT_SIZE,
      globalX,
      globalY
    );
  }

  private static void fill(
    final int[] field,
    final int columns,
    final int width,
    final int height,
    final TreeNode node,
    final int left,
    final int top,
    final int size,
    final int globalX,
    final int globalY
  ) {
    if (node.isSplit()) {
      final int half = size / 2;
      for (int quarter = 0; quarter < QUARTERS; quarter++) {
        fill(
          field,
          columns,
          width,
          height,
          node.getChild(quarter),
          left + (quarter % 2) * half,
          top + (quarter / 2) * half,
          half,
          globalX,
          globalY
        );
      }
      return;
    }
    final int vector = leafVector(node, globalX, globalY);
    for (int cellTop = top; cellTop < Math.min(top + size, height); cellTop += FrameJob.MOTION_CELL) {
      for (int cellLeft = left; cellLeft < Math.min(left + size, width); cellLeft += FrameJob.MOTION_CELL) {
        field[(cellTop / FrameJob.MOTION_CELL) * columns + cellLeft / FrameJob.MOTION_CELL] = vector;
      }
    }
  }

  /** The vector a leaf predicts from, as {@code x << 16 | (y & 0xFFFF)} in half pixels. */
  static int leafVector(final TreeNode leaf, final int globalX, final int globalY) {
    final int mode = leaf.getMode();
    final byte[] record = leaf.getRecord();
    int deltaX = 0;
    int deltaY = 0;
    if (mode == MODE_MOTION || isResidual(mode)) {
      deltaX = record[0];
      deltaY = record[1];
    } else if (mode == MODE_COMPACT) {
      deltaX = CompactRecord.deltaX(record, 0);
      deltaY = CompactRecord.deltaY(record, 0);
    }
    return MotionSearch.pack(globalX + deltaX, globalY + deltaY);
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
        coders = this.newCoders(job);
      }
      this.busyCoders.add(coders);
    }
    for (final BlockCoder coder : coders) {
      coder.bind(job);
    }
    return coders;
  }

  private BlockCoder[] newCoders(final FrameJob job) {
    final BlockCoder[] coders = {
      new BlockCoder(job, ROOT_SIZE, this.kernels.create()),
      new BlockCoder(job, ROOT_SIZE / 2, this.kernels.create()),
      new BlockCoder(job, SMALLEST_BLOCK, this.kernels.create()),
    };
    coders[1].loadFrom(coders[0]);
    coders[2].loadFrom(coders[0]);
    return coders;
  }

  /** Makes the coders of a finished frame's workers available to the next frame. */
  private void releaseCoders() {
    synchronized (this.idleCoders) {
      for (final BlockCoder[] coders : this.busyCoders) {
        for (final BlockCoder coder : coders) {
          coder.release();
        }
      }
      this.idleCoders.addAll(this.busyCoders);
      this.busyCoders.clear();
    }
  }

  private static void superblock(final FrameJob job, final BlockCoder[] coders, final int left, final int top) {
    for (int level = 0; level < BLOCK_SIZES; level++) {
      final int size = ROOT_SIZE >> level;
      final int span = ROOT_SIZE / size;
      for (int blockRow = 0; blockRow < span; blockRow++) {
        for (int blockColumn = 0; blockColumn < span; blockColumn++) {
          final int blockLeft = left + blockColumn * size;
          final int blockTop = top + blockRow * size;
          if (blockLeft < job.width() && blockTop < job.height()) {
            coders[level].code(level, (blockTop / size) * job.columns(level) + blockLeft / size, blockLeft, blockTop);
          }
        }
      }
    }
  }

  /** A chosen leaf, remembered for the verification. */
  record Leaf(int left, int top, int size, int level, int block) {}

  /** The node, its cost and its leaves, chosen bottom-up like the reference's {@code select}. */
  private record Choice(TreeNode node, double cost) {}

  private List<TreeNode> select(final FrameJob job, final int trial, final List<Leaf> leaves) {
    final List<TreeNode> roots = new ArrayList<>();
    for (int top = 0; top < job.height(); top += ROOT_SIZE) {
      for (int left = 0; left < job.width(); left += ROOT_SIZE) {
        roots.add(Preconditions.checkNotNull(this.select(job, trial, left, top, 0, leaves)).node());
      }
    }
    return roots;
  }

  private @Nullable Choice select(
    final FrameJob job,
    final int trial,
    final int left,
    final int top,
    final int level,
    final List<Leaf> leaves
  ) {
    final double lambda = job.settings().lambda();
    if (left >= job.width() || top >= job.height()) {
      return new Choice(TreeNode.leaf(MODE_SOLID, 0, new byte[CHANNELS]), lambda * OUTSIDE_BITS);
    }
    final int size = ROOT_SIZE >> level;
    final int block = (top / size) * job.columns(level) + left / size;
    final double cost = job.cost(trial, level)[block];
    if (cost == Double.POSITIVE_INFINITY) {
      // a live search did not evaluate the block
      return null;
    }
    final TreeNode leaf = TreeNode.leaf(job.mode(trial, level, block), job.quantizer(trial, level, block), job.record(trial, level, block));
    if (level == DEEPEST) {
      leaves.add(new Leaf(left, top, size, level, block));
      return new Choice(leaf, cost);
    }
    final List<Leaf> childLeaves = new ArrayList<>();
    final int half = size / 2;
    final Choice[] children = new Choice[QUARTERS];
    double splitCost = lambda * BlockCoder.INDEX_BITS;
    for (int quarter = 0; quarter < QUARTERS; quarter++) {
      final Choice child = this.select(job, trial, left + (quarter % 2) * half, top + (quarter / 2) * half, level + 1, childLeaves);
      if (child == null) {
        splitCost = Double.POSITIVE_INFINITY;
        break;
      }
      children[quarter] = child;
      splitCost += child.cost();
    }
    if (splitCost < cost) {
      leaves.addAll(childLeaves);
      return new Choice(TreeNode.split(children[0].node(), children[1].node(), children[2].node(), children[3].node()), splitCost);
    }
    leaves.add(new Leaf(left, top, size, level, block));
    return new Choice(leaf, cost);
  }

  /** The reference's trial error: the weighted squared error over the frame padded to 32-pixel blocks by replication. */
  private static long trialError(final byte[] source, final byte[] picture, final int width, final int height) {
    final int extraX = (ROOT_SIZE - (width % ROOT_SIZE)) % ROOT_SIZE;
    final int extraY = (ROOT_SIZE - (height % ROOT_SIZE)) % ROOT_SIZE;
    long sum = 0;
    for (int row = 0; row < height; row++) {
      final int weightY = row == height - 1 ? 1 + extraY : 1;
      for (int column = 0; column < width; column++) {
        final int weight = weightY * (column == width - 1 ? 1 + extraX : 1);
        final int at = (row * width + column) * CHANNELS;
        sum += (long) weight * pixelError(source, picture, at);
      }
    }
    return sum;
  }

  /**
   * Checks the kept frame of the reference's search, whose picture is the decode of its bytes: the bytes must describe
   * exactly the chosen tree, and every leaf inside the picture must decode to exactly the distortion the search measured
   * for it. The leaves are measured on the workers, a pass over the whole picture that would take one thread long.
   */
  static void check(
    final FrameJob job,
    final int trial,
    final byte[] data,
    final byte[] picture,
    final List<Leaf> leaves,
    final List<TreeNode> roots,
    final Workers workers
  ) {
    checkTree(data, roots, workers);
    checkLeaves(job, trial, picture, leaves, workers);
  }

  /** Checks that a frame's bytes describe exactly the chosen tree, which the parser must accept. */
  private static void checkTree(final byte[] data, final List<TreeNode> roots, final Workers workers) {
    checkTree(parseChosen(data), roots, workers);
  }

  /** Checks that a written and parsed frame describes the chosen trees; see {@link #checkTree(byte[], List, Workers)}. */
  private static void checkTree(final Mcv2Frame frame, final List<TreeNode> roots, final Workers workers) {
    final List<TreeNode> written = new ArrayList<>(roots.size());
    final List<TreeNode> expected = new ArrayList<>(roots.size());
    try {
      written.addAll(TreeReader.roots(frame, workers));
      for (final TreeNode root : roots) {
        expected.add(TreeReader.withPalettes(root, ROOT_SIZE));
      }
    } catch (final Mcv2Exception exception) {
      throw new IllegalStateException("The encoder wrote a frame the parser rejects", exception);
    }
    if (!written.equals(expected)) {
      throw new IllegalStateException("MCV2 encoder and serializer disagree about the tree");
    }
  }

  /**
   * Checks that every leaf inside the picture has exactly the distortion the search measured for it, on the workers;
   * the first leaf that disagrees, in the order of the list, is reported.
   */
  private static void checkLeaves(
    final FrameJob job,
    final int trial,
    final byte[] picture,
    final List<Leaf> leaves,
    final Workers workers
  ) {
    final int width = job.width();
    final int height = job.height();
    final AtomicInteger first = new AtomicInteger(leaves.size());
    workers.forEach(
      leaves.size(),
      () -> first,
      (_, index) -> {
        final Leaf leaf = leaves.get(index);
        if (leaf.left() + leaf.size() > width || leaf.top() + leaf.size() > height) {
          return;
        }
        long distortion = 0;
        for (int row = leaf.top(); row < leaf.top() + leaf.size(); row++) {
          for (int column = leaf.left(); column < leaf.left() + leaf.size(); column++) {
            distortion += pixelError(job.source(), picture, (row * width + column) * CHANNELS);
          }
        }
        if (distortion != job.distortion(trial, leaf.level(), leaf.block())) {
          first.accumulateAndGet(index, Math::min);
        }
      }
    );
    final int failed = first.get();
    if (failed < leaves.size()) {
      final Leaf leaf = leaves.get(failed);
      throw new IllegalStateException("MCV2 encoder/decoder disagreement at " + leaf.left() + "," + leaf.top() + " size " + leaf.size());
    }
  }

  /** The error of one pixel of two pictures of bytes, in the units of {@link Reconstruction#DISTORTION_SCALE}. */
  private static int pixelError(final byte[] source, final byte[] picture, final int at) {
    return Reconstruction.pixelError(
      (source[at] & 0xFF) - (picture[at] & 0xFF),
      (source[at + 1] & 0xFF) - (picture[at + 1] & 0xFF),
      (source[at + 2] & 0xFF) - (picture[at + 2] & 0xFF)
    );
  }
}
