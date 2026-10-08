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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.BLOCK_SIZES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.CHANNELS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.CHECKPOINT_GROUPS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.COMPACT_DC;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.COMPACT_GRID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.COMPACT_GRID_Y;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.DEFAULT_COLOR_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.DIMENSIONS_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.FRAME_ID_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.GROUP_ROOTS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.HEADER_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.KEYFRAME;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MAGIC;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MAX_CHANNEL;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MAX_DIMENSION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MAX_FRAME_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MAX_QUANTIZER;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MAX_U32;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_COMPACT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_MASK;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_PALETTE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_PATTERN;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_SKIP;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_SOLID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_SPLIT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.PAYLOAD_START_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.QUANTIZER_SHIFT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.QUARTERS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.REFERENCE_ID_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.ROOT_SIZE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.SMALLEST_BLOCK;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.TOTAL_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.VERSION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.WALK_SPAN;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.compactX;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.compactY;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.follows;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.pack565;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.patternSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.putU16;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.putU32;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.recordSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.signed;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.sizeIndex;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.unpack565;

import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The version 3 live encoder. Each instance encodes one stream on a caller-owned pool. */
public final class MCV2 {

  // Public API owns stream state; begin and finish may overlap by one frame.
  public record Settings(double lambda, int keyInterval, boolean fast, boolean adaptive) {
    public static final Settings DEFAULT = new Settings(72, 120, false, false);
    public static final Settings FAST = new Settings(55, 120, true, false);
    public static final Settings ADAPTIVE = new Settings(72, 120, false, true);

    public Settings {
      Preconditions.checkArgument(lambda >= 0 && Double.isFinite(lambda), "Lambda must be finite and non-negative");
      Preconditions.checkArgument(keyInterval > 0, "Key interval must be positive");
    }

    public Settings withLambda(final double value) {
      return new Settings(value, this.keyInterval, this.fast, this.adaptive);
    }
  }

  public record Stats(int bytes, boolean keyframe, int leaves, long nanoseconds, double lambda) {}

  public static final class Encoded {

    private final byte[] data;
    private final Stats stats;

    private Encoded(final byte[] data, final Stats stats) {
      this.data = data;
      this.stats = stats;
    }

    public byte[] getData() {
      return this.data;
    }

    public Stats getStats() {
      return this.stats;
    }
  }

  public static final class Pending {

    private final MCV2 owner;
    private final byte[] data;
    private final byte[] picture;
    private final byte[] predictFrom;
    private final long referenceId;
    private final boolean keyframe;
    private final int leaves;
    private final double lambda;
    private final long nanoseconds;
    private volatile boolean finished;

    private Pending(
      final MCV2 owner,
      final byte[] data,
      final byte[] picture,
      final byte[] predictFrom,
      final long referenceId,
      final boolean keyframe,
      final int leaves,
      final double lambda,
      final long nanoseconds
    ) {
      this.owner = owner;
      this.data = data;
      this.picture = picture;
      this.predictFrom = predictFrom;
      this.referenceId = referenceId;
      this.keyframe = keyframe;
      this.leaves = leaves;
      this.lambda = lambda;
      this.nanoseconds = nanoseconds;
    }

    public boolean isKeyframe() {
      return this.keyframe;
    }
  }

  private static final String STOPPED = "The encoder stopped after a frame failed its verification";
  private static final int LIMIT_RETRIES = 4;
  private static final int MOTION_RANGE = 24;
  private static final double SCENE_THRESHOLD = 45;
  private static final double INDEX_BITS = 12;
  private static final double DISTORTION_SCALE = 96.0;
  private static final int OUTSIDE_BITS = 56;
  private static final int GRID = 4;
  private static final int GRID_NODES = GRID * GRID;
  private static final int MAX_RECORD = 134;
  private static final int NO_VECTOR = 0x80008000;
  private static final int COARSEST_QUANTIZER = 4;
  private static final int PALETTE_COLORS = 2;
  private static final byte[] NONE = new byte[0];
  private Settings settings;
  private final Workers workers;
  private final boolean shouldVerify;
  private long frameBudget;
  private int frameLimit;
  private byte @Nullable [] reference;
  private long referenceId;
  private int width;
  private int height;
  private long lastFrameId = -1;
  private int framesSinceKey;
  private int @Nullable [] motion;
  private boolean @Nullable [] splitBefore;
  private @Nullable Buffers buffers;
  private final ArrayDeque<BlockCoder[]> idleCoders = new ArrayDeque<>();
  private final List<BlockCoder[]> busyCoders = new ArrayList<>();
  private final MotionLambda motionLambda = new MotionLambda();
  private boolean moving;
  private volatile @Nullable Stats stats;
  private volatile boolean failed;
  private @Nullable Pending newer;
  private @Nullable Pending older;
  private final Supplier<Kernels> kernels;

  public MCV2(final Settings settings, final ForkJoinPool pool, final int threads, final boolean verify) {
    this.settings = Preconditions.checkNotNull(settings, "Settings must not be null");
    this.workers = new Workers(Preconditions.checkNotNull(pool, "Pool must not be null"), threads);
    this.shouldVerify = verify;
    this.kernels = JavaKernels::new;
    this.framesSinceKey = settings.keyInterval();
  }

  public void setFrameBudget(final long nanoseconds) {
    Preconditions.checkArgument(nanoseconds >= 0, "The frame budget must not be negative");
    this.frameBudget = nanoseconds;
  }

  public void setFrameLimit(final int bytes) {
    Preconditions.checkArgument(bytes >= 0, "The frame limit must not be negative");
    this.frameLimit = bytes;
  }

  public void requestKeyframe() {
    this.framesSinceKey = Integer.MAX_VALUE;
  }

  public void switchTo(final Settings next) {
    this.settings = Preconditions.checkNotNull(next, "Settings must not be null");
  }

  public Settings getSettings() {
    return this.settings;
  }

  public @Nullable Stats getStats() {
    return this.stats;
  }

  public byte @Nullable [] getReference() {
    final byte[] previous = this.reference;
    return previous == null ? null : previous.clone();
  }

  public byte[] encode(final byte[] rgb, final int width, final int height, final long frameId) {
    return this.finish(this.begin(rgb, width, height, frameId)).getData();
  }

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
    final boolean predictable =
      previous != null && width == this.width && height == this.height && this.framesSinceKey < this.settings.keyInterval();
    this.moving = this.motionLambda.moving(this.moving, 8, 6);
    final boolean adaptiveFast = this.settings.adaptive() && this.moving;
    final boolean fast = this.settings.fast() || adaptiveFast;
    final double base = adaptiveFast ? fastLambda(this.settings.lambda()) : this.settings.lambda();
    double lambda = Math.min(this.motionLambda.lambda(base), Double.MAX_VALUE);
    final boolean cut = predictable && sceneCut(rgb, Preconditions.checkNotNull(previous), width, height, this.workers);
    final boolean keyframe = !predictable || cut;
    this.motionLambda.observe(rgb, width, height, cut, this.workers);
    final byte[] predictFrom = keyframe ? NONE : Preconditions.checkNotNull(previous);
    final long predictsId = keyframe ? frameId : this.referenceId;
    Buffers buffers = this.buffers;
    if (buffers == null || buffers.width != width || buffers.height != height) {
      buffers = new Buffers(width, height);
      this.buffers = buffers;
    }
    if (!keyframe) {
      half(predictFrom, width, height, this.workers, buffers.half);
      if (!fast) {
        half(buffers.half, (width + 1) / 2, (height + 1) / 2, this.workers, buffers.quarter);
      }
    }
    final Pending verifying = this.newer;
    final byte[] picture = buffers.spare(
      predictFrom,
      verifying == null ? NONE : verifying.picture,
      verifying == null ? NONE : verifying.predictFrom
    );
    final boolean[] history = this.splitBefore == null ? new boolean[buffers.roots] : this.splitBefore;
    final boolean[] before = history.length == buffers.roots ? history : new boolean[buffers.roots];
    final FrameState frame = new FrameState(
      rgb,
      predictFrom,
      width,
      height,
      keyframe,
      fast,
      lambda,
      keyframe ? null : this.motion,
      buffers,
      picture
    );
    SearchResult searched = this.search(frame, before, started, false);
    byte[] data = write(width, height, keyframe, searched.roots, frameId, predictsId);
    final int limit = this.frameLimit == 0 ? MAX_FRAME_BYTES : Math.min(this.frameLimit, MAX_FRAME_BYTES);
    for (int retry = 0; (data == null || data.length > limit) && retry < LIMIT_RETRIES; retry++) {
      lambda = Math.min(lambda * 2, Double.MAX_VALUE);
      frame.lambda = lambda;
      searched = this.search(frame, before, started, false);
      data = write(width, height, keyframe, searched.roots, frameId, predictsId);
    }
    if (data == null || data.length > limit) {
      searched = this.search(frame, before, started, true);
      data = Preconditions.checkNotNull(
        write(width, height, keyframe, searched.roots, frameId, predictsId),
        "Trivial frame exceeds the format bound"
      );
    }
    final Pending pending = new Pending(
      this,
      data,
      picture,
      predictFrom,
      predictsId,
      keyframe,
      searched.leaves,
      lambda,
      System.nanoTime() - started
    );
    this.reference = picture;
    this.referenceId = frameId;
    this.motion = searched.motion;
    this.splitBefore = searched.splits;
    this.width = width;
    this.height = height;
    this.lastFrameId = frameId;
    this.framesSinceKey = keyframe ? 1 : this.framesSinceKey + 1;
    this.older = this.newer;
    this.newer = pending;
    return pending;
  }

  @SuppressWarnings("ReferenceEquality")
  public Encoded finish(final Pending pending) {
    Preconditions.checkNotNull(pending, "Frame must not be null");
    Preconditions.checkState(!this.failed, STOPPED);
    Preconditions.checkArgument(pending.owner == this, "The pending frame belongs to another encoder");
    Preconditions.checkState(!pending.finished, "The frame is finished already");
    Preconditions.checkState(
      pending == this.older || (pending == this.newer && (this.older == null || this.older.finished)),
      "Finish frames in creation order"
    );
    final long started = System.nanoTime();
    if (this.shouldVerify) {
      try {
        this.verify(pending);
      } catch (final RuntimeException exception) {
        this.failed = true;
        throw exception;
      }
    }
    pending.finished = true;
    final Stats result = new Stats(
      pending.data.length,
      pending.keyframe,
      pending.leaves,
      pending.nanoseconds + System.nanoTime() - started,
      pending.lambda
    );
    this.stats = result;
    return new Encoded(pending.data, result);
  }

  public static void installNatives(final Path folder, final String mode) {
    Preconditions.checkNotNull(folder, "Native folder must not be null");
    Preconditions.checkArgument(mode.equals("auto") || mode.equals("off"), "Native mode must be auto or off");
  }

  public static String describeNatives() {
    return "Java";
  }

  // Frame analysis keeps source motion independent of reconstruction quality.
  private static double fastLambda(final double lambda) {
    final double scaled = (lambda * 55) / 72;
    return Double.isFinite(scaled) ? scaled : lambda * (55.0 / 72);
  }

  private static boolean sceneCut(final byte[] source, final byte[] reference, final int width, final int height, final Workers workers) {
    final int columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
    final int superblocks = columns * ((height + ROOT_SIZE - 1) / ROOT_SIZE);
    final long[] changes = new long[superblocks];
    workers.forEach(
      superblocks,
      () -> changes,
      (partial, index) -> {
        final int left = (index % columns) * ROOT_SIZE;
        final int top = (index / columns) * ROOT_SIZE;
        long change = 0;
        for (int row = 2; row < ROOT_SIZE; row += 4) {
          final int sourceRow = Math.min(top + row, height - 1);
          for (int column = 2; column < ROOT_SIZE; column += 4) {
            final int sourceColumn = Math.min(left + column, width - 1);
            final int at = (sourceRow * width + sourceColumn) * CHANNELS;
            final int luma = (source[at] & 255) + 2 * (source[at + 1] & 255) + (source[at + 2] & 255);
            final int predicted = (reference[at] & 255) + 2 * (reference[at + 1] & 255) + (reference[at + 2] & 255);
            change += Math.abs(luma - predicted);
          }
        }
        partial[index] = change;
      }
    );
    long change = 0;
    for (final long value : changes) {
      change += value;
    }
    return (double) change / (superblocks * 64L * 4) > SCENE_THRESHOLD;
  }

  private static final class MotionLambda {

    static final double KNEE = 4.6;

    static final double EXPONENT = 0.79;

    static final double MAX_RAISE = 4.0;

    static final double SMOOTHING = 1.0 / 16;

    private static final int SAMPLING = 4;

    private static final int BAND_ROWS = 16;

    private static final int BAND_SAMPLES = 16_384;

    private static final int BOX_AREA = 9;

    private static final int LUMA_SCALE = 4;

    private int[] previous = new int[0];

    private int[] spare = new int[0];

    private int[] across = new int[0];

    private int columns;

    private int rows;

    private double motion = Double.NaN;

    double lambda(final double base) {
      return base * raise(this.motion);
    }

    boolean moving(final boolean was, final double enter, final double leave) {
      return moving(this.average(), was, enter, leave);
    }

    static boolean moving(final double motion, final boolean was, final double enter, final double leave) {
      if (motion > enter) {
        return true;
      }
      return was && !(motion < leave);
    }

    static double raise(final double motion) {
      if (!(motion > KNEE)) {
        return 1;
      }
      return Math.min(MAX_RAISE, Math.pow(motion / KNEE, EXPONENT));
    }

    void observe(final byte[] rgb, final int width, final int height, final boolean startsOver, final Workers workers) {
      final int columns = (width + SAMPLING - 1) / SAMPLING;
      final int rows = (height + SAMPLING - 1) / SAMPLING;
      if (this.across.length != columns * rows) {
        this.across = new int[columns * rows];
        this.spare = new int[columns * rows];
      }
      final int[] current = blurredLuma(rgb, width, height, workers, this.across, this.spare);
      if (startsOver || columns != this.columns || rows != this.rows) {
        this.motion = Double.NaN;
      } else {
        this.add(temporalInformation(current, this.previous, workers));
      }
      this.spare = this.previous.length == current.length ? this.previous : new int[current.length];
      this.previous = current;
      this.columns = columns;
      this.rows = rows;
    }

    void add(final double information) {
      this.motion = Double.isNaN(this.motion) ? information : this.motion + SMOOTHING * (information - this.motion);
    }

    double average() {
      return this.motion;
    }

    static int[] blurredLuma(
      final byte[] rgb,
      final int width,
      final int height,
      final Workers workers,
      final int[] across,
      final int[] blurred
    ) {
      final int columns = (width + SAMPLING - 1) / SAMPLING;
      final int rows = (height + SAMPLING - 1) / SAMPLING;
      final int bands = (rows + BAND_ROWS - 1) / BAND_ROWS;

      workers.forEach(
        bands,
        () -> across,
        (sums, band) -> {
          for (int row = band * BAND_ROWS; row < Math.min(rows, (band + 1) * BAND_ROWS); row++) {
            final int line = row * SAMPLING * width;
            int left = luma(rgb, line);
            int middle = left;
            for (int column = 0; column < columns; column++) {
              final int right = column + 1 < columns ? luma(rgb, line + (column + 1) * SAMPLING) : middle;
              sums[row * columns + column] = left + middle + right;
              left = middle;
              middle = right;
            }
          }
        }
      );
      workers.forEach(
        bands,
        () -> blurred,
        (out, band) -> {
          for (int row = band * BAND_ROWS; row < Math.min(rows, (band + 1) * BAND_ROWS); row++) {
            final int above = Math.max(row - 1, 0) * columns;
            final int at = row * columns;
            final int below = Math.min(row + 1, rows - 1) * columns;
            for (int column = 0; column < columns; column++) {
              out[at + column] = across[above + column] + across[at + column] + across[below + column];
            }
          }
        }
      );
      return blurred;
    }

    private static int luma(final byte[] rgb, final int pixel) {
      final int at = pixel * CHANNELS;
      return (rgb[at] & 0xFF) + 2 * (rgb[at + 1] & 0xFF) + (rgb[at + 2] & 0xFF);
    }

    static double temporalInformation(final int[] current, final int[] previous, final Workers workers) {
      final int bands = (current.length + BAND_SAMPLES - 1) / BAND_SAMPLES;
      final long[] sums = new long[bands];
      workers.forEach(
        bands,
        () -> sums,
        (partial, band) -> {
          long sum = 0;
          for (int sample = band * BAND_SAMPLES; sample < Math.min(current.length, (band + 1) * BAND_SAMPLES); sample++) {
            sum += Math.abs(current[sample] - previous[sample]);
          }
          partial[band] = sum;
        }
      );
      long sum = 0;
      for (final long partial : sums) {
        sum += partial;
      }
      return (double) sum / ((long) current.length * LUMA_SCALE * BOX_AREA);
    }
  }

  // Motion search preserves the seeded diamond and the reference pyramid.
  private static final int[][] DIRECTIONS = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
  private static final int SAMPLED = 4;
  private static final int[][] SAMPLES = { { 1, 3, 5, 7 }, { 2, 6, 10, 14 }, { 4, 12, 20, 28 } };

  private static int packMotion(final int vectorX, final int vectorY) {
    return (vectorX << 16) | (vectorY & 65535);
  }

  private static int motionX(final int vector) {
    return vector >> 16;
  }

  private static int motionY(final int vector) {
    return (short) vector;
  }

  private static int seededMotion(
    final byte[] reference,
    final int width,
    final int height,
    final int[] source,
    final int blockLeft,
    final int blockTop,
    final int size,
    final int range,
    final int[] seeds
  ) {
    final int[] samples = SAMPLES[sizeIndex(size)];
    final int lowX = 0 - range;
    final int highX = 0 + range;
    final int lowY = 0 - range;
    final int highY = 0 + range;
    int bestX = 0;
    int bestY = 0;
    long best = motionCost(reference, width, height, source, blockLeft, blockTop, size, samples, bestX, bestY);
    for (int seedIndex = 0; seedIndex < seeds.length; seedIndex++) {
      final int seedX = Math.min(Math.max(motionX(seeds[seedIndex]), lowX), highX);
      final int seedY = Math.min(Math.max(motionY(seeds[seedIndex]), lowY), highY);

      boolean seen = seedX == 0 && seedY == 0;
      for (int earlierIndex = 0; earlierIndex < seedIndex && !seen; earlierIndex++) {
        seen =
          seedX == Math.min(Math.max(motionX(seeds[earlierIndex]), lowX), highX) &&
          seedY == Math.min(Math.max(motionY(seeds[earlierIndex]), lowY), highY);
      }
      if (!seen) {
        final long error = motionCost(reference, width, height, source, blockLeft, blockTop, size, samples, seedX, seedY);
        if (error < best) {
          best = error;
          bestX = seedX;
          bestY = seedY;
        }
      }
    }

    for (int steps = 0; steps < 2 * range; steps++) {
      final int centreX = bestX;
      final int centreY = bestY;
      for (int directionIndex = 0; directionIndex < DIRECTIONS.length; directionIndex++) {
        final int candidateX = Math.min(Math.max(centreX + DIRECTIONS[directionIndex][0], lowX), highX);
        final int candidateY = Math.min(Math.max(centreY + DIRECTIONS[directionIndex][1], lowY), highY);
        final long error = motionCost(reference, width, height, source, blockLeft, blockTop, size, samples, candidateX, candidateY);
        if (error < best) {
          best = error;
          bestX = candidateX;
          bestY = candidateY;
        }
      }
      if (bestX == centreX && bestY == centreY) {
        break;
      }
    }
    return packMotion(bestX, bestY);
  }

  private static long motionCost(
    final byte[] reference,
    final int width,
    final int height,
    final int[] source,
    final int blockLeft,
    final int blockTop,
    final int size,
    final int[] samples,
    final int motionX,
    final int motionY
  ) {
    final int left = blockLeft + samples[0] + motionX;
    final int right = blockLeft + samples[SAMPLED - 1] + motionX;
    final int top = blockTop + samples[0] + motionY;
    final int bottom = blockTop + samples[SAMPLED - 1] + motionY;
    if (left >= 0 && top >= 0 && right < width && bottom < height) {
      long sum = 0;
      for (int sampleRow = 0; sampleRow < SAMPLED; sampleRow++) {
        final int row = (blockTop + samples[sampleRow] + motionY) * width + blockLeft + motionX;
        final int line = samples[sampleRow] * size;
        for (int sampleColumn = 0; sampleColumn < SAMPLED; sampleColumn++) {
          final int at = (row + samples[sampleColumn]) * CHANNELS;
          final int target = (line + samples[sampleColumn]) * CHANNELS;
          sum += Math.abs(4 * (reference[at] & 255) - 4 * source[target]);
          sum += Math.abs(4 * (reference[at + 1] & 255) - 4 * source[target + 1]);
          sum += Math.abs(4 * (reference[at + 2] & 255) - 4 * source[target + 2]);
        }
      }
      return sum;
    }
    long sum = 0;
    for (int sampleRow = 0; sampleRow < SAMPLED; sampleRow++) {
      final int row = Math.min(Math.max(blockTop + samples[sampleRow] + motionY, 0), height - 1);
      for (int sampleColumn = 0; sampleColumn < SAMPLED; sampleColumn++) {
        final int column = Math.min(Math.max(blockLeft + samples[sampleColumn] + motionX, 0), width - 1);
        final int at = (row * width + column) * CHANNELS;
        final int target = (samples[sampleRow] * size + samples[sampleColumn]) * CHANNELS;
        for (int channel = 0; channel < CHANNELS; channel++) {
          sum += Math.abs(4 * (reference[at + channel] & 255) - 4 * source[target + channel]);
        }
      }
    }
    return sum;
  }

  private static byte[] half(final byte[] picture, final int width, final int height, final Workers workers, final byte[] out) {
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

  // Block search keeps the first strictly cheapest candidate and bounded top-down splits.
  private static final class Buffers {

    private final int width;
    private final int height;
    private final int roots;
    private final int[] columns = new int[BLOCK_SIZES];
    private final double[][] costs = new double[BLOCK_SIZES][];
    private final byte[][] modes = new byte[BLOCK_SIZES][];
    private final byte[][] quantizers = new byte[BLOCK_SIZES][];
    private final byte[][] records = new byte[BLOCK_SIZES][];
    private final byte[][] lengths = new byte[BLOCK_SIZES][];
    private final long[][] distortions = new long[BLOCK_SIZES][];
    private final byte[][] levels;
    private final byte[][] pictures;
    private final byte[] half;
    private final byte[] quarter;

    private Buffers(final int width, final int height) {
      this.width = width;
      this.height = height;
      for (int level = 0; level < BLOCK_SIZES; level++) {
        final int size = ROOT_SIZE >> level;
        this.columns[level] = (width + size - 1) / size;
        final int blocks = this.columns[level] * ((height + size - 1) / size);
        this.costs[level] = new double[blocks];
        this.modes[level] = new byte[blocks];
        this.quantizers[level] = new byte[blocks];
        this.records[level] = new byte[blocks * recordSize(MODE_PALETTE, size)];
        this.lengths[level] = new byte[blocks];
        this.distortions[level] = new long[blocks];
      }
      this.roots = this.costs[0].length;
      this.levels = new byte[BLOCK_SIZES][width * height * CHANNELS];
      this.pictures = new byte[3][width * height * CHANNELS];
      final int halfWidth = (width + 1) / 2;
      final int halfHeight = (height + 1) / 2;
      this.half = new byte[halfWidth * halfHeight * CHANNELS];
      this.quarter = new byte[((halfWidth + 1) / 2) * ((halfHeight + 1) / 2) * CHANNELS];
    }

    @SuppressWarnings("ReferenceEquality")
    private byte[] spare(final byte[] reference, final byte[] verifying, final byte[] verifyingReference) {
      for (int index = 0; index < this.pictures.length - 1; index++) {
        final byte[] picture = this.pictures[index];
        if (picture != reference && picture != verifying && picture != verifyingReference) {
          return picture;
        }
      }
      return this.pictures[this.pictures.length - 1];
    }
  }

  private static final class FrameState {

    private final byte[] source;
    private final byte[] reference;
    private final int width;
    private final int height;
    private final boolean keyframe;
    private final boolean fast;
    private double lambda;
    private final int @Nullable [] previousMotion;
    private final Buffers buffers;
    private final byte[] picture;

    private FrameState(
      final byte[] source,
      final byte[] reference,
      final int width,
      final int height,
      final boolean keyframe,
      final boolean fast,
      final double lambda,
      final int @Nullable [] previousMotion,
      final Buffers buffers,
      final byte[] picture
    ) {
      this.source = source;
      this.reference = reference;
      this.width = width;
      this.height = height;
      this.keyframe = keyframe;
      this.fast = fast;
      this.lambda = lambda;
      this.previousMotion = previousMotion;
      this.buffers = buffers;
      this.picture = picture;
    }

    private int previousMotion(final int column, final int row) {
      final int[] field = this.previousMotion;
      if (field == null) {
        return 0;
      }
      final int cellColumn = Math.min(Math.max(column, 0), this.width - 1) / SMALLEST_BLOCK;
      final int cellRow = Math.min(Math.max(row, 0), this.height - 1) / SMALLEST_BLOCK;
      return field[cellRow * ((this.width + SMALLEST_BLOCK - 1) / SMALLEST_BLOCK) + cellColumn];
    }

    private void set(
      final int level,
      final int block,
      final double cost,
      final int mode,
      final int quantizer,
      final byte[] record,
      final int length,
      final long distortion
    ) {
      final Buffers buffers = this.buffers;
      buffers.costs[level][block] = cost;
      buffers.modes[level][block] = (byte) mode;
      buffers.quantizers[level][block] = (byte) quantizer;
      final int stride = recordSize(MODE_PALETTE, ROOT_SIZE >> level);
      System.arraycopy(record, 0, buffers.records[level], block * stride, length);
      buffers.lengths[level][block] = (byte) length;
      buffers.distortions[level][block] = distortion;
    }
  }

  private static final class SearchResult {

    private final List<TreeNode> roots;
    private final int leaves;
    private final int[] motion;
    private final boolean[] splits;

    private SearchResult(final List<TreeNode> roots, final int leaves, final int[] motion, final boolean[] splits) {
      this.roots = roots;
      this.leaves = leaves;
      this.motion = motion;
      this.splits = splits;
    }
  }

  private record ChosenLeaf(int left, int top, int size, int level) {}

  private record Choice(TreeNode node, double cost) {}

  private SearchResult search(final FrameState frame, final boolean[] before, final long started, final boolean trivial) {
    final Buffers buffers = frame.buffers;
    for (final double[] costs : buffers.costs) {
      Arrays.fill(costs, Double.POSITIVE_INFINITY);
    }
    final int columns = buffers.columns[0];
    final TreeNode[] roots = new TreeNode[buffers.roots];
    final int[] leafCounts = new int[buffers.roots];
    final int[] motion = new int[((frame.width + 7) / 8) * ((frame.height + 7) / 8)];
    final boolean[] splits = new boolean[buffers.roots];
    final double[] thresholds = { 150 * frame.lambda, (frame.fast ? 600 : 300) * frame.lambda, 0 };
    final double steady = (frame.fast ? 900 : 450) * frame.lambda;
    try {
      this.workers.forEach(
        buffers.roots,
        () -> this.coders(frame),
        (coders, index) -> {
          final int left = (index % columns) * ROOT_SIZE;
          final int top = (index / columns) * ROOT_SIZE;
          final double threshold = !frame.keyframe && !before[index] ? steady : thresholds[0];
          final boolean late = trivial || (this.frameBudget > 0 && System.nanoTime() - started >= this.frameBudget);
          for (final BlockCoder coder : coders) {
            coder.hurried = late;
          }
          descend(frame, coders, 0, left, top, NO_VECTOR, threshold, thresholds);
          final List<ChosenLeaf> chosen = new ArrayList<>();
          final TreeNode root = Preconditions.checkNotNull(choose(frame, left, top, 0, chosen)).node();
          roots[index] = withPatterns(root, ROOT_SIZE);
          splits[index] = root.isSplit();
          fillMotion(motion, frame.width, frame.height, root, left, top, ROOT_SIZE);
          for (final ChosenLeaf leaf : chosen) {
            assemble(frame, leaf);
          }
          leafCounts[index] = chosen.size();
        }
      );
    } finally {
      this.releaseCoders();
    }
    int leaves = 0;
    for (final int count : leafCounts) {
      leaves += count;
    }
    return new SearchResult(List.of(roots), leaves, motion, splits);
  }

  private static double descend(
    final FrameState frame,
    final BlockCoder[] coders,
    final int level,
    final int left,
    final int top,
    final int parent,
    final double threshold,
    final double[] thresholds
  ) {
    if (left >= frame.width || top >= frame.height) {
      return frame.lambda * OUTSIDE_BITS;
    }
    final int size = ROOT_SIZE >> level;
    final int block = (top / size) * frame.buffers.columns[level] + left / size;
    final BlockCoder coder = coders[level];
    coder.code(level, block, left, top, parent);
    final double cost = frame.buffers.costs[level][block];
    if (level == BLOCK_SIZES - 1 || coder.skipped || cost <= threshold) {
      return cost;
    }
    final int half = size / 2;
    double splitCost = frame.lambda * INDEX_BITS;
    for (int corner = 0; corner < QUARTERS && splitCost < cost; corner++) {
      splitCost += descend(
        frame,
        coders,
        level + 1,
        left + (corner % 2) * half,
        top + (corner / 2) * half,
        coder.localVector,
        thresholds[level + 1],
        thresholds
      );
    }
    return Math.min(cost, splitCost);
  }

  private static @Nullable Choice choose(
    final FrameState frame,
    final int left,
    final int top,
    final int level,
    final List<ChosenLeaf> leaves
  ) {
    if (left >= frame.width || top >= frame.height) {
      return new Choice(TreeNode.leaf(MODE_SOLID, 0, new byte[CHANNELS]), frame.lambda * OUTSIDE_BITS);
    }
    final int size = ROOT_SIZE >> level;
    final int block = (top / size) * frame.buffers.columns[level] + left / size;
    final double cost = frame.buffers.costs[level][block];
    if (cost == Double.POSITIVE_INFINITY) {
      return null;
    }
    final int at = block * recordSize(MODE_PALETTE, size);
    final byte[] record = Arrays.copyOfRange(frame.buffers.records[level], at, at + (frame.buffers.lengths[level][block] & 255));
    final TreeNode leaf = TreeNode.leaf(frame.buffers.modes[level][block], frame.buffers.quantizers[level][block], record);
    if (level == BLOCK_SIZES - 1) {
      leaves.add(new ChosenLeaf(left, top, size, level));
      return new Choice(leaf, cost);
    }
    final List<ChosenLeaf> childLeaves = new ArrayList<>();
    final Choice[] children = new Choice[QUARTERS];
    final int half = size / 2;
    double splitCost = frame.lambda * INDEX_BITS;
    for (int corner = 0; corner < QUARTERS; corner++) {
      final Choice child = choose(frame, left + (corner % 2) * half, top + (corner / 2) * half, level + 1, childLeaves);
      if (child == null) {
        splitCost = Double.POSITIVE_INFINITY;
        break;
      }
      children[corner] = child;
      splitCost += child.cost();
    }
    if (splitCost < cost) {
      leaves.addAll(childLeaves);
      return new Choice(TreeNode.split(children[0].node(), children[1].node(), children[2].node(), children[3].node()), splitCost);
    }
    leaves.add(new ChosenLeaf(left, top, size, level));
    return new Choice(leaf, cost);
  }

  private BlockCoder[] coders(final FrameState frame) {
    BlockCoder[] coders;
    synchronized (this.idleCoders) {
      coders = this.idleCoders.poll();
      if (coders == null) {
        coders = new BlockCoder[] {
          new BlockCoder(frame, ROOT_SIZE, this.kernels.get()),
          new BlockCoder(frame, ROOT_SIZE / 2, this.kernels.get()),
          new BlockCoder(frame, SMALLEST_BLOCK, this.kernels.get()),
        };
        coders[1].root = coders[0];
        coders[2].root = coders[0];
      }
      this.busyCoders.add(coders);
    }
    for (final BlockCoder coder : coders) {
      coder.frame = frame;
    }
    return coders;
  }

  private void releaseCoders() {
    synchronized (this.idleCoders) {
      for (final BlockCoder[] coders : this.busyCoders) {
        for (final BlockCoder coder : coders) {
          coder.kernels.forgetArrays();
        }
      }
      this.idleCoders.addAll(this.busyCoders);
      this.busyCoders.clear();
    }
  }

  private static final class BlockCoder {

    private FrameState frame;
    private final int size;
    private final int count;
    private final int[] source;
    private final float[] ycocg;
    private final float[] target;
    private final int[] zeroPrediction;
    private final int[] localPrediction;
    private final int[] recon;
    private final int[] best;
    private final Kernels kernels;
    private final byte[] record = new byte[MAX_RECORD];
    private final byte[] palette = new byte[MAX_RECORD];
    private final float[] fit = new float[GRID_NODES + 2];
    private final float[] lumaGrid = new float[GRID_NODES];
    private final int[] colors = new int[2 * CHANNELS];
    private final byte[] selectors;
    private final int[] seeds = new int[6];
    private final int[] halfSeeds = new int[6];
    private final int[] quarterSeeds = new int[6];
    private final int[] halfSource;
    private final int[] quarterSource;
    private final float[] clusters = new float[2 * CHANNELS];
    private final float[] means = new float[CHANNELS];
    private int level;
    private int block;
    private double rate;
    private boolean skipped;
    private boolean hurried;
    private @Nullable BlockCoder root;
    private int left;
    private int top;
    private int localVector = NO_VECTOR;
    private boolean clustered;
    private boolean ycocgLoaded;
    private boolean meansLoaded;
    private boolean gridLoaded;

    private BlockCoder(final FrameState frame, final int size, final Kernels kernels) {
      this.frame = frame;
      this.size = size;
      this.count = size * size;
      this.kernels = kernels;
      this.source = new int[this.count * CHANNELS];
      this.ycocg = new float[this.count * CHANNELS];
      this.target = new float[this.count * CHANNELS];
      this.zeroPrediction = new int[this.count * CHANNELS];
      this.localPrediction = new int[this.count * CHANNELS];
      this.recon = new int[this.count * CHANNELS];
      this.best = new int[this.count * CHANNELS];
      this.selectors = new byte[this.count];
      this.halfSource = new int[((size * size) / 4) * CHANNELS];
      this.quarterSource = new int[((size * size) / 16) * CHANNELS];
    }

    private void code(final int level, final int block, final int left, final int top, final int parent) {
      this.level = level;
      this.block = block;
      this.left = left;
      this.top = top;
      this.clustered = false;
      this.ycocgLoaded = false;
      this.skipped = false;
      this.localVector = NO_VECTOR;
      this.loadSource();
      this.evaluate(parent);
      final byte[] picture = this.frame.buffers.levels[level];
      final int right = Math.min(this.size, this.frame.width - left);
      final int bottom = Math.min(this.size, this.frame.height - top);
      for (int row = 0; row < bottom; row++) {
        final int from = row * this.size * CHANNELS;
        final int to = ((top + row) * this.frame.width + left) * CHANNELS;
        for (int offset = 0; offset < right * CHANNELS; offset++) {
          picture[to + offset] = (byte) this.best[from + offset];
        }
      }
    }

    private void evaluate(final int parent) {
      if (!this.frame.keyframe) {
        this.predict(0, this.zeroPrediction);
        this.eligible(0);
        this.kernels.predicted(this.zeroPrediction, this.size, this.recon);
        this.score(MODE_SKIP, 0, 0);
        final long skipDistortion = this.kernels.distortion();
        if (this.hurried || this.cost() <= (this.frame.fast ? 60 : 28) * this.frame.lambda) {
          this.skipped = true;
          return;
        }
        this.localVector = this.size < 16 && parent != NO_VECTOR ? parent : this.searchMotion(parent);
        this.predict(this.localVector, this.localPrediction);
        boolean closer = false;
        if (this.localVector != 0 && this.eligible(2)) {
          this.record[0] = (byte) motionX(this.localVector);
          this.record[1] = (byte) motionY(this.localVector);
          if (this.kernels.predicted(this.localPrediction, this.size, this.recon)) {
            this.score(MODE_MOTION, 0, 2);
            closer = this.kernels.distortion() < skipDistortion;
          }
        }
        this.compact(closer);
      }
      this.solid();
      if (this.hurried) {
        this.skipped = true;
        return;
      }
      this.palette();
      this.pattern();
    }

    private double cost() {
      return this.frame.buffers.costs[this.level][this.block];
    }

    private boolean eligible(final int length) {
      final double bits = length == 0 && this.size == ROOT_SIZE ? 1 : INDEX_BITS;
      this.rate = Math.min(this.frame.lambda * (length * Byte.SIZE + bits), Double.MAX_VALUE);
      final double best = this.cost();
      this.kernels.start(this.source, this.rate, best);
      return best > this.rate;
    }

    private void score(final int mode, final int quantizer, final int length) {
      final long distortion = this.kernels.distortion();
      final double cost = Math.min(distortion / DISTORTION_SCALE + this.rate, Double.MAX_VALUE);
      if (cost < this.cost()) {
        System.arraycopy(this.recon, 0, this.best, 0, this.recon.length);
        this.frame.set(this.level, this.block, cost, mode, quantizer, this.record, length, distortion);
      }
    }

    private void loadSource() {
      final BlockCoder parent = this.root;
      if (parent != null) {
        final int rowLength = this.size * CHANNELS;
        for (int row = 0; row < this.size; row++) {
          System.arraycopy(
            parent.source,
            ((this.top - parent.top + row) * ROOT_SIZE + this.left - parent.left) * CHANNELS,
            this.source,
            row * rowLength,
            rowLength
          );
        }
      } else {
        this.kernels.loadSource(this.frame.source, this.frame.width, this.frame.height, this.left, this.top, this.size, this.source);
      }
    }

    private void predict(final int vector, final int[] out) {
      final BlockCoder parent = this.root;
      final int[] from =
        parent == null ? null : vector == 0 ? parent.zeroPrediction : vector == parent.localVector ? parent.localPrediction : null;
      if (parent != null && from != null) {
        final int rowLength = this.size * CHANNELS;
        for (int row = 0; row < this.size; row++) {
          System.arraycopy(
            from,
            ((this.top - parent.top + row) * ROOT_SIZE + this.left - parent.left) * CHANNELS,
            out,
            row * rowLength,
            rowLength
          );
        }
      } else {
        this.kernels.predict(
          this.frame.reference,
          this.frame.width,
          this.frame.height,
          this.left,
          this.top,
          this.size,
          motionX(vector),
          motionY(vector),
          out
        );
      }
    }

    private int searchMotion(final int parent) {
      final FrameState frame = this.frame;
      final int half = this.size / 2;
      this.seeds[0] = frame.previousMotion(this.left + half, this.top + half);
      this.seeds[1] = frame.previousMotion(this.left - 1, this.top + half);
      this.seeds[2] = frame.previousMotion(this.left + this.size, this.top + half);
      this.seeds[3] = frame.previousMotion(this.left + half, this.top - 1);
      this.seeds[4] = frame.previousMotion(this.left + half, this.top + this.size);
      this.seeds[5] = parent == NO_VECTOR ? this.seeds[0] : parent;
      if (this.size >= 16) {
        Arrays.fill(this.seeds, this.halfMotion());
      }
      return this.kernels.seeded(
        frame.reference,
        frame.width,
        frame.height,
        this.source,
        this.left,
        this.top,
        this.size,
        MOTION_RANGE,
        this.seeds
      );
    }

    private int halfMotion() {
      final FrameState frame = this.frame;
      this.kernels.halve(this.source, this.size, this.halfSource);
      if (!frame.fast && this.size == ROOT_SIZE) {
        Arrays.fill(this.halfSeeds, this.quarterMotion());
      } else {
        for (int index = 0; index < this.seeds.length; index++) {
          this.halfSeeds[index] = packMotion(motionX(this.seeds[index]) / 2, motionY(this.seeds[index]) / 2);
        }
      }
      final int coarse = this.kernels.seeded(
        frame.buffers.half,
        (frame.width + 1) / 2,
        (frame.height + 1) / 2,
        this.halfSource,
        this.left / 2,
        this.top / 2,
        this.size / 2,
        MOTION_RANGE / 2,
        this.halfSeeds
      );
      return packMotion(motionX(coarse) * 2, motionY(coarse) * 2);
    }

    private int quarterMotion() {
      final FrameState frame = this.frame;
      this.kernels.halve(this.halfSource, this.size / 2, this.quarterSource);
      for (int index = 0; index < this.seeds.length; index++) {
        this.quarterSeeds[index] = packMotion(motionX(this.seeds[index]) / 4, motionY(this.seeds[index]) / 4);
      }
      final int halfWidth = (frame.width + 1) / 2;
      final int halfHeight = (frame.height + 1) / 2;
      final int coarse = this.kernels.seeded(
        frame.buffers.quarter,
        (halfWidth + 1) / 2,
        (halfHeight + 1) / 2,
        this.quarterSource,
        this.left / 4,
        this.top / 4,
        this.size / 4,
        MOTION_RANGE / 4,
        this.quarterSeeds
      );
      return packMotion(motionX(coarse) * 2, motionY(coarse) * 2);
    }

    private float[] endpoints() {
      if (!this.clustered) {
        this.kernels.cluster(this.source, this.size, this.clusters);
        this.clustered = true;
      }
      return this.clusters;
    }

    private void solid() {
      if (!this.eligible(CHANNELS)) {
        return;
      }
      long redSum = 0;
      long greenSum = 0;
      long blueSum = 0;
      for (int pixel = 0; pixel < this.count; pixel++) {
        redSum += this.source[pixel * CHANNELS];
        greenSum += this.source[pixel * CHANNELS + 1];
        blueSum += this.source[pixel * CHANNELS + 2];
      }
      final int red = (int) Math.floor((double) redSum / this.count + 0.5);
      final int green = (int) Math.floor((double) greenSum / this.count + 0.5);
      final int blue = (int) Math.floor((double) blueSum / this.count + 0.5);
      this.record[0] = (byte) red;
      this.record[1] = (byte) green;
      this.record[2] = (byte) blue;
      if (this.kernels.solid((red << 16) | (green << 8) | blue, this.size, this.recon)) {
        this.score(MODE_SOLID, 0, CHANNELS);
      }
    }

    private void writePalette(final byte[] target) {
      for (int index = 0; index < 2 * CHANNELS; index++) {
        target[index] = (byte) this.colors[index];
      }
      Arrays.fill(target, 2 * CHANNELS, 2 * CHANNELS + this.count / Byte.SIZE, (byte) 0);
      for (int pixel = 0; pixel < this.count; pixel++) {
        target[2 * CHANNELS + pixel / Byte.SIZE] |= (byte) (this.selectors[pixel] << (pixel % Byte.SIZE));
      }
    }

    private void palette() {
      final int length = recordSize(MODE_PALETTE, this.size);
      if (!this.eligible(length)) {
        return;
      }
      this.kernels.finish(this.source, this.count, this.endpoints(), false, this.colors, this.selectors);
      this.writePalette(this.record);
      if (this.kernels.palette(this.record, 0, this.size, this.recon)) {
        this.score(MODE_PALETTE, 0, length);
      }
    }

    private void pattern() {
      final int length = patternSize(this.size, false, false);
      if (!this.eligible(length)) {
        return;
      }
      if (!this.kernels.finishPattern(this.source, this.size, this.endpoints(), true, this.colors, this.selectors)) {
        return;
      }
      this.writePalette(this.palette);
      patternRecord(this.palette, this.size, this.record);
      if (this.kernels.palette(this.palette, 0, this.size, this.recon)) {
        this.score(MODE_PATTERN, 0, length);
      }
    }

    private void compact(final boolean local) {
      final int[] prediction = local ? this.localPrediction : this.zeroPrediction;
      final int deltaX = local ? motionX(this.localVector) : 0;
      final int deltaY = local ? motionY(this.localVector) : 0;
      final int form = deltaX == 0 && deltaY == 0 ? 0 : deltaX >= -8 && deltaX <= 7 && deltaY >= -8 && deltaY <= 7 ? 1 : 2;
      boolean targeted = false;
      for (int kind = COMPACT_DC; kind <= COMPACT_GRID_Y; kind++) {
        final int length = 1 + form + (kind == COMPACT_DC ? 1 : kind == COMPACT_GRID ? 10 : 8);
        if (!this.eligible(length)) {
          continue;
        }
        if (!targeted) {
          if (!this.ycocgLoaded) {
            this.kernels.ycocg(this.source, this.count, this.ycocg);
            this.ycocgLoaded = true;
          }
          this.kernels.residualTarget(this.ycocg, prediction, this.count, this.target);
          this.meansLoaded = false;
          this.gridLoaded = false;
          targeted = true;
        }
        final int values = this.compactFit(kind);
        final int quantizer = neededQuantizer(kind, this.fit, values);
        final int body = 1 + form;
        this.record[0] = (byte) (kind | (form << 4));
        if (form == 1) {
          this.record[1] = (byte) ((deltaX & 15) | ((deltaY & 15) << 4));
        } else if (form == 2) {
          this.record[1] = (byte) deltaX;
          this.record[2] = (byte) deltaY;
        }
        this.compactBody(kind, quantizer, body);
        if (this.kernels.compact(prediction, this.record, body, kind, quantizer, this.size, this.recon)) {
          this.score(MODE_COMPACT, quantizer, length);
        }
      }
    }

    private int compactFit(final int kind) {
      if (kind == COMPACT_DC) {
        this.fit[0] = this.mean(0);
        return 1;
      }
      if (!this.gridLoaded) {
        this.kernels.fit(this.target, 0, CHANNELS, this.size, this.lumaGrid, 0, 1);
        this.gridLoaded = true;
      }
      System.arraycopy(this.lumaGrid, 0, this.fit, 0, GRID_NODES);
      if (kind == COMPACT_GRID_Y) {
        return GRID_NODES;
      }
      this.fit[GRID_NODES] = this.mean(1);
      this.fit[GRID_NODES + 1] = this.mean(2);
      return GRID_NODES + 2;
    }

    private float mean(final int channel) {
      if (!this.meansLoaded) {
        double luma = 0;
        double orange = 0;
        double green = 0;
        for (int offset = 0; offset < this.target.length; offset += CHANNELS) {
          luma += this.target[offset];
          orange += this.target[offset + 1];
          green += this.target[offset + 2];
        }
        this.means[0] = (float) (luma / this.count);
        this.means[1] = (float) (orange / this.count);
        this.means[2] = (float) (green / this.count);
        this.meansLoaded = true;
      }
      return this.means[channel];
    }

    private void compactBody(final int kind, final int quantizer, final int body) {
      final int step = 1 << quantizer;
      if (kind == COMPACT_DC) {
        this.record[body] = (byte) quantize(this.fit[0], step, -128, 127);
        return;
      }
      for (int index = 0; index < GRID_NODES / 2; index++) {
        final int low = quantize(this.fit[2 * index], step, -8, 7) & 15;
        final int high = quantize(this.fit[2 * index + 1], step, -8, 7) & 15;
        this.record[body + index] = (byte) (low | (high << 4));
      }
      if (kind == COMPACT_GRID) {
        this.record[body + 8] = (byte) quantize(this.fit[GRID_NODES], step, -128, 127);
        this.record[body + 9] = (byte) quantize(this.fit[GRID_NODES + 1], step, -128, 127);
      }
    }
  }

  // Fits preserve the live palette iterations and separable least squares.
  private static final int CLUSTER_SUMS = 8;
  private static final int COUNTS = 6;
  private static final int ITERATIONS = 2;
  private static final int SAMPLED_SIZE = 16;
  private static final float[][] FITTING_MATRICES = fittingMatrices();

  private static void cluster(final int[] source, final int size, final long[] sums, final float[] endpoints) {
    final int step = size >= SAMPLED_SIZE ? 2 : 1;
    int low = 0;
    int high = 0;
    int lowLuma = Integer.MAX_VALUE;
    int highLuma = Integer.MIN_VALUE;
    for (int row = 0; row < size; row += step) {
      for (int column = 0; column < size; column += step) {
        final int at = (row * size + column) * CHANNELS;
        final int luma = source[at] + 2 * source[at + 1] + source[at + 2];
        if (luma < lowLuma) {
          lowLuma = luma;
          low = at;
        }
        if (luma > highLuma) {
          highLuma = luma;
          high = at;
        }
      }
    }
    for (int channel = 0; channel < CHANNELS; channel++) {
      endpoints[channel] = source[low + channel];
      endpoints[CHANNELS + channel] = source[high + channel];
    }
    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
      final int firstRed = (int) endpoints[0];
      final int firstGreen = (int) endpoints[1];
      final int firstBlue = (int) endpoints[2];
      final int secondRed = (int) endpoints[3];
      final int secondGreen = (int) endpoints[4];
      final int secondBlue = (int) endpoints[5];
      Arrays.fill(sums, 0, CLUSTER_SUMS, 0);
      for (int row = 0; row < size; row += step) {
        for (int column = 0; column < size; column += step) {
          final int at = (row * size + column) * CHANNELS;
          final int red = source[at];
          final int green = source[at + 1];
          final int blue = source[at + 2];
          final int firstDistance =
            (red - firstRed) * (red - firstRed) + (green - firstGreen) * (green - firstGreen) + (blue - firstBlue) * (blue - firstBlue);
          final int secondDistance =
            (red - secondRed) * (red - secondRed) +
            (green - secondGreen) * (green - secondGreen) +
            (blue - secondBlue) * (blue - secondBlue);
          final int nearest = secondDistance < firstDistance ? 1 : 0;
          sums[nearest * CHANNELS] += red;
          sums[nearest * CHANNELS + 1] += green;
          sums[nearest * CHANNELS + 2] += blue;
          sums[COUNTS + nearest]++;
        }
      }
      for (int endpoint = 0; endpoint < PALETTE_COLORS; endpoint++) {
        final long members = sums[COUNTS + endpoint];
        if (members > 0) {
          for (int channel = 0; channel < CHANNELS; channel++) {
            final long mean = (sums[endpoint * CHANNELS + channel] + members / 2) / members;
            endpoints[endpoint * CHANNELS + channel] = mean;
          }
        }
      }
    }
  }

  private static void finishPalette(
    final int[] source,
    final int count,
    final float[] endpoints,
    final boolean shouldQuantize,
    final int[] colors,
    final byte[] selectors
  ) {
    roundPalette(endpoints, shouldQuantize, colors);
    for (int pixel = 0; pixel < count; pixel++) {
      selectors[pixel] = nearest(source, pixel, colors);
    }
  }

  private static void roundPalette(final float[] endpoints, final boolean shouldQuantize, final int[] colors) {
    for (int index = 0; index < PALETTE_COLORS * CHANNELS; index++) {
      colors[index] = rgb8(endpoints[index]);
    }
    if (shouldQuantize) {
      for (int endpoint = 0; endpoint < PALETTE_COLORS; endpoint++) {
        final int at = endpoint * CHANNELS;
        final int value = pack565(colors[at], colors[at + 1], colors[at + 2]);
        final int packed = unpack565(value & 0xFF, value >> Byte.SIZE);
        colors[at] = (packed >> 16) & 0xFF;
        colors[at + 1] = (packed >> 8) & 0xFF;
        colors[at + 2] = packed & 0xFF;
      }
    }
  }

  private static byte nearest(final int[] source, final int pixel, final int[] colors) {
    final int at = pixel * CHANNELS;
    final int firstRedDelta = source[at] - colors[0];
    final int firstGreenDelta = source[at + 1] - colors[1];
    final int firstBlueDelta = source[at + 2] - colors[2];
    final int secondRedDelta = source[at] - colors[3];
    final int secondGreenDelta = source[at + 1] - colors[4];
    final int secondBlueDelta = source[at + 2] - colors[5];
    final int firstDistance = firstRedDelta * firstRedDelta + firstGreenDelta * firstGreenDelta + firstBlueDelta * firstBlueDelta;
    final int secondDistance = secondRedDelta * secondRedDelta + secondGreenDelta * secondGreenDelta + secondBlueDelta * secondBlueDelta;
    return (byte) (secondDistance < firstDistance ? 1 : 0);
  }

  private static boolean finishPattern(
    final int[] source,
    final int size,
    final float[] endpoints,
    final boolean shouldQuantize,
    final int[] colors,
    final byte[] selectors
  ) {
    roundPalette(endpoints, shouldQuantize, colors);
    boolean columns = true;
    boolean rows = true;
    for (int row = 0; row < size && (columns || rows); row++) {
      for (int column = 0; column < size; column++) {
        final int pixel = row * size + column;
        selectors[pixel] = nearest(source, pixel, colors);
        columns &= selectors[pixel] == selectors[column];
        rows &= selectors[pixel] == selectors[row * size];
      }
    }
    return columns || rows;
  }

  private static int rgb8(final float value) {
    return (int) Math.floor(Math.min(Math.max(value, 0), MAX_CHANNEL) + 0.5f);
  }

  private static int quantize(final float value, final int step, final int low, final int high) {
    final float scaled = (float) Math.floor(value / step + 0.5f);
    return (int) Math.min(Math.max(scaled, low), high);
  }

  private static int neededQuantizer(final int kind, final float[] fit, final int values) {
    for (int quantizer = 0; quantizer < COARSEST_QUANTIZER; quantizer++) {
      final int step = 1 << quantizer;
      boolean fits = true;
      for (int index = 0; index < values && fits; index++) {
        final boolean nibble = kind != COMPACT_DC && index < GRID_NODES;
        final float value = (float) Math.floor(fit[index] / step + 0.5f);
        fits = value >= (nibble ? -8 : -128) && value <= (nibble ? 7 : 127);
      }
      if (fits) {
        return quantizer;
      }
    }
    return COARSEST_QUANTIZER;
  }

  private static float[][] fittingMatrices() {
    final float[][] matrices = new float[BLOCK_SIZES][];
    for (int index = 0; index < BLOCK_SIZES; index++) {
      final int size = SMALLEST_BLOCK << index;
      final double[][] basis = new double[size][GRID];
      for (int pixel = 0; pixel < size; pixel++) {
        final double position = Math.min(Math.max(((pixel + 0.5) * GRID) / size - 0.5, 0), GRID - 1);
        final int lower = (int) position;
        final int upper = Math.min(lower + 1, GRID - 1);
        basis[pixel][lower] += 1 - (position - lower);
        basis[pixel][upper] += position - lower;
      }
      final double[][] inverse = new double[GRID][2 * GRID];
      for (int row = 0; row < GRID; row++) {
        for (int column = 0; column < GRID; column++) {
          for (int pixel = 0; pixel < size; pixel++) {
            inverse[row][column] += basis[pixel][row] * basis[pixel][column];
          }
        }
        inverse[row][GRID + row] = 1;
      }
      // The clamped interpolation basis has independent columns, so its Gram matrix is positive definite.
      for (int pivot = 0; pivot < GRID; pivot++) {
        final double scale = inverse[pivot][pivot];
        for (int column = 0; column < 2 * GRID; column++) {
          inverse[pivot][column] /= scale;
        }
        for (int row = 0; row < GRID; row++) {
          if (row == pivot) {
            continue;
          }
          final double factor = inverse[row][pivot];
          for (int column = 0; column < 2 * GRID; column++) {
            inverse[row][column] -= factor * inverse[pivot][column];
          }
        }
      }
      final float[] matrix = new float[GRID * size];
      for (int node = 0; node < GRID; node++) {
        for (int pixel = 0; pixel < size; pixel++) {
          double value = 0;
          for (int column = 0; column < GRID; column++) {
            value += inverse[node][GRID + column] * basis[pixel][column];
          }
          matrix[node * size + pixel] = (float) value;
        }
      }
      matrices[index] = matrix;
    }
    return matrices;
  }

  private static void fitGrid(
    final float[] values,
    final int offset,
    final int stride,
    final int size,
    final double[] scratch,
    final float[] out,
    final int outOffset,
    final int outStride
  ) {
    final float[] matrix = FITTING_MATRICES[sizeIndex(size)];
    for (int row = 0; row < size; row++) {
      for (int nodeColumn = 0; nodeColumn < GRID; nodeColumn++) {
        double sum = 0;
        for (int column = 0; column < size; column++) {
          sum += (double) values[offset + (row * size + column) * stride] * matrix[nodeColumn * size + column];
        }
        scratch[row * GRID + nodeColumn] = sum;
      }
    }
    for (int nodeRow = 0; nodeRow < GRID; nodeRow++) {
      for (int nodeColumn = 0; nodeColumn < GRID; nodeColumn++) {
        double sum = 0;
        for (int row = 0; row < size; row++) {
          sum += matrix[nodeRow * size + row] * scratch[row * GRID + nodeColumn];
        }
        out[outOffset + (nodeRow * GRID + nodeColumn) * outStride] = (float) sum;
      }
    }
  }

  // Pixel kernels share reusable scratch; native implementations use this same contract.
  private interface Kernels {
    void start(int[] source, double rate, double limit);
    long distortion();
    boolean predicted(int[] prediction, int size, int[] out);
    boolean solid(int color, int size, int[] out);
    boolean palette(byte[] record, int offset, int size, int[] out);
    boolean compact(int[] prediction, byte[] record, int body, int kind, int quantizer, int size, int[] out);
    void predict(byte[] reference, int width, int height, int left, int top, int size, int motionX, int motionY, int[] out);
    void fit(float[] values, int offset, int stride, int size, float[] out, int outOffset, int outStride);
    void cluster(int[] source, int size, float[] endpoints);
    void finish(int[] source, int count, float[] endpoints, boolean shouldQuantize, int[] colors, byte[] selectors);
    boolean finishPattern(int[] source, int size, float[] endpoints, boolean shouldQuantize, int[] colors, byte[] selectors);
    int seeded(byte[] reference, int width, int height, int[] source, int left, int top, int size, int range, int[] seeds);
    void loadSource(byte[] image, int width, int height, int left, int top, int size, int[] source);
    void halve(int[] block, int size, int[] out);
    void ycocg(int[] source, int count, float[] out);
    void residualTarget(float[] ycocg, int[] prediction, int count, float[] target);

    default void forgetArrays() {}
  }

  private static final class Score {

    private int[] source = new int[0];
    private double rate;
    private double limit;
    private long distortion;

    private void start(final int[] source, final double rate, final double limit) {
      this.source = source;
      this.rate = rate;
      this.limit = limit;
      this.distortion = 0;
    }

    private boolean row(final int[] out, final int from, final int pixels) {
      int sum = 0;
      final int end = from + pixels * CHANNELS;
      for (int offset = from; offset < end; offset += CHANNELS) {
        sum += pixelError(
          this.source[offset] - out[offset],
          this.source[offset + 1] - out[offset + 1],
          this.source[offset + 2] - out[offset + 2]
        );
      }
      this.distortion += sum;
      return this.distortion / DISTORTION_SCALE + this.rate < this.limit;
    }
  }

  private static int pixelError(final int red, final int green, final int blue) {
    final int luma = red + 2 * green + blue;
    final int orange = red - blue;
    final int chromaGreen = 2 * green - red - blue;
    return 4 * (luma * luma + orange * orange) + chromaGreen * chromaGreen;
  }

  private static int roundChannel(final int value, final int shift) {
    return Math.min(Math.max((value + (1 << (shift - 1))) >> shift, 0), MAX_CHANNEL);
  }

  private static final class JavaKernels implements Kernels {

    private final Score score = new Score();
    private final int[] nodes = new int[GRID_NODES];
    private final int[] rows = new int[GRID * ROOT_SIZE];
    private final int[] line = new int[ROOT_SIZE];
    private final double[] fitScratch = new double[ROOT_SIZE * GRID];
    private final long[] clusterScratch = new long[CLUSTER_SUMS];

    @Override
    public void start(final int[] source, final double rate, final double limit) {
      this.score.start(source, rate, limit);
    }

    @Override
    public long distortion() {
      return this.score.distortion;
    }

    @Override
    public boolean predicted(final int[] prediction, final int size, final int[] out) {
      final int rowLength = size * CHANNELS;
      for (int row = 0; row < size; row++) {
        final int from = row * rowLength;
        for (int offset = from; offset < from + rowLength; offset++) {
          out[offset] = prediction[offset] >> 2;
        }
        if (!this.score.row(out, from, size)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public boolean solid(final int color, final int size, final int[] out) {
      final int red = (color >> 16) & 255;
      final int green = (color >> 8) & 255;
      final int blue = color & 255;
      for (int row = 0; row < size; row++) {
        final int from = row * size * CHANNELS;
        for (int column = 0; column < size; column++) {
          out[from + column * CHANNELS] = red;
          out[from + column * CHANNELS + 1] = green;
          out[from + column * CHANNELS + 2] = blue;
        }
        if (!this.score.row(out, from, size)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public boolean palette(final byte[] record, final int offset, final int size, final int[] out) {
      for (int row = 0; row < size; row++) {
        for (int column = 0; column < size; column++) {
          final int pixel = row * size + column;
          final int bit = (record[offset + 2 * CHANNELS + pixel / Byte.SIZE] >> (pixel % Byte.SIZE)) & 1;
          final int at = offset + bit * CHANNELS;
          out[pixel * CHANNELS] = record[at] & 255;
          out[pixel * CHANNELS + 1] = record[at + 1] & 255;
          out[pixel * CHANNELS + 2] = record[at + 2] & 255;
        }
        if (!this.score.row(out, row * size * CHANNELS, size)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public boolean compact(
      final int[] prediction,
      final byte[] record,
      final int body,
      final int kind,
      final int quantizer,
      final int size,
      final int[] out
    ) {
      if (kind == COMPACT_DC) {
        final int delta = (record[body] << quantizer) * 4;
        for (int row = 0; row < size; row++) {
          final int from = row * size * CHANNELS;
          for (int offset = from; offset < from + size * CHANNELS; offset++) {
            out[offset] = roundChannel(prediction[offset] + delta, 2);
          }
          if (!this.score.row(out, from, size)) {
            return false;
          }
        }
        return true;
      }
      for (int index = 0; index < GRID_NODES; index++) {
        this.nodes[index] = signed((record[body + index / 2] & 255) >> ((index % 2) * 4), 4);
      }
      this.horizontal(size);
      final int orange = kind == COMPACT_GRID ? record[body + 8] : 0;
      final int chromaGreen = kind == COMPACT_GRID ? record[body + 9] : 0;
      final int shift = 2 * (Integer.numberOfTrailingZeros(size) + 1);
      final int quarter = size * size;
      final int scale = 4 * quarter;
      final int red = ((orange - chromaGreen) * scale) << quantizer;
      final int green = (chromaGreen * scale) << quantizer;
      final int blue = (-(orange + chromaGreen) * scale) << quantizer;
      if (orange == 0 && chromaGreen == 0) {
        final int half = 1 << (shift - 1);
        for (int row = 0; row < size; row++) {
          this.vertical(size, row);
          final int from = row * size * CHANNELS;
          for (int column = 0; column < size; column++) {
            final int at = from + column * CHANNELS;
            final int scaled = (this.line[column] << quantizer) + half;
            out[at] = Math.min(Math.max((prediction[at] * quarter + scaled) >> shift, 0), MAX_CHANNEL);
            out[at + 1] = Math.min(Math.max((prediction[at + 1] * quarter + scaled) >> shift, 0), MAX_CHANNEL);
            out[at + 2] = Math.min(Math.max((prediction[at + 2] * quarter + scaled) >> shift, 0), MAX_CHANNEL);
          }
          if (!this.score.row(out, from, size)) {
            return false;
          }
        }
        return true;
      }
      for (int row = 0; row < size; row++) {
        this.vertical(size, row);
        final int from = row * size * CHANNELS;
        for (int column = 0; column < size; column++) {
          final int at = from + column * CHANNELS;
          final int scaled = this.line[column] << quantizer;
          out[at] = roundChannel(prediction[at] * quarter + scaled + red, shift);
          out[at + 1] = roundChannel(prediction[at + 1] * quarter + scaled + green, shift);
          out[at + 2] = roundChannel(prediction[at + 2] * quarter + scaled + blue, shift);
        }
        if (!this.score.row(out, from, size)) {
          return false;
        }
      }
      return true;
    }

    private void horizontal(final int size) {
      final int[] lower = GRID_LOWER[sizeIndex(size)];
      final int[] upper = GRID_UPPER[sizeIndex(size)];
      final int[] weights = GRID_WEIGHTS[sizeIndex(size)];
      final int span = 2 * size;
      for (int row = 0; row < GRID; row++) {
        for (int column = 0; column < size; column++) {
          final int weight = weights[column];
          this.rows[row * size + column] =
            this.nodes[row * GRID + lower[column]] * (span - weight) + this.nodes[row * GRID + upper[column]] * weight;
        }
      }
    }

    private void vertical(final int size, final int row) {
      final int index = sizeIndex(size);
      final int top = GRID_LOWER[index][row] * size;
      final int bottom = GRID_UPPER[index][row] * size;
      final int below = GRID_WEIGHTS[index][row];
      final int above = 2 * size - below;
      for (int column = 0; column < size; column++) {
        this.line[column] = this.rows[top + column] * above + this.rows[bottom + column] * below;
      }
    }

    @Override
    public void predict(
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
      final int left = blockLeft + motionX;
      final int top = blockTop + motionY;
      if (left >= 0 && top >= 0 && left + size <= width && top + size <= height) {
        final int rowLength = size * CHANNELS;
        for (int row = 0; row < size; row++) {
          final int from = ((top + row) * width + left) * CHANNELS;
          final int to = row * rowLength;
          for (int offset = 0; offset < rowLength; offset++) {
            out[to + offset] = 4 * (reference[from + offset] & 255);
          }
        }
        return;
      }
      for (int row = 0; row < size; row++) {
        final int sourceRow = Math.min(Math.max(top + row, 0), height - 1);
        for (int column = 0; column < size; column++) {
          final int sourceColumn = Math.min(Math.max(left + column, 0), width - 1);
          final int from = (sourceRow * width + sourceColumn) * CHANNELS;
          final int to = (row * size + column) * CHANNELS;
          for (int channel = 0; channel < CHANNELS; channel++) {
            out[to + channel] = 4 * (reference[from + channel] & 255);
          }
        }
      }
    }

    @Override
    public void fit(
      final float[] values,
      final int offset,
      final int stride,
      final int size,
      final float[] out,
      final int outOffset,
      final int outStride
    ) {
      fitGrid(values, offset, stride, size, this.fitScratch, out, outOffset, outStride);
    }

    @Override
    public void cluster(final int[] source, final int size, final float[] endpoints) {
      MCV2.cluster(source, size, this.clusterScratch, endpoints);
    }

    @Override
    public void finish(
      final int[] source,
      final int count,
      final float[] endpoints,
      final boolean shouldQuantize,
      final int[] colors,
      final byte[] selectors
    ) {
      finishPalette(source, count, endpoints, shouldQuantize, colors, selectors);
    }

    @Override
    public boolean finishPattern(
      final int[] source,
      final int size,
      final float[] endpoints,
      final boolean shouldQuantize,
      final int[] colors,
      final byte[] selectors
    ) {
      return MCV2.finishPattern(source, size, endpoints, shouldQuantize, colors, selectors);
    }

    @Override
    public int seeded(
      final byte[] reference,
      final int width,
      final int height,
      final int[] source,
      final int left,
      final int top,
      final int size,
      final int range,
      final int[] seeds
    ) {
      return seededMotion(reference, width, height, source, left, top, size, range, seeds);
    }

    @Override
    public void halve(final int[] block, final int size, final int[] out) {
      halveBlock(block, size, out);
    }

    @Override
    public void loadSource(
      final byte[] image,
      final int width,
      final int height,
      final int blockLeft,
      final int blockTop,
      final int size,
      final int[] source
    ) {
      for (int row = 0; row < size; row++) {
        final int sourceRow = Math.min(blockTop + row, height - 1);
        for (int column = 0; column < size; column++) {
          final int sourceColumn = Math.min(blockLeft + column, width - 1);
          final int from = (sourceRow * width + sourceColumn) * CHANNELS;
          final int to = (row * size + column) * CHANNELS;
          source[to] = image[from] & 255;
          source[to + 1] = image[from + 1] & 255;
          source[to + 2] = image[from + 2] & 255;
        }
      }
    }

    @Override
    public void ycocg(final int[] source, final int count, final float[] out) {
      for (int offset = 0; offset < count * CHANNELS; offset += CHANNELS) {
        final int red = source[offset];
        final int green = source[offset + 1];
        final int blue = source[offset + 2];
        out[offset] = (red + 2 * green + blue) * 0.25f;
        out[offset + 1] = (red - blue) * 0.5f;
        out[offset + 2] = (-red + 2 * green - blue) * 0.25f;
      }
    }

    @Override
    public void residualTarget(final float[] ycocg, final int[] prediction, final int count, final float[] target) {
      for (int offset = 0; offset < count * CHANNELS; offset += CHANNELS) {
        final float red = prediction[offset] * 0.25f;
        final float green = prediction[offset + 1] * 0.25f;
        final float blue = prediction[offset + 2] * 0.25f;
        target[offset] = ycocg[offset] - (red + 2 * green + blue) * 0.25f;
        target[offset + 1] = ycocg[offset + 1] - (red - blue) * 0.5f;
        target[offset + 2] = ycocg[offset + 2] - (-red + 2 * green - blue) * 0.25f;
      }
    }
  }

  private static final int[][] GRID_LOWER = new int[BLOCK_SIZES][];
  private static final int[][] GRID_UPPER = new int[BLOCK_SIZES][];
  private static final int[][] GRID_WEIGHTS = new int[BLOCK_SIZES][];

  static {
    for (int index = 0; index < BLOCK_SIZES; index++) {
      final int size = SMALLEST_BLOCK << index;
      final int span = 2 * size;
      GRID_LOWER[index] = new int[size];
      GRID_UPPER[index] = new int[size];
      GRID_WEIGHTS[index] = new int[size];
      for (int pixel = 0; pixel < size; pixel++) {
        final int position = Math.min(Math.max((2 * pixel + 1) * GRID - size, 0), (GRID - 1) * span);
        GRID_LOWER[index][pixel] = position / span;
        GRID_UPPER[index][pixel] = Math.min(position / span + 1, GRID - 1);
        GRID_WEIGHTS[index][pixel] = position % span;
      }
    }
  }

  private static void halveBlock(final int[] block, final int size, final int[] out) {
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

  // The writer emits one level-order index and tables when repeated records pay for them.
  private static byte @Nullable [] write(
    final int width,
    final int height,
    final boolean keyframe,
    final List<TreeNode> input,
    final long frameId,
    final long referenceId
  ) {
    final Map<Integer, Integer> colors = new LinkedHashMap<>();
    if (keyframe) {
      for (final TreeNode root : input) {
        countSolids(root, colors);
      }
    }
    int defaultColor = 0;
    int most = -1;
    for (final Map.Entry<Integer, Integer> entry : colors.entrySet()) {
      if (entry.getValue() > most) {
        most = entry.getValue();
        defaultColor = entry.getKey();
      }
    }
    final List<TreeNode> roots = new ArrayList<>(input.size());
    for (final TreeNode root : input) {
      roots.add(keyframe ? rewriteDefault(root, defaultColor) : root);
    }
    final List<TreeNode> flat = new ArrayList<>();
    final List<Integer> sizes = new ArrayList<>();
    final int[] levels = new int[BLOCK_SIZES];
    List<TreeNode> level = new ArrayList<>();
    for (final TreeNode root : roots) {
      if (root.getMode() != MODE_SKIP) {
        level.add(root);
      }
    }
    for (int depth = 0; depth < BLOCK_SIZES; depth++) {
      levels[depth] = level.size();
      final List<TreeNode> children = new ArrayList<>();
      for (final TreeNode node : level) {
        flat.add(node);
        sizes.add(ROOT_SIZE >> depth);
        if (node.isSplit()) {
          for (int corner = 0; corner < QUARTERS; corner++) {
            children.add(node.getChild(corner));
          }
        }
      }
      level = children;
    }
    final Map<Long, Integer> endpoints = endpointTable(flat);
    final List<Map<Long, Integer>> words = selectorTables(flat, sizes);
    final int groups = (roots.size() + GROUP_ROOTS - 1) / GROUP_ROOTS;
    final int checkpoints = (groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS;
    final int countsAt = HEADER_BYTES + (groups + checkpoints) * Integer.BYTES;
    final int descriptorsAt = countsAt + BLOCK_SIZES * Integer.BYTES;
    final int walksAt = descriptorsAt + flat.size();
    final int start = walksAt + ((flat.size() + WALK_SPAN - 1) / WALK_SPAN) * Integer.BYTES + Integer.BYTES;
    int length = start + endpoints.size() * Integer.BYTES;
    for (int index = 0; index < BLOCK_SIZES; index++) {
      length += words.get(index).size() * (1 + (SMALLEST_BLOCK << index) / Byte.SIZE);
    }
    for (int index = 0; index < flat.size(); index++) {
      final TreeNode node = flat.get(index);
      length +=
        node.getMode() == MODE_PATTERN
          ? patternSize(sizes.get(index), !endpoints.isEmpty(), !words.get(sizeIndex(sizes.get(index))).isEmpty())
          : node.record().length;
    }
    if (length > MAX_FRAME_BYTES) {
      return null;
    }
    final byte[] data = new byte[length];
    putU32(data, 0, MAGIC);
    data[4] = VERSION;
    data[5] = (byte) (keyframe ? KEYFRAME : 0);
    putU16(data, DIMENSIONS_OFFSET, width);
    putU16(data, DIMENSIONS_OFFSET + Short.BYTES, height);
    putU32(data, FRAME_ID_OFFSET, frameId);
    putU32(data, REFERENCE_ID_OFFSET, referenceId);
    putU32(data, PAYLOAD_START_OFFSET, start);
    putU32(data, TOTAL_OFFSET, length);
    if (keyframe) {
      putColor(data, DEFAULT_COLOR_OFFSET, defaultColor);
    }
    int seen = 0;
    for (int group = 0; group < groups; group++) {
      long mask = 0;
      final int from = group * GROUP_ROOTS;
      for (int index = from; index < Math.min(roots.size(), from + GROUP_ROOTS); index++) {
        if (roots.get(index).getMode() != MODE_SKIP) {
          mask |= 1L << (index - from);
        }
      }
      putU32(data, HEADER_BYTES + group * Integer.BYTES, mask);
      if (group % CHECKPOINT_GROUPS == 0) {
        putU32(data, HEADER_BYTES + (groups + group / CHECKPOINT_GROUPS) * Integer.BYTES, seen);
      }
      seen += Long.bitCount(mask);
    }
    for (int index = 0; index < BLOCK_SIZES; index++) {
      putU32(data, countsAt + index * Integer.BYTES, levels[index]);
    }
    data[start - Integer.BYTES] = (byte) endpoints.size();
    for (int index = 0; index < BLOCK_SIZES; index++) {
      data[start - BLOCK_SIZES + index] = (byte) words.get(index).size();
    }
    int cursor = 0;
    int splits = 0;
    for (int index = 0; index < flat.size(); index++) {
      final TreeNode node = flat.get(index);
      final int size = sizes.get(index);
      if (index % WALK_SPAN == 0) {
        putU32(data, walksAt + (index / WALK_SPAN) * Integer.BYTES, cursor | ((long) splits << 17));
      }
      data[descriptorsAt + index] = (byte) (node.getMode() | (node.getQ() << QUANTIZER_SHIFT));
      if (node.isSplit()) {
        splits++;
        continue;
      }
      final byte[] record = node.record();
      if (node.getMode() == MODE_PATTERN) {
        if (endpoints.isEmpty()) {
          System.arraycopy(record, 0, data, start + cursor, 2 * CHANNELS);
          cursor += 2 * CHANNELS;
        } else {
          data[start + cursor++] = (byte) (int) Preconditions.checkNotNull(endpoints.get(bytesKey(record, 0, 2 * CHANNELS)));
        }
        final Map<Long, Integer> table = words.get(sizeIndex(size));
        if (table.isEmpty()) {
          System.arraycopy(record, 2 * CHANNELS, data, start + cursor, 1 + size / Byte.SIZE);
          cursor += 1 + size / Byte.SIZE;
        } else {
          data[start + cursor++] = (byte) (int) Preconditions.checkNotNull(table.get(bytesKey(record, 2 * CHANNELS, 1 + size / Byte.SIZE)));
        }
      } else {
        System.arraycopy(record, 0, data, start + cursor, record.length);
        cursor += record.length;
      }
    }
    int at = start + cursor;
    for (int index = 0; index < BLOCK_SIZES; index++) {
      final int entry = 1 + (SMALLEST_BLOCK << index) / Byte.SIZE;
      for (final long word : words.get(index).keySet()) {
        for (int offset = 0; offset < entry; offset++) {
          data[at++] = (byte) (word >>> (offset * Byte.SIZE));
        }
      }
    }
    for (final long pair : endpoints.keySet()) {
      for (int endpoint = 0; endpoint < PALETTE_COLORS; endpoint++) {
        final long color = pair >>> (endpoint * CHANNELS * Byte.SIZE);
        putU16(data, at, pack565((int) color & 255, (int) (color >> 8) & 255, (int) (color >> 16) & 255));
        at += Short.BYTES;
      }
    }
    return data;
  }

  private static Map<Long, Integer> endpointTable(final List<TreeNode> flat) {
    final Map<Long, Integer> counts = new LinkedHashMap<>();
    int uses = 0;
    for (final TreeNode node : flat) {
      if (node.getMode() != MODE_PATTERN) {
        continue;
      }
      final byte[] record = node.record();
      // Palettes rewritten as patterns may still have full RGB endpoints, which a 565 table cannot preserve.
      for (int at = 0; at < PALETTE_COLORS * CHANNELS; at += CHANNELS) {
        final int packed = pack565(record[at] & 255, record[at + 1] & 255, record[at + 2] & 255);
        if (unpack565(packed & 255, packed >> Byte.SIZE) != color(record, at)) {
          return Map.of();
        }
      }
      counts.putIfAbsent(bytesKey(record, 0, 2 * CHANNELS), counts.size());
      uses++;
    }
    return counts.size() <= 255 && uses * 5 > counts.size() * Integer.BYTES ? counts : Map.of();
  }

  private static List<Map<Long, Integer>> selectorTables(final List<TreeNode> flat, final List<Integer> sizes) {
    final List<Map<Long, Integer>> tables = new ArrayList<>(List.of(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>()));
    final int[] uses = new int[BLOCK_SIZES];
    for (int index = 0; index < flat.size(); index++) {
      final TreeNode node = flat.get(index);
      if (node.getMode() != MODE_PATTERN) {
        continue;
      }
      final int size = sizes.get(index);
      final int which = sizeIndex(size);
      final Map<Long, Integer> table = tables.get(which);
      table.putIfAbsent(bytesKey(node.record(), 2 * CHANNELS, 1 + size / Byte.SIZE), table.size());
      uses[which]++;
    }
    for (int index = 0; index < BLOCK_SIZES; index++) {
      final int entry = 1 + (SMALLEST_BLOCK << index) / Byte.SIZE;
      final int count = tables.get(index).size();
      if (count > 255 || uses[index] * (entry - 1) <= count * entry) {
        tables.set(index, Map.of());
      }
    }
    return tables;
  }

  private static long bytesKey(final byte[] data, final int at, final int length) {
    long key = 0;
    for (int index = 0; index < length; index++) {
      key |= (data[at + index] & 255L) << (Byte.SIZE * index);
    }
    return key;
  }

  private static int color(final byte[] data, final int at) {
    return ((data[at] & 255) << 16) | ((data[at + 1] & 255) << 8) | (data[at + 2] & 255);
  }

  private static void putColor(final byte[] data, final int at, final int color) {
    data[at] = (byte) (color >> 16);
    data[at + 1] = (byte) (color >> 8);
    data[at + 2] = (byte) color;
  }

  private static void countSolids(final TreeNode node, final Map<Integer, Integer> counts) {
    if (node.isSplit()) {
      for (int corner = 0; corner < QUARTERS; corner++) {
        countSolids(node.getChild(corner), counts);
      }
    } else if (node.getMode() == MODE_SOLID) {
      counts.merge(color(node.record(), 0), 1, Integer::sum);
    }
  }

  private static TreeNode rewriteDefault(final TreeNode node, final int defaultColor) {
    if (node.isSplit()) {
      return TreeNode.split(
        rewriteDefault(node.getChild(0), defaultColor),
        rewriteDefault(node.getChild(1), defaultColor),
        rewriteDefault(node.getChild(2), defaultColor),
        rewriteDefault(node.getChild(3), defaultColor)
      );
    }
    return node.getMode() == MODE_SOLID && color(node.record(), 0) == defaultColor ? TreeNode.skip() : node;
  }

  private static boolean patternRecord(final byte[] paletteRecord, final int size, final byte[] patternOutput) {
    for (int orientation = 0; orientation < 2; orientation++) {
      boolean repeats = true;
      for (int row = 0; row < size && repeats; row++) {
        for (int column = 0; column < size; column++) {
          final int axis = orientation == 0 ? paletteBit(paletteRecord, column) : paletteBit(paletteRecord, row * size);
          if (paletteBit(paletteRecord, row * size + column) != axis) {
            repeats = false;
            break;
          }
        }
      }
      if (repeats) {
        System.arraycopy(paletteRecord, 0, patternOutput, 0, 2 * CHANNELS);
        patternOutput[2 * CHANNELS] = (byte) orientation;
        final int at = 2 * CHANNELS + 1;
        Arrays.fill(patternOutput, at, at + size / Byte.SIZE, (byte) 0);
        for (int position = 0; position < size; position++) {
          final int value = orientation == 0 ? paletteBit(paletteRecord, position) : paletteBit(paletteRecord, position * size);
          patternOutput[at + position / Byte.SIZE] |= (byte) (value << (position % Byte.SIZE));
        }
        return true;
      }
    }
    return false;
  }

  private static int paletteBit(final byte[] paletteRecord, final int index) {
    return (paletteRecord[2 * CHANNELS + index / Byte.SIZE] >> (index % Byte.SIZE)) & 1;
  }

  private static TreeNode withPatterns(final TreeNode node, final int size) {
    if (node.isSplit()) {
      return TreeNode.split(
        withPatterns(node.getChild(0), size / 2),
        withPatterns(node.getChild(1), size / 2),
        withPatterns(node.getChild(2), size / 2),
        withPatterns(node.getChild(3), size / 2)
      );
    }
    if (node.getMode() == MODE_PALETTE) {
      final byte[] patternOutput = new byte[patternSize(size, false, false)];
      if (patternRecord(node.record(), size, patternOutput)) {
        return TreeNode.leaf(MODE_PATTERN, 0, patternOutput);
      }
    }
    return node;
  }

  private static final class TreeNode {

    private static final TreeNode SKIP = new TreeNode(MODE_SKIP, 0, new byte[0], null);

    private final int mode;

    private final int quantizer;

    private final byte[] record;

    private final TreeNode @Nullable [] children;

    private TreeNode(final int mode, final int quantizer, final byte[] record, final TreeNode @Nullable [] children) {
      this.mode = mode;
      this.quantizer = quantizer;
      this.record = record;
      this.children = children;
    }

    private static TreeNode skip() {
      return SKIP;
    }

    private static TreeNode leaf(final int mode, final int quantizer, final byte[] record) {
      Preconditions.checkNotNull(record, "Record must not be null");
      Preconditions.checkArgument(mode != MODE_SPLIT && mode >= 0 && mode <= MODE_MASK, "Invalid leaf mode %s", mode);
      Preconditions.checkArgument(quantizer >= 0 && quantizer <= MAX_QUANTIZER, "Invalid quantizer %s", quantizer);
      return new TreeNode(mode, quantizer, record.clone(), null);
    }

    private static TreeNode split(final TreeNode topLeft, final TreeNode topRight, final TreeNode bottomLeft, final TreeNode bottomRight) {
      Preconditions.checkNotNull(topLeft, "Children must not be null");
      Preconditions.checkNotNull(topRight, "Children must not be null");
      Preconditions.checkNotNull(bottomLeft, "Children must not be null");
      Preconditions.checkNotNull(bottomRight, "Children must not be null");
      return new TreeNode(MODE_SPLIT, 0, new byte[0], new TreeNode[] { topLeft, topRight, bottomLeft, bottomRight });
    }

    private int getMode() {
      return this.mode;
    }

    private int getQ() {
      return this.quantizer;
    }

    private boolean isSplit() {
      return this.children != null;
    }

    private byte[] record() {
      return this.record;
    }

    private TreeNode getChild(final int index) {
      final TreeNode[] nodes = this.children;
      if (nodes == null) {
        throw new IllegalStateException("A leaf has no children");
      }
      return nodes[index];
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      if (!(other instanceof final TreeNode node)) {
        return false;
      }
      return (
        this.mode == node.mode &&
        this.quantizer == node.quantizer &&
        Arrays.equals(this.record, node.record) &&
        Arrays.equals(this.children, node.children)
      );
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.mode, this.quantizer, Arrays.hashCode(this.record), Arrays.hashCode(this.children));
    }

    @Override
    public String toString() {
      return this.isSplit()
        ? "split" + Arrays.toString(this.children)
        : "leaf(" + this.mode + "," + this.quantizer + "," + this.record.length + "B)";
    }
  }

  // Chosen reconstructions become the reference, and verification checks the written bytes.
  private static void assemble(final FrameState frame, final ChosenLeaf leaf) {
    final byte[] source = frame.buffers.levels[leaf.level()];
    final int right = Math.min(leaf.left() + leaf.size(), frame.width);
    final int bottom = Math.min(leaf.top() + leaf.size(), frame.height);
    for (int row = leaf.top(); row < bottom; row++) {
      final int at = (row * frame.width + leaf.left()) * CHANNELS;
      System.arraycopy(source, at, frame.picture, at, (right - leaf.left()) * CHANNELS);
    }
  }

  private static void fillMotion(
    final int[] field,
    final int width,
    final int height,
    final TreeNode node,
    final int left,
    final int top,
    final int size
  ) {
    if (node.isSplit()) {
      final int half = size / 2;
      for (int corner = 0; corner < QUARTERS; corner++) {
        fillMotion(field, width, height, node.getChild(corner), left + (corner % 2) * half, top + (corner / 2) * half, half);
      }
      return;
    }
    final byte[] record = node.record();
    final int mode = node.getMode();
    final int vector =
      mode == MODE_MOTION
        ? packMotion(record[0], record[1])
        : mode == MODE_COMPACT
          ? packMotion(compactX(record, 0), compactY(record, 0))
          : 0;
    final int columns = (width + SMALLEST_BLOCK - 1) / SMALLEST_BLOCK;
    for (int row = top; row < Math.min(top + size, height); row += SMALLEST_BLOCK) {
      for (int column = left; column < Math.min(left + size, width); column += SMALLEST_BLOCK) {
        field[(row / SMALLEST_BLOCK) * columns + column / SMALLEST_BLOCK] = vector;
      }
    }
  }

  private void verify(final Pending pending) {
    try {
      final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(pending.data);
      final byte[] decoded = new byte[pending.picture.length];
      final int bands = (frame.getHeight() + ROOT_SIZE - 1) / ROOT_SIZE;
      final AtomicReference<@Nullable Mcv2Exception> failure = new AtomicReference<>();
      final AtomicBoolean same = new AtomicBoolean(true);
      this.workers.forEach(
        bands,
        () -> decoded,
        (picture, band) -> {
          final int from = band * ROOT_SIZE;
          final int to = Math.min(frame.getHeight(), from + ROOT_SIZE);
          try {
            Mcv2Decoder.decodeRows(frame, pending.predictFrom, pending.referenceId, picture, from, to);
          } catch (final Mcv2Exception exception) {
            failure.compareAndSet(null, exception);
            return;
          }
          final int first = from * frame.getWidth() * CHANNELS;
          final int last = to * frame.getWidth() * CHANNELS;
          if (!Arrays.equals(picture, first, last, pending.picture, first, last)) {
            same.set(false);
          }
        }
      );
      final Mcv2Exception error = failure.get();
      if (error != null) {
        throw error;
      }
      Preconditions.checkState(same.get(), "MCV2 live picture and decoded picture disagree");
    } catch (final Mcv2Exception exception) {
      throw new IllegalStateException("The encoder wrote a frame the decoder rejects", exception);
    }
  }

  // Workers share a bounded CPU pool across screens and finish all callbacks before returning.
  private static final class Workers {

    private final @Nullable ForkJoinPool pool;

    private final int threads;

    private Workers(final @Nullable ForkJoinPool pool, final int threads) {
      Preconditions.checkArgument(threads >= 1, "At least one thread is needed");
      this.pool = pool;
      this.threads = threads;
    }

    private int threads() {
      return this.pool == null ? 1 : this.threads;
    }

    private <T> void forEach(final int count, final Supplier<T> scratch, final ObjIntConsumer<T> body) {
      final ForkJoinPool target = this.pool;
      final int workers = Math.min(this.threads(), count);
      if (target == null || workers <= 1) {
        final T state = scratch.get();
        for (int index = 0; index < count; index++) {
          body.accept(state, index);
        }
        return;
      }
      final AtomicInteger next = new AtomicInteger();
      final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
      target
        .submit(() ->
          IntStream.range(0, workers)
            .parallel()
            .forEach(_ -> {
              try {
                final T state = scratch.get();
                for (int index = next.getAndIncrement(); index < count; index = next.getAndIncrement()) {
                  body.accept(state, index);
                }
              } catch (final RuntimeException | Error exception) {
                failure.compareAndSet(null, exception);
              }
            })
        )
        .join();
      final Throwable reported = failure.get();
      if (reported instanceof final RuntimeException exception) {
        throw exception;
      }
      if (reported instanceof final Error error) {
        throw error;
      }
    }
  }

  public static final class Pool implements AutoCloseable {

    public static final int MAX_THREADS = 256;

    private static final long KEEP_ALIVE_SECONDS = 30;

    private static final Logger LOGGER = LoggerFactory.getLogger(Pool.class);

    private static final String THREAD_FAILED = "MCV2 encoder thread {} failed";

    private static final ThreadGroup GROUP = lowPriorityGroup();

    private static @Nullable Pool shared;

    private static int sharedThreads;

    private final ForkJoinPool pool;

    private final int threads;

    public Pool(final int threads) {
      Preconditions.checkArgument(threads >= 1 && threads <= MAX_THREADS, "Threads must be 1 to %s", MAX_THREADS);
      this.threads = threads;
      final AtomicInteger created = new AtomicInteger();
      final ForkJoinPool.ForkJoinWorkerThreadFactory factory = forkJoinPool -> {
        final ForkJoinWorkerThread thread = new EncoderThread(forkJoinPool);
        thread.setName("mcav-mcv2-encoder-" + created.incrementAndGet());
        thread.setDaemon(true);
        return thread;
      };

      this.pool = new ForkJoinPool(threads, factory, Pool::uncaught, false, 0, threads, 1, _ -> true, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
    }

    private static final class EncoderThread extends ForkJoinWorkerThread {

      private EncoderThread(final ForkJoinPool pool) {
        super(GROUP, pool, false);
      }
    }

    private static ThreadGroup lowPriorityGroup() {
      final ThreadGroup group = new ThreadGroup("mcav-mcv2-encoders");
      group.setMaxPriority(Thread.MIN_PRIORITY);
      return group;
    }

    public static int defaultThreads(final int processors) {
      return Math.max(1, processors / 2);
    }

    public static synchronized Pool shared() {
      final Pool current = shared;
      if (current != null) {
        return current;
      }
      final int size = sharedThreads > 0 ? sharedThreads : defaultThreads(Runtime.getRuntime().availableProcessors());
      final Pool created = new Pool(size);
      shared = created;
      return created;
    }

    public static synchronized void setSharedThreads(final int threads) {
      Preconditions.checkArgument(threads >= 0 && threads <= MAX_THREADS, "Threads must be 0 to %s", MAX_THREADS);
      if (threads != sharedThreads) {
        sharedThreads = threads;
        shared = null;
      }
    }

    public int getThreads() {
      return this.threads;
    }

    public MCV2 encoder(final Settings settings, final boolean shouldVerify) {
      return new MCV2(settings, this.pool, this.threads, shouldVerify);
    }

    public <T> T run(final Callable<T> task) throws InterruptedException {
      Preconditions.checkNotNull(task, "Task must not be null");

      if (Thread.interrupted()) {
        throw new InterruptedException("Interrupted before the task started");
      }
      final ForkJoinTask<T> job = this.pool.submit(task);
      try {
        return job.get();
      } catch (final InterruptedException exception) {
        job.cancel(true);
        throw exception;
      } catch (final ExecutionException exception) {
        final Throwable cause = exception.getCause();
        if (cause instanceof final RuntimeException runtime) {
          throw runtime;
        }
        if (cause instanceof final Error error) {
          throw error;
        }
        throw new IllegalStateException("An encode failed", cause);
      }
    }

    static void uncaught(final Thread thread, final Throwable exception) {
      LOGGER.error(THREAD_FAILED, thread.getName(), exception);
    }

    @Override
    public void close() {
      this.pool.shutdownNow();
    }
  }
}
