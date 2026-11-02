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

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.util.Objects.requireNonNull;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.BLOCK_SIZES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.CHANNELS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.CHECKPOINT_GROUPS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.COMPACT_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.CURSOR_BITS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.DIMENSIONS_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.FRAME_ID_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.GRID_LOWER;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.GRID_UPPER;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.GRID_WEIGHTS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.GROUP_ROOTS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.HEADER_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MAGIC;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MAXIMUM_DIMENSION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MAXIMUM_FRAME_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MAXIMUM_QUANTIZER;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MAXIMUM_UNSIGNED_INT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_COMPACT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_PALETTE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_PATTERN;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_SKIP;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_SOLID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_SPLIT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.QUANTIZER_SHIFT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.QUARTERS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.REFERENCE_ID_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.ROOT_SIZE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.SMALLEST_BLOCK;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.VERSION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.WALK_SPAN;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.follows;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.interpolateHorizontally;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.isBlockSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.patternSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.putUnsignedInt;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.putUnsignedShort;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.recordSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.roundToChannel;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.signed;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.sizeIndex;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The version 3 live encoder. Each instance encodes one stream on a caller-owned pool.
 * Calls to begin and calls to finish must each be serial; one begin may overlap the preceding finish.
 * Settings changes and keyframe requests may come from another thread.
 *
 * @since 1.0.0
 */
public final class Mcv2 {

  private static final String SETTINGS_NULL_MESSAGE = "Settings must not be null!";
  private static final String INVALID_DIMENSIONS_MESSAGE = "Invalid dimensions!";
  private static final String INVALID_BLOCK_SIZE_MESSAGE = "Invalid block size!";
  private static final String INVALID_PIXEL_COUNT_MESSAGE = "Invalid pixel count!";

  private static final String STOPPED_MESSAGE = "The encoder stopped after a frame failed its verification!";
  private static final int LIMIT_RETRIES = 4;
  private static final int MOTION_RANGE = 24;
  private static final int NORMAL_SKIP_BITS = 28;
  private static final int FAST_SKIP_BITS = 60;
  private static final int ROOT_SPLIT_BITS = 150;
  private static final int NORMAL_FINE_SPLIT_BITS = 300;
  private static final int FAST_FINE_SPLIT_BITS = 600;
  private static final int NORMAL_STEADY_SPLIT_BITS = 450;
  private static final int FAST_STEADY_SPLIT_BITS = 900;
  private static final int KEYFRAME_INTERVAL = 120;
  private static final double INDEX_BITS = 12;
  private static final double DISTORTION_SCALE = 96.0;
  private static final int OUTSIDE_BITS = 56;
  private static final int GRID = 4;
  private static final int GRID_NODES = GRID * GRID;
  private static final int MAXIMUM_RECORD = 134;
  private static final int NO_VECTOR = 0x80008000;
  private static final int COARSEST_QUANTIZER = 2;
  private static final int PALETTE_COLORS = 2;
  private static final byte[] NO_BYTES = new byte[0];
  private static final boolean[] NO_SPLITS = new boolean[0];

  private static final int MAGIC_OFFSET = 0;
  private static final int VERSION_OFFSET = 4;

  private static final int BYTE_MASK = 0xFF;

  private static final int GREEN_OFFSET = 1;
  private static final int BLUE_OFFSET = 2;

  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final int LUMA_GREEN_WEIGHT = 2;

  private static final int CHROMA_GREEN_WEIGHT = 2;

  private static final int LUMA_ORANGE_ERROR_WEIGHT = 4;

  private static final int ROUNDING_DIVISOR = 2;

  private static final int LAMBDA_GROWTH = 2;

  private static final int QUARTERS_PER_SIDE = 2;

  private static final int TOP_LEFT = 0;
  private static final int TOP_RIGHT = 1;
  private static final int BOTTOM_LEFT = 2;
  private static final int BOTTOM_RIGHT = 3;

  private static final int FINEST_LEVEL = BLOCK_SIZES - 1;

  private static final int MOTION_BYTES = 2;

  private static final int NODES_PER_BYTE = 2;
  private static final int NODE_BITS = 4;
  private static final int NODE_MASK = 0xF;

  private static final int PATTERN_ORIENTATIONS = 2;
  private volatile Settings settings;
  private final AtomicBoolean keyframeRequested = new AtomicBoolean();
  private byte[] verificationPicture = NO_BYTES;
  private final Workers workers;
  private final Verification verification;
  private long frameBudget;
  private int frameLimit;
  private byte @Nullable [] reference;
  private int width;
  private int height;
  private long lastFrameId = -1;
  private int framesSinceKeyframe;
  private int[] motion = new int[0];
  private boolean @Nullable [] splitBefore;
  private @Nullable Buffers buffers;
  private final ArrayDeque<BlockCoder[]> idleCoders = new ArrayDeque<>();
  private final List<BlockCoder[]> busyCoders = new ArrayList<>();
  private final MotionLambda motionLambda = new MotionLambda();
  private volatile @Nullable Statistics statistics;
  private volatile boolean failed;
  private @Nullable Pending newer;
  private @Nullable Pending older;
  private final Supplier<Kernels> kernels;

  // Motion search against the reference pyramid.
  private static final int[][] DIRECTIONS = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
  private static final int SAMPLES_PER_SIDE = 4;
  private static final int[][] SAMPLES = { { 1, 3, 5, 7 }, { 2, 6, 10, 14 }, { 4, 12, 20, 28 } };

  private static final int MOTION_X_SHIFT = 16;
  private static final int MOTION_Y_MASK = 0xFFFF;

  private static final int WINDOW_SIDES = 2;

  private static final int PYRAMID_SCALE = 2;

  private static final int QUAD_AVERAGE_ROUNDING = 2;
  private static final int QUAD_AVERAGE_SHIFT = 2;

  // Palette, pattern and compact-grid fitting.
  private static final int CLUSTER_SUMS = 8;
  private static final int CLUSTER_COUNTS_OFFSET = 6;
  private static final int CLUSTER_ITERATIONS = 2;
  private static final int SAMPLED_SIZE = 16;
  private static final int SAMPLED_STEP = 2;

  private static final int MINIMUM_NODE = -8;
  private static final int MAXIMUM_NODE = 7;

  private static final float ROUNDING_BIAS = 0.5f;

  private static final double CENTER_OFFSET = 0.5;

  private static final int AUGMENTED_COLUMNS = 2 * GRID;
  private static final float[][] FITTING_MATRICES = fittingMatrices();

  // Pixel kernels and native-library binding.

  /**
   * The system property that turns the native kernels on ({@code auto}) or off ({@code off}) over the configuration.
   *
   * @since 1.0.0
   */
  public static final String NATIVE_PROPERTY = "mcv2.native";

  /**
   * Uses the native kernels where a library loads: the default.
   *
   * @since 1.0.0
   */
  public static final String NATIVE_AUTO = "auto";

  /**
   * Always runs the Java kernels.
   *
   * @since 1.0.0
   */
  public static final String NATIVE_OFF = "off";

  private static final String NATIVE_RESOURCES = "/mcav/mcv2/natives/";

  static final Map<String, String> NATIVE_DIGESTS = Natives.digests(Mcv2.class.getResourceAsStream(NATIVE_RESOURCES + "SHA256SUMS"));

  /**
   * Creates an encoder for one stream on a caller-owned pool.
   *
   * @param settings     initial live search settings
   * @param pool         worker pool, retained but never shut down by this encoder
   * @param threads      maximum concurrent workers, at least one
   * @param verification whether finish checks every reconstructed pixel with Mcv2Decoder
   * @throws NullPointerException     if {@code settings}, {@code pool} or {@code verification} is null
   * @throws IllegalArgumentException if threads is less than one
   * @since 1.0.0
   */
  public Mcv2(final Settings settings, final ForkJoinPool pool, final int threads, final Verification verification) {
    this.settings = Preconditions.checkNotNull(settings, SETTINGS_NULL_MESSAGE);
    this.workers = new Workers(Preconditions.checkNotNull(pool, "Pool must not be null!"), threads);
    this.verification = Preconditions.checkNotNull(verification, "Verification must not be null!");
    this.kernels = Natives.resolved().factory();
    this.framesSinceKeyframe = KEYFRAME_INTERVAL;
  }

  /**
   * Requests an independent picture on the next begin call.
   *
   * @since 1.0.0
   */
  public void requestKeyframe() {
    this.keyframeRequested.set(true);
  }

  /**
   * Changes settings for the next frame while retaining prediction history.
   *
   * @param settings the new settings
   * @throws NullPointerException if {@code settings} is null
   * @since 1.0.0
   */
  public void setSettings(final Settings settings) {
    this.settings = Preconditions.checkNotNull(settings, SETTINGS_NULL_MESSAGE);
  }

  /**
   * Returns a copy of the latest reconstruction, including a frame still awaiting finish.
   *
   * @return row-major RGB24 reference, or empty before the first begin
   * @since 1.0.0
   */
  public Optional<byte[]> getReference() {
    final byte @Nullable [] previous = this.reference;
    return Optional.ofNullable(previous).map(byte[]::clone);
  }

  /**
   * Searches, writes and finishes one frame synchronously.
   *
   * @param sourcePixels row-major RGB24 source, exactly width times height times three bytes
   * @param width        picture width, 1 through 4096
   * @param height       picture height, 1 through 4096
   * @param frameId      unsigned 32-bit id strictly following the last id modulo 2^32
   * @return a complete version 3 frame
   * @throws NullPointerException     if {@code sourcePixels} is null
   * @throws IllegalArgumentException if dimensions, input length or frame id are invalid
   * @throws IllegalStateException    if the encoder stopped or pending frames prevent this operation
   * @since 1.0.0
   */
  public byte[] encode(final byte[] sourcePixels, final int width, final int height, final long frameId) {
    return this.finish(this.begin(sourcePixels, width, height, frameId)).getData();
  }

  /**
   * Searches and writes the next frame, assembling its prediction reference before returning.
   * Calls to begin are serial; finish of the preceding frame may overlap one begin.
   * At most two unfinished frames may exist and they must finish in order.
   *
   * @param sourcePixels row-major RGB24 source, exactly width times height times three bytes
   * @param width        picture width, 1 through 4096
   * @param height       picture height, 1 through 4096
   * @param frameId      unsigned 32-bit id strictly following the last id modulo 2^32
   * @return the pending frame to finish exactly once
   * @throws NullPointerException     if {@code sourcePixels} is null
   * @throws IllegalArgumentException if dimensions, input length or frame id are invalid
   * @throws IllegalStateException    if the encoder stopped or pending frames prevent this operation
   * @since 1.0.0
   */
  public Pending begin(final byte[] sourcePixels, final int width, final int height, final long frameId) {
    Preconditions.checkState(!this.failed, STOPPED_MESSAGE);
    Preconditions.checkState(this.older == null || this.older.finished, "Two frames are in flight already!");
    Preconditions.checkNotNull(sourcePixels, "Picture must not be null!");
    Preconditions.checkArgument(
      width >= 1 && width <= MAXIMUM_DIMENSION && height >= 1 && height <= MAXIMUM_DIMENSION,
      INVALID_DIMENSIONS_MESSAGE
    );
    Preconditions.checkArgument(sourcePixels.length == width * height * CHANNELS, "Picture size does not match the dimensions!");
    Preconditions.checkArgument(frameId >= 0 && frameId <= MAXIMUM_UNSIGNED_INT, "Frame id must be an unsigned 32-bit value!");
    Preconditions.checkArgument(this.lastFrameId < 0 || follows(frameId, this.lastFrameId), "Stale or ambiguous frame id!");

    final long started = System.nanoTime();
    final byte[] previous = this.reference;
    final boolean requested = this.keyframeRequested.getAndSet(false);
    final boolean predictable =
      !requested && previous != null && width == this.width && height == this.height && this.framesSinceKeyframe < KEYFRAME_INTERVAL;
    final Settings settings = this.settings;
    final boolean fast = settings.isFast();
    final double base = settings.getLambda();
    double lambda = Math.min(this.motionLambda.lambda(base), Double.MAX_VALUE);
    final boolean keyframe = !predictable;
    final Mcv2FrameKind kind = keyframe ? Mcv2FrameKind.KEYFRAME : Mcv2FrameKind.PREDICTED;
    final byte[] predictFrom = keyframe ? NO_BYTES : Preconditions.checkNotNull(previous);
    final long predictsId = keyframe ? frameId : this.lastFrameId;
    Buffers buffers = this.buffers;
    if (buffers == null || buffers.width != width || buffers.height != height) {
      buffers = new Buffers(width, height);
      this.buffers = buffers;
    }

    if (!keyframe) {
      halve(predictFrom, width, height, this.workers, buffers.half);

      if (!fast) {
        halve(buffers.half, (width + 1) / PYRAMID_SCALE, (height + 1) / PYRAMID_SCALE, this.workers, buffers.quarter);
      }
    }

    final Pending verifying = this.newer;
    final byte[] picture = buffers.spare(
      predictFrom,
      verifying == null ? NO_BYTES : verifying.picture,
      verifying == null ? NO_BYTES : verifying.predictFrom
    );
    final boolean[] before = keyframe ? NO_SPLITS : Preconditions.checkNotNull(this.splitBefore);
    final FrameState frame = new FrameState(
      sourcePixels,
      predictFrom,
      width,
      height,
      keyframe,
      fast,
      lambda,
      this.motion,
      buffers,
      picture
    );
    SearchResult searched = this.search(frame, before, started, SearchEffort.BUDGETED);
    byte[] data = write(width, height, kind, searched.roots, frameId, predictsId);
    final int limit = this.frameLimit == 0 ? MAXIMUM_FRAME_BYTES : Math.min(this.frameLimit, MAXIMUM_FRAME_BYTES);
    for (int retry = 0; (data == null || data.length > limit) && lambda > 0 && retry < LIMIT_RETRIES; retry++) {
      lambda = Math.min(lambda * LAMBDA_GROWTH, Double.MAX_VALUE);
      frame.lambda = lambda;
      searched = this.search(frame, before, started, SearchEffort.BUDGETED);
      data = write(width, height, kind, searched.roots, frameId, predictsId);
    }

    if (data == null || data.length > limit) {
      searched = this.search(frame, before, started, SearchEffort.TRIVIAL);
      data = write(width, height, kind, searched.roots, frameId, predictsId);
      Preconditions.checkState(Objects.nonNull(data), "Trivial frame exceeds the format bound!");
    }

    final Pending pending = new Pending(
      this,
      Objects.requireNonNull(data),
      picture,
      predictFrom,
      predictsId,
      keyframe,
      searched.leaves,
      lambda,
      System.nanoTime() - started
    );
    this.motionLambda.observe(sourcePixels, width, height, this.workers);
    this.reference = picture;
    this.motion = searched.motion;
    this.splitBefore = searched.splits;
    this.width = width;
    this.height = height;
    this.lastFrameId = frameId;
    this.framesSinceKeyframe = keyframe ? 1 : this.framesSinceKeyframe + 1;
    this.older = this.newer;
    this.newer = pending;

    return pending;
  }

  /**
   * Verifies and publishes a pending frame. A verification failure permanently stops this encoder.
   *
   * @param pending an unfinished frame from this encoder, in begin order
   * @return the finished frame and statistics
   * @throws NullPointerException     if {@code pending} is null
   * @throws IllegalArgumentException if another encoder owns {@code pending}
   * @throws IllegalStateException    if order is wrong, pending is finished, or verification fails
   * @since 1.0.0
   */
  @SuppressWarnings("ReferenceEquality") // the pending frame must be the very frame this encoder created first
  public Encoded finish(final Pending pending) {
    Preconditions.checkNotNull(pending, "Frame must not be null!");
    Preconditions.checkState(!this.failed, STOPPED_MESSAGE);
    Preconditions.checkArgument(pending.owner == this, "The pending frame belongs to another encoder!");
    Preconditions.checkState(!pending.finished, "The frame is finished already!");
    Preconditions.checkState(this.older == null || this.older.finished || pending == this.older, "Finish frames in creation order!");

    final long started = System.nanoTime();
    if (this.verification == Verification.VERIFIED) {
      try {
        this.verify(pending);
      } catch (final RuntimeException exception) {
        this.failed = true;
        throw exception;
      }
    }

    pending.finished = true;
    final Statistics result = Statistics.of(
      pending.data.length,
      pending.keyframe,
      pending.leaves,
      pending.nanoseconds + System.nanoTime() - started,
      pending.lambda
    );
    this.statistics = result;

    return new Encoded(pending.data, result);
  }

  /**
   * Gives the native kernels a folder to extract their library into and the configured mode; a plugin calls this once
   * as it starts. Which kernels run is decided at the next encoder; existing encoders keep theirs, and a library already
   * loaded stays loaded.
   *
   * @param folder the folder, such as the plugin's data folder (hosted servers often mount {@code /tmp} without
   *               execution); created if missing
   * @param mode   {@code auto} or {@code off}; the system property {@value #NATIVE_PROPERTY} wins over it
   * @throws IllegalArgumentException if the mode is neither
   * @since 1.0.0
   */
  public static void installNatives(final Path folder, final String mode) {
    Natives.install(folder, mode);
  }

  /**
   * Returns which kernels the encoders use and why, deciding it if no encoder has yet: for example
   * {@code native avx2 (linux-x86_64)} or {@code Java, turned off by mcv2.native=off}.
   *
   * @return the description, as logged
   * @since 1.0.0
   */
  public static String describeNatives() {
    return Natives.resolved().description();
  }

  private static int packMotion(final int vectorX, final int vectorY) {
    return (vectorX << MOTION_X_SHIFT) | (vectorY & MOTION_Y_MASK);
  }

  private static int motionX(final int vector) {
    return vector >> MOTION_X_SHIFT;
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
    final MotionSearch search = new MotionSearch(reference, width, height, source, blockLeft, blockTop, size, samples);
    for (int seedIndex = 0; seedIndex < seeds.length; seedIndex++) {
      final int seedX = clampMotion(motionX(seeds[seedIndex]), range);
      final int seedY = clampMotion(motionY(seeds[seedIndex]), range);
      if (isSeedRepeated(seeds, seedIndex, seedX, seedY, range)) {
        continue;
      }
      search.consider(seedX, seedY);
    }

    for (int steps = 0; steps < WINDOW_SIDES * range; steps++) {
      final int centerX = search.bestX;
      final int centerY = search.bestY;
      for (int directionIndex = 0; directionIndex < DIRECTIONS.length; directionIndex++) {
        final int candidateX = clampMotion(centerX + DIRECTIONS[directionIndex][0], range);
        final int candidateY = clampMotion(centerY + DIRECTIONS[directionIndex][1], range);
        search.consider(candidateX, candidateY);
      }
      if (search.bestX == centerX && search.bestY == centerY) {
        break;
      }
    }

    return packMotion(search.bestX, search.bestY);
  }

  private static int clampMotion(final int component, final int range) {
    return Math.min(Math.max(component, 0 - range), 0 + range);
  }

  private static boolean isSeedRepeated(final int[] seeds, final int seedIndex, final int seedX, final int seedY, final int range) {
    boolean seen = seedX == 0 && seedY == 0;
    for (int earlierIndex = 0; earlierIndex < seedIndex && !seen; earlierIndex++) {
      seen = seedX == clampMotion(motionX(seeds[earlierIndex]), range) && seedY == clampMotion(motionY(seeds[earlierIndex]), range);
    }

    return seen;
  }

  private static void halve(final byte[] picture, final int width, final int height, final Workers workers, final byte[] output) {
    final int halfHeight = (height + 1) / PYRAMID_SCALE;
    workers.forEach(halfHeight, row -> halveRow(picture, width, height, row, output));
  }

  private static void halveRow(final byte[] picture, final int width, final int height, final int row, final byte[] output) {
    final int halfWidth = (width + 1) / PYRAMID_SCALE;
    final int topRow = PYRAMID_SCALE * row;
    final int bottomRow = Math.min(topRow + 1, height - 1);
    for (int column = 0; column < halfWidth; column++) {
      final int leftColumn = PYRAMID_SCALE * column;
      final int rightColumn = Math.min(leftColumn + 1, width - 1);
      for (int channel = 0; channel < CHANNELS; channel++) {
        final int sum =
          (picture[(topRow * width + leftColumn) * CHANNELS + channel] & BYTE_MASK) +
          (picture[(topRow * width + rightColumn) * CHANNELS + channel] & BYTE_MASK) +
          (picture[(bottomRow * width + leftColumn) * CHANNELS + channel] & BYTE_MASK) +
          (picture[(bottomRow * width + rightColumn) * CHANNELS + channel] & BYTE_MASK);
        output[(row * halfWidth + column) * CHANNELS + channel] = (byte) ((sum + QUAD_AVERAGE_ROUNDING) >> QUAD_AVERAGE_SHIFT);
      }
    }
  }

  private SearchResult search(final FrameState frame, final boolean[] before, final long started, final SearchEffort effort) {
    final Buffers buffers = frame.buffers;
    final BooleanSupplier late = () ->
      effort == SearchEffort.TRIVIAL || (this.frameBudget > 0 && System.nanoTime() - started >= this.frameBudget);
    final RootSearch rootSearch = new RootSearch(frame, before, late);
    try {
      this.workers.forEach(buffers.roots, () -> this.acquireCoders(frame), rootSearch::searchRoot);
    } finally {
      this.releaseCoders();
    }

    int leaves = 0;
    for (final int count : rootSearch.leafCounts) {
      leaves += count;
    }

    return new SearchResult(List.of(rootSearch.roots), leaves, rootSearch.motion, rootSearch.splits);
  }

  private static Choice descend(
    final FrameState frame,
    final BlockCoder[] coders,
    final int level,
    final int left,
    final int top,
    final int parent,
    final double threshold,
    final double fineThreshold
  ) {
    if (left >= frame.width || top >= frame.height) {
      return new Choice(TreeNode.leaf(MODE_SOLID, 0, new byte[CHANNELS]), frame.lambda * OUTSIDE_BITS, 0);
    }

    final int size = ROOT_SIZE >> level;
    final BlockCoder coder = coders[level];
    coder.code(left, top, parent);
    final double cost = coder.cost();
    if (level < BLOCK_SIZES - 1 && !coder.skipped && cost > threshold) {
      final int half = size / QUARTERS_PER_SIDE;
      double splitCost = frame.lambda * INDEX_BITS;
      final TreeNode[] children = new TreeNode[QUARTERS];
      int leaves = 0;
      for (int corner = 0; corner < QUARTERS && splitCost < cost; corner++) {
        final Choice child = descend(
          frame,
          coders,
          level + 1,
          left + (corner % QUARTERS_PER_SIDE) * half,
          top + (corner / QUARTERS_PER_SIDE) * half,
          coder.localVector,
          fineThreshold,
          fineThreshold
        );
        children[corner] = child.node();
        splitCost += child.cost();
        leaves += child.leaves();
      }

      if (splitCost < cost) {
        return new Choice(
          TreeNode.split(children[TOP_LEFT], children[TOP_RIGHT], children[BOTTOM_LEFT], children[BOTTOM_RIGHT]),
          splitCost,
          leaves
        );
      }
    }

    coder.reconstruct();
    return new Choice(TreeNode.leaf(coder.mode, coder.quantizer, Arrays.copyOf(coder.bestRecord, coder.length)), cost, 1);
  }

  private BlockCoder[] acquireCoders(final FrameState frame) {
    BlockCoder[] coders;
    synchronized (this.idleCoders) {
      coders = this.idleCoders.poll();
      if (coders == null) {
        coders = this.createCoders(frame);
      }

      this.busyCoders.add(coders);
    }

    for (final BlockCoder coder : coders) {
      coder.frame = frame;
    }

    return coders;
  }

  private BlockCoder[] createCoders(final FrameState frame) {
    final BlockCoder[] coders = {
      new BlockCoder(frame, ROOT_SIZE, this.kernels.get()),
      new BlockCoder(frame, ROOT_SIZE / QUARTERS_PER_SIDE, this.kernels.get()),
      new BlockCoder(frame, SMALLEST_BLOCK, this.kernels.get()),
    };
    coders[1].root = coders[0];
    coders[FINEST_LEVEL].root = coders[0];
    return coders;
  }

  private void releaseCoders() {
    synchronized (this.idleCoders) {
      for (final BlockCoder[] coders : this.busyCoders) {
        this.forgetCoderArrays(coders);
      }

      this.idleCoders.addAll(this.busyCoders);
      this.busyCoders.clear();
    }
  }

  private void forgetCoderArrays(final BlockCoder[] coders) {
    for (final BlockCoder coder : coders) {
      coder.kernels.forgetArrays();
    }
  }

  private static byte nearest(final int[] source, final int pixel, final int[] colors) {
    final int arrayOffset = pixel * CHANNELS;
    final int firstRedDelta = source[arrayOffset] - colors[0];
    final int firstGreenDelta = source[arrayOffset + GREEN_OFFSET] - colors[GREEN_OFFSET];
    final int firstBlueDelta = source[arrayOffset + BLUE_OFFSET] - colors[BLUE_OFFSET];
    final int secondRedDelta = source[arrayOffset] - colors[CHANNELS];
    final int secondGreenDelta = source[arrayOffset + GREEN_OFFSET] - colors[CHANNELS + GREEN_OFFSET];
    final int secondBlueDelta = source[arrayOffset + BLUE_OFFSET] - colors[CHANNELS + BLUE_OFFSET];
    final int firstDistance = firstRedDelta * firstRedDelta + firstGreenDelta * firstGreenDelta + firstBlueDelta * firstBlueDelta;
    final int secondDistance = secondRedDelta * secondRedDelta + secondGreenDelta * secondGreenDelta + secondBlueDelta * secondBlueDelta;
    return (byte) (secondDistance < firstDistance ? 1 : 0);
  }

  private static int quantize(final float value, final int step) {
    final float scaled = quantizedValue(value, step);
    return (int) Math.min(Math.max(scaled, MINIMUM_NODE), MAXIMUM_NODE);
  }

  private static float quantizedValue(final float value, final int step) {
    return (float) Math.floor(value / step + ROUNDING_BIAS);
  }

  private static int neededQuantizer(final float[] fit) {
    for (int quantizer = 0; quantizer < COARSEST_QUANTIZER; quantizer++) {
      final int step = 1 << quantizer;
      boolean fits = true;
      for (int index = 0; index < GRID_NODES && fits; index++) {
        final float value = quantizedValue(fit[index], step);
        fits = value >= MINIMUM_NODE && value <= MAXIMUM_NODE;
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
      matrices[index] = fittingMatrix(SMALLEST_BLOCK << index);
    }

    return matrices;
  }

  private static float[] fittingMatrix(final int size) {
    final double[][] basis = interpolationBasis(size);
    final double[][] inverse = augmentedGramMatrix(basis, size);
    invertAugmented(inverse);
    return projectionMatrix(inverse, basis, size);
  }

  private static double[][] interpolationBasis(final int size) {
    final double[][] basis = new double[size][GRID];
    for (int pixel = 0; pixel < size; pixel++) {
      final double position = Math.min(Math.max(((pixel + CENTER_OFFSET) * GRID) / size - CENTER_OFFSET, 0), GRID - 1);
      final int lower = (int) position;
      final int upper = Math.min(lower + 1, GRID - 1);
      basis[pixel][lower] += 1 - (position - lower);
      basis[pixel][upper] += position - lower;
    }

    return basis;
  }

  private static double[][] augmentedGramMatrix(final double[][] basis, final int size) {
    final double[][] inverse = new double[GRID][AUGMENTED_COLUMNS];
    for (int row = 0; row < GRID; row++) {
      for (int column = 0; column < GRID; column++) {
        inverse[row][column] = basisProduct(basis, size, row, column);
      }
      inverse[row][GRID + row] = 1;
    }

    return inverse;
  }

  private static double basisProduct(final double[][] basis, final int size, final int row, final int column) {
    double sum = 0;
    for (int pixel = 0; pixel < size; pixel++) {
      sum += basis[pixel][row] * basis[pixel][column];
    }

    return sum;
  }

  // Independent interpolation columns make the Gram matrix positive definite.
  private static void invertAugmented(final double[][] inverse) {
    for (int pivot = 0; pivot < GRID; pivot++) {
      final double scale = inverse[pivot][pivot];
      for (int column = 0; column < AUGMENTED_COLUMNS; column++) {
        inverse[pivot][column] /= scale;
      }
      eliminatePivotColumn(inverse, pivot);
    }
  }

  private static void eliminatePivotColumn(final double[][] inverse, final int pivot) {
    for (int row = 0; row < GRID; row++) {
      if (row == pivot) {
        continue;
      }
      final double factor = inverse[row][pivot];
      for (int column = 0; column < AUGMENTED_COLUMNS; column++) {
        inverse[row][column] -= factor * inverse[pivot][column];
      }
    }
  }

  private static float[] projectionMatrix(final double[][] inverse, final double[][] basis, final int size) {
    final float[] matrix = new float[GRID * size];
    for (int node = 0; node < GRID; node++) {
      for (int pixel = 0; pixel < size; pixel++) {
        matrix[node * size + pixel] = (float) projectPixel(inverse, basis, node, pixel);
      }
    }

    return matrix;
  }

  private static double projectPixel(final double[][] inverse, final double[][] basis, final int node, final int pixel) {
    double value = 0;
    for (int column = 0; column < GRID; column++) {
      value += inverse[node][GRID + column] * basis[pixel][column];
    }

    return value;
  }

  private static int pixelError(final int red, final int green, final int blue) {
    final int luma = red + LUMA_GREEN_WEIGHT * green + blue;
    final int orange = red - blue;
    final int chromaGreen = CHROMA_GREEN_WEIGHT * green - red - blue;
    return LUMA_ORANGE_ERROR_WEIGHT * (luma * luma + orange * orange) + chromaGreen * chromaGreen;
  }

  // Frame serialization in level order.
  private static byte @Nullable [] write(
    final int width,
    final int height,
    final Mcv2FrameKind kind,
    final List<TreeNode> roots,
    final long frameId,
    final long referenceId
  ) {
    Preconditions.checkNotNull(roots, "Roots must not be null!");
    Preconditions.checkArgument(
      width >= 1 && width <= MAXIMUM_DIMENSION && height >= 1 && height <= MAXIMUM_DIMENSION,
      INVALID_DIMENSIONS_MESSAGE
    );
    final int columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
    Preconditions.checkArgument(roots.size() == columns * ((height + ROOT_SIZE - 1) / ROOT_SIZE), "Wrong root count!");

    for (final TreeNode root : roots) {
      validateTree(root, ROOT_SIZE, kind);
    }

    final List<TreeNode> flat = new ArrayList<>();
    final int[] levels = new int[BLOCK_SIZES];
    List<TreeNode> level = new ArrayList<>();
    for (final TreeNode root : roots) {
      if (root.mode != MODE_SKIP) {
        level.add(root);
      }
    }

    for (int depth = 0; depth < BLOCK_SIZES; depth++) {
      levels[depth] = level.size();
      level = flattenLevel(level, flat);
    }

    final int groups = (roots.size() + GROUP_ROOTS - 1) / GROUP_ROOTS;
    final int checkpoints = (groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS;
    final int countsAt = HEADER_BYTES + (groups + checkpoints) * Integer.BYTES;
    final int descriptorsAt = countsAt + BLOCK_SIZES * Integer.BYTES;
    final int walksAt = descriptorsAt + flat.size();
    final int start = walksAt + ((flat.size() + WALK_SPAN - 1) / WALK_SPAN) * Integer.BYTES;
    int length = start;
    for (final TreeNode node : flat) {
      length += node.record.length;
    }

    if (length > MAXIMUM_FRAME_BYTES) {
      return null;
    }

    final byte[] data = new byte[length];
    putUnsignedInt(data, MAGIC_OFFSET, MAGIC);
    putUnsignedInt(data, VERSION_OFFSET, VERSION);
    putUnsignedShort(data, DIMENSIONS_OFFSET, width);
    putUnsignedShort(data, DIMENSIONS_OFFSET + Short.BYTES, height);
    putUnsignedInt(data, FRAME_ID_OFFSET, frameId);
    putUnsignedInt(data, REFERENCE_ID_OFFSET, referenceId);
    int seen = 0;
    for (int group = 0; group < groups; group++) {
      final long mask = presenceMask(roots, group * GROUP_ROOTS);
      putUnsignedInt(data, HEADER_BYTES + group * Integer.BYTES, mask);
      if (group % CHECKPOINT_GROUPS == 0) {
        putUnsignedInt(data, HEADER_BYTES + (groups + group / CHECKPOINT_GROUPS) * Integer.BYTES, seen);
      }
      seen += Long.bitCount(mask);
    }

    for (int index = 0; index < BLOCK_SIZES; index++) {
      putUnsignedInt(data, countsAt + index * Integer.BYTES, levels[index]);
    }

    int cursor = 0;
    int splits = 0;
    for (int index = 0; index < flat.size(); index++) {
      final TreeNode node = flat.get(index);
      if (index % WALK_SPAN == 0) {
        putUnsignedInt(data, walksAt + (index / WALK_SPAN) * Integer.BYTES, cursor | ((long) splits << CURSOR_BITS));
      }
      data[descriptorsAt + index] = (byte) (node.mode | (node.quantizer << QUANTIZER_SHIFT));
      if (node.isSplit()) {
        splits++;
        continue;
      }
      final byte[] record = node.record;
      System.arraycopy(record, 0, data, start + cursor, record.length);
      cursor += record.length;
    }

    return data;
  }

  private static List<TreeNode> flattenLevel(final List<TreeNode> level, final List<TreeNode> flat) {
    final List<TreeNode> children = new ArrayList<>();
    for (final TreeNode node : level) {
      flat.add(node);
      if (node.isSplit()) {
        addChildren(node, children);
      }
    }

    return children;
  }

  private static void addChildren(final TreeNode node, final List<TreeNode> children) {
    for (int corner = 0; corner < QUARTERS; corner++) {
      children.add(node.getChild(corner));
    }
  }

  private static long presenceMask(final List<TreeNode> roots, final int from) {
    long mask = 0;
    for (int index = from; index < Math.min(roots.size(), from + GROUP_ROOTS); index++) {
      if (roots.get(index).mode != MODE_SKIP) {
        mask |= 1L << (index - from);
      }
    }

    return mask;
  }

  private static void validateTree(final TreeNode node, final int size, final Mcv2FrameKind kind) {
    if (node.isSplit()) {
      Preconditions.checkArgument(size > SMALLEST_BLOCK, "Split below the bounded depth!");

      for (int corner = 0; corner < QUARTERS; corner++) {
        validateTree(node.getChild(corner), size / QUARTERS_PER_SIDE, kind);
      }

      return;
    }

    final int mode = node.mode;
    Preconditions.checkArgument(mode <= MODE_COMPACT, "Leaf mode %s is illegal!", mode);
    Preconditions.checkArgument(mode == MODE_COMPACT || node.quantizer == 0, "Quantizer on a mode without one!");
    Preconditions.checkArgument(
      kind == Mcv2FrameKind.PREDICTED || (mode != MODE_MOTION && mode != MODE_COMPACT),
      "The tree does not serialize to a valid frame: temporal keyframe leaf!"
    );
    final byte[] record = node.record;
    final int length = recordSize(mode, size);
    Preconditions.checkArgument(record.length == length, "Record length disagrees with its mode!");

    if (mode == MODE_PATTERN) {
      Preconditions.checkArgument((record[PALETTE_COLORS * CHANNELS] & BYTE_MASK) <= 1, "Invalid pattern orientation!");
    }
  }

  private static boolean writePatternRecord(final byte[] paletteRecord, final int size, final byte[] patternOutput) {
    for (int orientation = 0; orientation < PATTERN_ORIENTATIONS; orientation++) {
      if (isPatternRepeated(paletteRecord, size, orientation)) {
        writePattern(paletteRecord, size, orientation, patternOutput);

        return true;
      }
    }

    return false;
  }

  private static boolean isPatternRepeated(final byte[] paletteRecord, final int size, final int orientation) {
    for (int row = 0; row < size; row++) {
      if (!isPatternRowRepeated(paletteRecord, size, orientation, row)) {
        return false;
      }
    }

    return true;
  }

  private static boolean isPatternRowRepeated(final byte[] paletteRecord, final int size, final int orientation, final int row) {
    for (int column = 0; column < size; column++) {
      final int axis = orientation == 0 ? paletteBit(paletteRecord, column) : paletteBit(paletteRecord, row * size);
      if (paletteBit(paletteRecord, row * size + column) != axis) {
        return false;
      }
    }

    return true;
  }

  private static void writePattern(final byte[] paletteRecord, final int size, final int orientation, final byte[] patternOutput) {
    System.arraycopy(paletteRecord, 0, patternOutput, 0, PALETTE_COLORS * CHANNELS);
    patternOutput[PALETTE_COLORS * CHANNELS] = (byte) orientation;
    final int arrayOffset = PALETTE_COLORS * CHANNELS + 1;
    Arrays.fill(patternOutput, arrayOffset, arrayOffset + size / Byte.SIZE, (byte) 0);
    for (int position = 0; position < size; position++) {
      final int value = orientation == 0 ? paletteBit(paletteRecord, position) : paletteBit(paletteRecord, position * size);
      patternOutput[arrayOffset + position / Byte.SIZE] |= (byte) (value << (position % Byte.SIZE));
    }
  }

  private static int paletteBit(final byte[] paletteRecord, final int index) {
    return (paletteRecord[PALETTE_COLORS * CHANNELS + index / Byte.SIZE] >> (index % Byte.SIZE)) & 1;
  }

  // Reference assembly and reconstruction verification.
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
      final int half = size / QUARTERS_PER_SIDE;
      for (int corner = 0; corner < QUARTERS; corner++) {
        fillMotion(
          field,
          width,
          height,
          node.getChild(corner),
          left + (corner % QUARTERS_PER_SIDE) * half,
          top + (corner / QUARTERS_PER_SIDE) * half,
          half
        );
      }

      return;
    }

    final byte[] record = node.record;
    final int mode = node.mode;
    final int vector = mode == MODE_MOTION || mode == MODE_COMPACT ? packMotion(record[0], record[1]) : 0;
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
      if (this.verificationPicture.length != pending.picture.length) {
        this.verificationPicture = new byte[pending.picture.length];
      }

      final byte[] decoded = this.verificationPicture;
      final int bands = (frame.getHeight() + ROOT_SIZE - 1) / ROOT_SIZE;
      final AtomicReference<@Nullable Mcv2Exception> failure = new AtomicReference<>();
      final AtomicBoolean same = new AtomicBoolean(true);
      this.workers.forEach(bands, band -> this.verifyBand(frame, pending, decoded, band, failure, same));
      final Mcv2Exception error = failure.get();
      if (error != null) {
        throw error;
      }

      Preconditions.checkState(same.get(), "MCV2 live picture and decoded picture disagree!");
    } catch (final Mcv2Exception exception) {
      final String message = "The encoder wrote a frame the decoder rejects!";
      throw new IllegalStateException(message, exception);
    }
  }

  private void verifyBand(
    final Mcv2Decoder.Frame frame,
    final Pending pending,
    final byte[] decoded,
    final int band,
    final AtomicReference<@Nullable Mcv2Exception> failure,
    final AtomicBoolean same
  ) {
    final int from = band * ROOT_SIZE;
    final int to = Math.min(frame.getHeight(), from + ROOT_SIZE);
    try {
      Mcv2Decoder.decodeRows(frame, pending.predictFrom, pending.referenceId, decoded, from, to);
    } catch (final Mcv2Exception exception) {
      failure.compareAndSet(null, exception);
      return;
    }

    final int first = from * frame.getWidth() * CHANNELS;
    final int last = to * frame.getWidth() * CHANNELS;
    if (!Arrays.equals(decoded, first, last, pending.picture, first, last)) {
      same.set(false);
    }
  }

  /**
   * Limits search time per frame; later roots use their cheapest candidate.
   *
   * @param nanoseconds nonnegative budget, or zero for unlimited time
   * @throws IllegalArgumentException if the budget is negative
   * @since 1.0.0
   */
  public void setFrameBudget(final long nanoseconds) {
    Preconditions.checkArgument(nanoseconds >= 0, "The frame budget must not be negative!");

    this.frameBudget = nanoseconds;
  }

  /**
   * Sets the desired byte limit used for lambda retries and trivial-frame fallback.
   * The trivial keyframe may exceed a limit smaller than its required mean-colour roots;
   * every result remains within the format's 131,071-byte bound.
   *
   * @param bytes nonnegative desired limit, or zero for the format maximum
   * @throws IllegalArgumentException if bytes is negative
   * @since 1.0.0
   */
  public void setFrameLimit(final int bytes) {
    Preconditions.checkArgument(bytes >= 0, "The frame limit must not be negative!");

    this.frameLimit = bytes;
  }

  /**
   * Returns the settings used by the next frame.
   *
   * @return current settings
   * @since 1.0.0
   */
  public Settings getSettings() {
    return this.settings;
  }

  /**
   * Returns the last completed frame's measurements.
   *
   * @return statistics, or empty before the first finish
   * @since 1.0.0
   */
  public Optional<Statistics> getStatistics() {
    return Optional.ofNullable(this.statistics);
  }

  // Public API and stream state.
  /**
   * Whether an encoder checks every frame it finishes by decoding it again.
   *
   * <p>This enum is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public enum Verification {
    /**
     * Finish decodes each frame with Mcv2Decoder and checks every reconstructed pixel.
     *
     * @since 1.0.0
     */
    VERIFIED,

    /**
     * Finish trusts the reconstruction without decoding the frame again.
     *
     * @since 1.0.0
     */
    UNVERIFIED,
  }

  /**
   * Controls the live search without changing the version 3 syntax.
   *
   * <p>This class is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public static final class Settings {

    /**
     * Normal live thresholds with lambda 72.
     *
     * @since 1.0.0
     */
    public static final Settings DEFAULT = of(72, false);

    /**
     * Fast live thresholds with lambda 55.
     *
     * @since 1.0.0
     */
    public static final Settings FAST = of(55, true);

    private final double lambda;
    private final boolean fast;

    private Settings(final double lambda, final boolean fast) {
      this.lambda = lambda;
      this.fast = fast;
    }

    /**
     * Returns settings of the given rate cost and thresholds.
     *
     * @param lambda the finite, non-negative cost of a coded bit relative to distortion
     * @param fast   whether the search uses the fast thresholds
     * @return the settings
     * @throws IllegalArgumentException if {@code lambda} is negative or not finite
     * @since 1.0.0
     */
    public static Settings of(final double lambda, final boolean fast) {
      Preconditions.checkArgument(lambda >= 0 && Double.isFinite(lambda), "Lambda must be finite and non-negative!");

      return new Settings(lambda, fast);
    }

    /**
     * Copies these settings with another rate cost.
     *
     * @param value finite, nonnegative lambda
     * @return the copied settings
     * @throws IllegalArgumentException if value is negative or nonfinite
     * @since 1.0.0
     */
    public Settings withLambda(final double value) {
      return of(value, this.fast);
    }

    /**
     * Returns the cost of a coded bit relative to distortion.
     *
     * @return the finite, non-negative cost of a coded bit relative to distortion
     * @since 1.0.0
     */
    public double getLambda() {
      return this.lambda;
    }

    /**
     * Returns whether the search uses the fast thresholds.
     *
     * @return whether the search uses the fast thresholds
     * @since 1.0.0
     */
    public boolean isFast() {
      return this.fast;
    }

    /**
     * Returns whether {@code other} is a settings with the same parts.
     *
     * @param other the object to compare with
     * @return whether every part matches
     * @since 1.0.0
     */
    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final Settings settings)) {
        return false;
      }

      return Double.compare(this.lambda, settings.lambda) == 0 && this.fast == settings.fast;
    }

    /**
     * Returns a hash code built from every part.
     *
     * @return the hash code
     * @since 1.0.0
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.lambda, this.fast);
    }

    /**
     * Returns a debugging representation with every part.
     *
     * @return the representation
     * @since 1.0.0
     */
    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("lambda", this.lambda).add("fast", this.fast).toString();
    }
  }

  /**
   * Measurements of the most recently finished frame.
   *
   * <p>This class is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public static final class Statistics {

    private final int bytes;
    private final boolean keyframe;
    private final int leaves;
    private final long nanoseconds;
    private final double lambda;

    private Statistics(final int bytes, final boolean keyframe, final int leaves, final long nanoseconds, final double lambda) {
      this.bytes = bytes;
      this.keyframe = keyframe;
      this.leaves = leaves;
      this.nanoseconds = nanoseconds;
      this.lambda = lambda;
    }

    /**
     * Returns the measurements of one frame.
     *
     * @param bytes       the encoded frame length
     * @param keyframe    whether the frame decodes independently
     * @param leaves      the number of chosen leaves, including absent roots
     * @param nanoseconds the elapsed search and writing time
     * @param lambda      the effective lambda, including motion adjustment and any size retries
     * @return the statistics
     * @since 1.0.0
     */
    public static Statistics of(final int bytes, final boolean keyframe, final int leaves, final long nanoseconds, final double lambda) {
      return new Statistics(bytes, keyframe, leaves, nanoseconds, lambda);
    }

    /**
     * Returns the encoded frame length.
     *
     * @return the encoded frame length
     * @since 1.0.0
     */
    public int getBytes() {
      return this.bytes;
    }

    /**
     * Returns whether the frame decodes independently.
     *
     * @return whether the frame decodes independently
     * @since 1.0.0
     */
    public boolean isKeyframe() {
      return this.keyframe;
    }

    /**
     * Returns the number of chosen leaves, including absent roots.
     *
     * @return the number of chosen leaves, including absent roots
     * @since 1.0.0
     */
    public int getLeaves() {
      return this.leaves;
    }

    /**
     * Returns the elapsed search and writing time in nanoseconds.
     *
     * @return the elapsed search and writing time
     * @since 1.0.0
     */
    public long getNanoseconds() {
      return this.nanoseconds;
    }

    /**
     * Returns the effective lambda, including motion adjustment and any size retries.
     *
     * @return the effective lambda, including motion adjustment and any size retries
     * @since 1.0.0
     */
    public double getLambda() {
      return this.lambda;
    }

    /**
     * Returns whether {@code other} is a statistics with the same parts.
     *
     * @param other the object to compare with
     * @return whether every part matches
     * @since 1.0.0
     */
    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final Statistics statistics)) {
        return false;
      }

      return (
        this.bytes == statistics.bytes &&
        this.keyframe == statistics.keyframe &&
        this.leaves == statistics.leaves &&
        this.nanoseconds == statistics.nanoseconds &&
        Double.compare(this.lambda, statistics.lambda) == 0
      );
    }

    /**
     * Returns a hash code built from every part.
     *
     * @return the hash code
     * @since 1.0.0
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.bytes, this.keyframe, this.leaves, this.nanoseconds, this.lambda);
    }

    /**
     * Returns a debugging representation with every part.
     *
     * @return the representation
     * @since 1.0.0
     */
    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
        .add("bytes", this.bytes)
        .add("keyframe", this.keyframe)
        .add("leaves", this.leaves)
        .add("nanoseconds", this.nanoseconds)
        .add("lambda", this.lambda)
        .toString();
    }
  }

  /**
   * A finished frame whose bytes and measurements are ready for transmission.
   *
   * <p>This class is immutable and thread-safe, but {@link #getData()} hands its array to the caller without copying
   * it.
   *
   * @since 1.0.0
   */
  public static final class Encoded {

    private final byte[] data;
    private final Statistics statistics;

    private Encoded(final byte[] data, final Statistics statistics) {
      this.data = data;
      this.statistics = statistics;
    }

    /**
     * Returns a finished frame of the given bytes and measurements.
     *
     * @param data       the complete version 3 frame, whose ownership transfers to the finished frame
     * @param statistics the measurements of the frame
     * @return the finished frame
     * @throws NullPointerException if an argument is null
     * @since 1.0.0
     */
    public static Encoded of(final byte[] data, final Statistics statistics) {
      Preconditions.checkNotNull(data, "Data must not be null!");
      Preconditions.checkNotNull(statistics, "Statistics must not be null!");

      return new Encoded(data, statistics);
    }

    /**
     * Returns the encoded bytes, whose ownership transfers to the caller.
     *
     * @return the complete version 3 frame
     * @since 1.0.0
     */
    public byte[] getData() {
      return this.data;
    }

    /**
     * Returns the measurements of this frame.
     *
     * @return the frame statistics
     * @since 1.0.0
     */
    public Statistics getStatistics() {
      return this.statistics;
    }

    /**
     * Returns whether {@code other} is a frame with the same bytes and measurements.
     *
     * @param other the object to compare with
     * @return whether every part matches
     * @since 1.0.0
     */
    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final Encoded encoded)) {
        return false;
      }

      return Arrays.equals(this.data, encoded.data) && this.statistics.equals(encoded.statistics);
    }

    /**
     * Returns a hash code built from every part.
     *
     * @return the hash code
     * @since 1.0.0
     */
    @Override
    public int hashCode() {
      final int dataHash = Arrays.hashCode(this.data);
      return Objects.hash(dataHash, this.statistics);
    }

    /**
     * Returns a debugging representation with the frame length and the measurements.
     *
     * @return the representation
     * @since 1.0.0
     */
    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("dataBytes", this.data.length).add("statistics", this.statistics).toString();
    }
  }

  /**
   * A searched frame awaiting ordered verification by its owning encoder.
   *
   * <p>Instances may be handed to another thread, which finishes each one exactly once.
   *
   * @since 1.0.0
   */
  public static final class Pending {

    private final Mcv2 owner;
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
      final Mcv2 owner,
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

    /**
     * Returns whether this pending frame decodes independently.
     *
     * @return whether the frame is a keyframe
     * @since 1.0.0
     */
    public boolean isKeyframe() {
      return this.keyframe;
    }
  }

  // Frame analysis for motion-dependent rate control.

  private static final class MotionLambda {

    private static final double KNEE = 4.6;

    private static final double EXPONENT = 0.79;

    private static final double MAXIMUM_RAISE = 4.0;

    private static final double SMOOTHING = 1.0 / 16;

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

    private double lambda(final double base) {
      return base * raise(this.motion);
    }

    private static double raise(final double motion) {
      if (!(motion > KNEE)) {
        return 1;
      }

      return Math.min(MAXIMUM_RAISE, Math.pow(motion / KNEE, EXPONENT));
    }

    private void observe(final byte[] sourcePixels, final int width, final int height, final Workers workers) {
      final int columns = (width + SAMPLING - 1) / SAMPLING;
      final int rows = (height + SAMPLING - 1) / SAMPLING;
      if (this.across.length != columns * rows) {
        this.across = new int[columns * rows];
        this.spare = new int[columns * rows];
      }

      final int[] current = blurredLuma(sourcePixels, width, height, workers, this.across, this.spare);
      if (columns != this.columns || rows != this.rows) {
        this.motion = Double.NaN;
      } else {
        this.add(temporalInformation(current, this.previous, workers));
      }

      this.spare = this.previous.length == current.length ? this.previous : new int[current.length];
      this.previous = current;
      this.columns = columns;
      this.rows = rows;
    }

    private void add(final double information) {
      this.motion = Double.isNaN(this.motion) ? information : this.motion + SMOOTHING * (information - this.motion);
    }

    private static int[] blurredLuma(
      final byte[] sourcePixels,
      final int width,
      final int height,
      final Workers workers,
      final int[] across,
      final int[] blurred
    ) {
      final int columns = (width + SAMPLING - 1) / SAMPLING;
      final int rows = (height + SAMPLING - 1) / SAMPLING;
      final int bands = (rows + BAND_ROWS - 1) / BAND_ROWS;

      workers.forEach(bands, band -> blurAcross(sourcePixels, width, rows, band, across));
      workers.forEach(bands, band -> blurDown(columns, rows, band, across, blurred));
      return blurred;
    }

    private static void blurAcross(final byte[] sourcePixels, final int width, final int rows, final int band, final int[] across) {
      final int columns = (width + SAMPLING - 1) / SAMPLING;
      for (int row = band * BAND_ROWS; row < Math.min(rows, (band + 1) * BAND_ROWS); row++) {
        final int line = row * SAMPLING * width;
        int left = luma(sourcePixels, line);
        int middle = left;
        for (int column = 0; column < columns; column++) {
          final int right = column + 1 < columns ? luma(sourcePixels, line + (column + 1) * SAMPLING) : middle;
          across[row * columns + column] = left + middle + right;
          left = middle;
          middle = right;
        }
      }
    }

    private static void blurDown(final int columns, final int rows, final int band, final int[] across, final int[] blurred) {
      for (int row = band * BAND_ROWS; row < Math.min(rows, (band + 1) * BAND_ROWS); row++) {
        final int above = Math.max(row - 1, 0) * columns;
        final int arrayOffset = row * columns;
        final int below = Math.min(row + 1, rows - 1) * columns;
        for (int column = 0; column < columns; column++) {
          blurred[arrayOffset + column] = across[above + column] + across[arrayOffset + column] + across[below + column];
        }
      }
    }

    private static int luma(final byte[] sourcePixels, final int pixel) {
      final int arrayOffset = pixel * CHANNELS;
      return (
        (sourcePixels[arrayOffset] & BYTE_MASK) +
        LUMA_GREEN_WEIGHT * (sourcePixels[arrayOffset + GREEN_OFFSET] & BYTE_MASK) +
        (sourcePixels[arrayOffset + BLUE_OFFSET] & BYTE_MASK)
      );
    }

    private static double temporalInformation(final int[] current, final int[] previous, final Workers workers) {
      final int bands = (current.length + BAND_SAMPLES - 1) / BAND_SAMPLES;
      final long[] sums = new long[bands];
      workers.forEach(bands, band -> {
        long sum = 0;
        for (int sample = band * BAND_SAMPLES; sample < Math.min(current.length, (band + 1) * BAND_SAMPLES); sample++) {
          sum += Math.abs(current[sample] - previous[sample]);
        }

        sums[band] = sum;
      });
      long sum = 0;
      for (final long partial : sums) {
        sum += partial;
      }

      return (double) sum / ((long) current.length * LUMA_SCALE * BOX_AREA);
    }
  }

  // Block selection within the frame budget.
  private static final class Buffers {

    private static final int PICTURE_BUFFERS = 3;

    private final int width;
    private final int height;
    private final int roots;
    private final int columns;
    private final byte[][] pictures;
    private final byte[] half;
    private final byte[] quarter;

    private Buffers(final int width, final int height) {
      this.width = width;
      this.height = height;
      this.columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
      this.roots = this.columns * ((height + ROOT_SIZE - 1) / ROOT_SIZE);
      this.pictures = new byte[PICTURE_BUFFERS][width * height * CHANNELS];
      final int halfWidth = (width + 1) / PYRAMID_SCALE;
      final int halfHeight = (height + 1) / PYRAMID_SCALE;
      this.half = new byte[halfWidth * halfHeight * CHANNELS];
      this.quarter = new byte[((halfWidth + 1) / PYRAMID_SCALE) * ((halfHeight + 1) / PYRAMID_SCALE) * CHANNELS];
    }

    @SuppressWarnings("ReferenceEquality") // pictures are pooled buffers told apart by identity, not content
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
    private final int[] previousMotion;
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
      final int[] previousMotion,
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
      final int cellColumn = Math.min(Math.max(column, 0), this.width - 1) / SMALLEST_BLOCK;
      final int cellRow = Math.min(Math.max(row, 0), this.height - 1) / SMALLEST_BLOCK;
      return field[cellRow * ((this.width + SMALLEST_BLOCK - 1) / SMALLEST_BLOCK) + cellColumn];
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

  private record Choice(TreeNode node, double cost, int leaves) {}

  private static final class BlockCoder {

    private static final int CENTER_SEED = 0;
    private static final int LEFT_SEED = 1;
    private static final int RIGHT_SEED = 2;
    private static final int ABOVE_SEED = 3;
    private static final int BELOW_SEED = 4;
    private static final int PARENT_SEED = 5;
    private static final int MOTION_SEEDS = 6;

    private static final int CENTER_DIVISOR = 2;

    private static final int QUARTER_SCALE = PYRAMID_SCALE * PYRAMID_SCALE;

    private static final int HALF_AREA_DIVISOR = PYRAMID_SCALE * PYRAMID_SCALE;
    private static final int QUARTER_AREA_DIVISOR = QUARTER_SCALE * QUARTER_SCALE;

    private FrameState frame;
    private final int size;
    private final int count;
    private final int[] source;
    private final float[] target;
    private final int[] zeroPrediction;
    private final int[] localPrediction;
    private final int[] reconstruction;
    private final int[] best;
    private final Kernels kernels;
    private final byte[] record = new byte[MAXIMUM_RECORD];
    private final byte[] palette = new byte[MAXIMUM_RECORD];
    private final float[] fit = new float[GRID_NODES];
    private final int[] colors = new int[PALETTE_COLORS * CHANNELS];
    private final byte[] selectors;
    private final int[] seeds = new int[MOTION_SEEDS];
    private final int[] halfSeeds = new int[MOTION_SEEDS];
    private final int[] quarterSeeds = new int[MOTION_SEEDS];
    private final int[] coarseSeed = new int[1];
    private final int[] halfSource;
    private final int[] quarterSource;
    private final int[] clusters = new int[PALETTE_COLORS * CHANNELS];
    private double bestCost;
    private int mode;
    private int quantizer;
    private int length;
    private final byte[] bestRecord = new byte[MAXIMUM_RECORD];
    private double rate;
    private boolean skipped;
    private boolean hurried;
    private @Nullable BlockCoder root;
    private int left;
    private int top;
    private int localVector = NO_VECTOR;
    private boolean clustered;

    private BlockCoder(final FrameState frame, final int size, final Kernels kernels) {
      this.frame = frame;
      this.size = size;
      this.count = size * size;
      this.kernels = kernels;
      this.source = new int[this.count * CHANNELS];
      this.target = new float[this.count];
      this.zeroPrediction = new int[this.count * CHANNELS];
      this.localPrediction = new int[this.count * CHANNELS];
      this.reconstruction = new int[this.count * CHANNELS];
      this.best = new int[this.count * CHANNELS];
      this.selectors = new byte[this.count];
      this.halfSource = new int[((size * size) / HALF_AREA_DIVISOR) * CHANNELS];
      this.quarterSource = new int[((size * size) / QUARTER_AREA_DIVISOR) * CHANNELS];
    }

    private void code(final int left, final int top, final int parent) {
      this.bestCost = Double.POSITIVE_INFINITY;
      this.left = left;
      this.top = top;
      this.clustered = false;
      this.skipped = false;
      this.localVector = NO_VECTOR;
      this.loadSource();
      this.evaluate(parent);
    }

    private void reconstruct() {
      final int left = this.left;
      final int top = this.top;
      final byte[] picture = this.frame.picture;
      final int right = Math.min(this.size, this.frame.width - left);
      final int bottom = Math.min(this.size, this.frame.height - top);
      for (int row = 0; row < bottom; row++) {
        final int from = row * this.size * CHANNELS;
        final int destinationOffset = ((top + row) * this.frame.width + left) * CHANNELS;
        for (int offset = 0; offset < right * CHANNELS; offset++) {
          picture[destinationOffset + offset] = (byte) this.best[from + offset];
        }
      }
    }

    private void evaluate(final int parent) {
      if (!this.frame.keyframe) {
        this.evaluateTemporal(parent);
        if (this.skipped) {
          return;
        }
      }

      this.evaluateSolid();

      if (this.hurried) {
        this.skipped = true;
        return;
      }

      this.evaluatePattern();
      this.evaluatePalette();
    }

    private void evaluateTemporal(final int parent) {
      this.predict(0, this.zeroPrediction);
      this.startMode(0);
      this.kernels.predicted(this.zeroPrediction, this.size, this.reconstruction);
      this.score(MODE_SKIP, 0, 0);

      if (this.hurried || this.cost() <= (this.frame.fast ? FAST_SKIP_BITS : NORMAL_SKIP_BITS) * this.frame.lambda) {
        this.skipped = true;
        return;
      }

      this.localVector = this.size == SMALLEST_BLOCK ? parent : this.searchMotion(parent);
      this.predict(this.localVector, this.localPrediction);
      final boolean closer = this.localVector != 0 && this.scoreMotion();
      if (closer) {
        this.compact(this.localVector, this.localPrediction);
        return;
      }

      this.compact(0, this.zeroPrediction);
    }

    private boolean scoreMotion() {
      // The SKIP bound already pays for MOTION's two bytes.
      this.startMode(MOTION_BYTES);
      this.record[0] = (byte) motionX(this.localVector);
      this.record[1] = (byte) motionY(this.localVector);

      if (!this.kernels.predicted(this.localPrediction, this.size, this.reconstruction)) {
        return false;
      }

      this.score(MODE_MOTION, 0, MOTION_BYTES);

      return true;
    }

    private double cost() {
      return this.bestCost;
    }

    private void startMode(final int length) {
      final double bits = length == 0 && this.size == ROOT_SIZE ? 1 : INDEX_BITS;
      this.rate = Math.min(this.frame.lambda * (length * Byte.SIZE + bits), Double.MAX_VALUE);
      final double best = this.cost();
      this.kernels.start(this.source, this.rate, best);
    }

    private boolean isRateBelowBestCost() {
      return this.bestCost > this.rate;
    }

    private void score(final int mode, final int quantizer, final int length) {
      final long distortion = this.kernels.getDistortion();
      final double cost = distortion / DISTORTION_SCALE + this.rate;

      System.arraycopy(this.reconstruction, 0, this.best, 0, this.reconstruction.length);
      this.bestCost = cost;
      this.mode = mode;
      this.quantizer = quantizer;
      this.length = length;
      System.arraycopy(this.record, 0, this.bestRecord, 0, length);
    }

    private void loadSource() {
      final BlockCoder root = this.root;
      if (root != null) {
        this.copyFromRoot(root, root.source, this.source);
      } else {
        this.kernels.loadSource(this.frame.source, this.frame.width, this.frame.height, this.left, this.top, this.size, this.source);
      }
    }

    private void copyFromRoot(final BlockCoder root, final int[] from, final int[] output) {
      final int rowLength = this.size * CHANNELS;
      for (int row = 0; row < this.size; row++) {
        System.arraycopy(
          from,
          ((this.top - root.top + row) * ROOT_SIZE + this.left - root.left) * CHANNELS,
          output,
          row * rowLength,
          rowLength
        );
      }
    }

    private void predict(final int vector, final int[] output) {
      final BlockCoder root = this.root;
      final int[] from = root == null ? null : vector == 0 ? root.zeroPrediction : vector == root.localVector ? root.localPrediction : null;
      if (root != null && from != null) {
        this.copyFromRoot(root, from, output);
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
          output
        );
      }
    }

    private int searchMotion(final int parent) {
      final FrameState frame = this.frame;
      final int half = this.size / CENTER_DIVISOR;
      this.seeds[CENTER_SEED] = frame.previousMotion(this.left + half, this.top + half);
      this.seeds[LEFT_SEED] = frame.previousMotion(this.left - 1, this.top + half);
      this.seeds[RIGHT_SEED] = frame.previousMotion(this.left + this.size, this.top + half);
      this.seeds[ABOVE_SEED] = frame.previousMotion(this.left + half, this.top - 1);
      this.seeds[BELOW_SEED] = frame.previousMotion(this.left + half, this.top + this.size);
      this.seeds[PARENT_SEED] = parent == NO_VECTOR ? this.seeds[CENTER_SEED] : parent;
      this.coarseSeed[0] = this.coarseMotion(PYRAMID_SCALE);

      return this.kernels.seeded(
        frame.reference,
        frame.width,
        frame.height,
        this.source,
        this.left,
        this.top,
        this.size,
        MOTION_RANGE,
        this.coarseSeed
      );
    }

    private int coarseMotion(final int divisor) {
      final FrameState frame = this.frame;
      final boolean half = divisor == PYRAMID_SCALE;
      final int[] source = half ? this.halfSource : this.quarterSource;
      this.kernels.halve(half ? this.source : this.halfSource, (this.size * PYRAMID_SCALE) / divisor, source);
      int[] seeds = half ? this.halfSeeds : this.quarterSeeds;
      if (half && !frame.fast && this.size == ROOT_SIZE) {
        this.coarseSeed[0] = this.coarseMotion(QUARTER_SCALE);
        seeds = this.coarseSeed;
      } else {
        for (int index = 0; index < this.seeds.length; index++) {
          seeds[index] = packMotion(motionX(this.seeds[index]) / divisor, motionY(this.seeds[index]) / divisor);
        }
      }

      final int coarse = this.kernels.seeded(
        half ? frame.buffers.half : frame.buffers.quarter,
        (frame.width + divisor - 1) / divisor,
        (frame.height + divisor - 1) / divisor,
        source,
        this.left / divisor,
        this.top / divisor,
        this.size / divisor,
        MOTION_RANGE / divisor,
        seeds
      );
      return packMotion(motionX(coarse) * PYRAMID_SCALE, motionY(coarse) * PYRAMID_SCALE);
    }

    private int[] endpoints() {
      if (!this.clustered) {
        this.kernels.cluster(this.source, this.size, this.clusters);
        this.clustered = true;
      }

      return this.clusters;
    }

    private void evaluateSolid() {
      this.startMode(CHANNELS);
      if (!this.isRateBelowBestCost()) {
        return;
      }

      long redSum = 0;
      long greenSum = 0;
      long blueSum = 0;
      for (int pixel = 0; pixel < this.count; pixel++) {
        redSum += this.source[pixel * CHANNELS];
        greenSum += this.source[pixel * CHANNELS + GREEN_OFFSET];
        blueSum += this.source[pixel * CHANNELS + BLUE_OFFSET];
      }

      final int red = (int) ((redSum + this.count / ROUNDING_DIVISOR) / this.count);
      final int green = (int) ((greenSum + this.count / ROUNDING_DIVISOR) / this.count);
      final int blue = (int) ((blueSum + this.count / ROUNDING_DIVISOR) / this.count);
      this.record[0] = (byte) red;
      this.record[GREEN_OFFSET] = (byte) green;
      this.record[BLUE_OFFSET] = (byte) blue;

      if (this.kernels.solid((red << RED_SHIFT) | (green << GREEN_SHIFT) | blue, this.size, this.reconstruction)) {
        this.score(MODE_SOLID, 0, CHANNELS);
      }
    }

    private void writePalette(final byte[] target) {
      for (int index = 0; index < PALETTE_COLORS * CHANNELS; index++) {
        target[index] = (byte) this.colors[index];
      }

      Arrays.fill(target, PALETTE_COLORS * CHANNELS, PALETTE_COLORS * CHANNELS + this.count / Byte.SIZE, (byte) 0);
      for (int pixel = 0; pixel < this.count; pixel++) {
        target[PALETTE_COLORS * CHANNELS + pixel / Byte.SIZE] |= (byte) (this.selectors[pixel] << (pixel % Byte.SIZE));
      }
    }

    private void evaluatePalette() {
      final int length = recordSize(MODE_PALETTE, this.size);
      this.startMode(length);
      if (!this.isRateBelowBestCost()) {
        return;
      }

      this.kernels.finishPalette(this.source, this.count, this.endpoints(), this.colors, this.selectors);
      this.writePalette(this.record);

      if (this.kernels.palette(this.record, this.size, this.reconstruction)) {
        this.score(MODE_PALETTE, 0, length);
      }
    }

    private void evaluatePattern() {
      final int length = patternSize(this.size);
      this.startMode(length);
      if (!this.isRateBelowBestCost()) {
        return;
      }

      if (!this.kernels.finishPattern(this.source, this.size, this.endpoints(), this.colors, this.selectors)) {
        return;
      }

      this.writePalette(this.palette);
      writePatternRecord(this.palette, this.size, this.record);

      if (this.kernels.palette(this.palette, this.size, this.reconstruction)) {
        this.score(MODE_PATTERN, 0, length);
      }
    }

    private void compact(final int vector, final int[] prediction) {
      this.startMode(COMPACT_BYTES);
      if (!this.isRateBelowBestCost()) {
        return;
      }

      this.kernels.residualTarget(this.source, prediction, this.count, this.target);
      this.kernels.fit(this.target, this.size, this.fit);
      final int quantizer = neededQuantizer(this.fit);
      this.record[0] = (byte) motionX(vector);
      this.record[1] = (byte) motionY(vector);
      final int step = 1 << quantizer;
      for (int index = 0; index < GRID_NODES / NODES_PER_BYTE; index++) {
        final int low = quantize(this.fit[NODES_PER_BYTE * index], step) & NODE_MASK;
        final int high = quantize(this.fit[NODES_PER_BYTE * index + 1], step) & NODE_MASK;
        this.record[MOTION_BYTES + index] = (byte) (low | (high << NODE_BITS));
      }

      if (this.kernels.compact(prediction, this.record, quantizer, this.size, this.reconstruction)) {
        this.score(MODE_COMPACT, quantizer, COMPACT_BYTES);
      }
    }
  }

  enum Level {
    SCALAR(1, "scalar"),
    SSE2(16, "sse2"),
    SSE41(2, "sse41"),
    AVX2(4, "avx2"),
    // This level requires Ice Lake's F, DQ, BW, VL, VBMI, VBMI2, VNNI and BITALG extensions.
    AVX512(32, "avx512"),
    NEON(8, "neon"),
    SVE256(64, "sve256"),
    SVE512(128, "sve512");

    private final int bit;

    private final String symbol;

    Level(final int bit, final String symbol) {
      this.bit = bit;
      this.symbol = symbol;
    }

    boolean isIn(final int levels) {
      return (levels & this.bit) != 0;
    }

    String getSymbol() {
      return this.symbol;
    }
  }

  record Resolution(@Nullable Binding binding, int levels, String description, boolean failed) {
    private static final Supplier<Kernels> JAVA = JavaKernels::new;

    private Supplier<Kernels> factory() {
      final Binding bound = this.binding;
      return bound == null ? JAVA : () -> new NativeKernels(bound);
    }

    static Resolution java(final String reason) {
      return new Resolution(null, 0, "Java, " + reason, false);
    }

    static Resolution failure(final String reason) {
      return new Resolution(null, 0, "Java, " + reason, true);
    }
  }

  static final class Natives {

    private static final Logger LOGGER = LoggerFactory.getLogger(Mcv2.class);

    private static final Pattern DIGEST_ENTRY = Pattern.compile("([0-9a-f]{64})  ((?:linux|macos|windows)-(?:x86_64|aarch64))/(.+)");

    static final int ABI_VERSION = 5;

    private static final String LEVEL_PROPERTY = "mcv2.native.level";

    private static final Path AUXILIARY_VECTOR_FILE = Path.of("/proc/self/auxv");

    private static final long HARDWARE_CAPABILITIES_TYPE = 16;

    private static final Level[] PREFERENCE = {
      Level.AVX512,
      Level.AVX2,
      Level.SSE41,
      Level.SSE2,
      Level.SVE512,
      Level.SVE256,
      Level.NEON,
      Level.SCALAR,
    };

    private static final FunctionDescriptor QUERY = FunctionDescriptor.of(JAVA_INT);

    private static final FunctionDescriptor LEVELS = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);

    private static final String OPERATING_SYSTEM_NAME_PROPERTY = "os.name";
    private static final String PROCESSOR_ARCHITECTURE_PROPERTY = "os.arch";

    private static final String RESOURCE_SEPARATOR = "/";

    private static final String LINUX_NAME_PREFIX = "linux";
    private static final String WINDOWS_NAME_PREFIX = "windows";
    private static final String MAC_NAME_PREFIX = "mac";

    private static final String LINUX = "linux";
    private static final String WINDOWS = "windows";
    private static final String MACOS = "macos";

    private static final String AMD64 = "amd64";
    private static final String X86_64 = "x86_64";
    private static final String AARCH64 = "aarch64";
    private static final String ARM64 = "arm64";

    private static final String X86_64_SUFFIX = "-x86_64";
    private static final String AARCH64_SUFFIX = "-aarch64";

    private static final String WINDOWS_LIBRARY = "mcv2kernels.dll";
    private static final String MACOS_LIBRARY = "libmcv2kernels.dylib";
    private static final String LINUX_LIBRARY = "libmcv2kernels.so";

    private static final String EXTRACTED_PREFIX = "mcv2kernels-";
    private static final String EXTRACTED_SEPARATOR = "-";

    private static final String PARTIAL_SUFFIX = ".partial";

    private static final String ABI_SYMBOL = "mcv2_abi";
    private static final String CPU_LEVELS_SYMBOL = "mcv2_cpu_levels";

    private static final int FEATURES_ARGUMENT = 0;

    private static final int AUXILIARY_ENTRY_BYTES = 2 * Long.BYTES;

    private static final int DIGEST_GROUP = 1;
    private static final int PLATFORM_GROUP = 2;
    private static final int FILE_GROUP = 3;

    private static @Nullable Path directory;

    private static String configured = NATIVE_AUTO;

    private static @Nullable Resolution resolution;

    private Natives() {
      throw new UnsupportedOperationException("Utility class cannot be instantiated!");
    }

    static synchronized void install(final Path folder, final String mode) {
      Preconditions.checkNotNull(mode, "Native mode must not be null!");
      Preconditions.checkArgument(NATIVE_AUTO.equals(mode) || NATIVE_OFF.equals(mode), "The MCV2 native mode must be auto or off!");

      directory = Preconditions.checkNotNull(folder, "Native folder must not be null!");
      configured = mode;
      resolution = null;
    }

    static synchronized Resolution resolved() {
      Resolution current = resolution;
      if (current == null) {
        current = resolve(
          System.getProperty(NATIVE_PROPERTY, configured),
          platform(System.getProperty(OPERATING_SYSTEM_NAME_PROPERTY, ""), System.getProperty(PROCESSOR_ARCHITECTURE_PROPERTY, "")),
          directory,
          platform -> read(Mcv2.class.getResourceAsStream(NATIVE_RESOURCES + platform + RESOURCE_SEPARATOR + libraryName(platform))),
          System.getProperty(LEVEL_PROPERTY)
        );
        if (current.failed()) {
          LOGGER.warn("MCV2 native kernels did not load ({}), so the Java kernels run.", current.description());
        }

        LOGGER.info("MCV2 uses the {} kernels.", current.description());
        resolution = current;
      }

      return current;
    }

    static @Nullable String platform(final String operatingSystemName, final String processorArchitecture) {
      final String name = operatingSystemName.toLowerCase(Locale.ROOT);
      final String operatingSystem;
      if (name.startsWith(LINUX_NAME_PREFIX)) {
        operatingSystem = LINUX;
      } else if (name.startsWith(WINDOWS_NAME_PREFIX)) {
        operatingSystem = WINDOWS;
      } else if (name.startsWith(MAC_NAME_PREFIX)) {
        operatingSystem = MACOS;
      } else {
        return null;
      }

      return switch (processorArchitecture.toLowerCase(Locale.ROOT)) {
        case AMD64, X86_64 -> operatingSystem + X86_64_SUFFIX;
        case AARCH64, ARM64 -> operatingSystem + AARCH64_SUFFIX;
        default -> null;
      };
    }

    static String libraryName(final String platform) {
      if (platform.startsWith(WINDOWS)) {
        return WINDOWS_LIBRARY;
      }

      return platform.startsWith(MACOS) ? MACOS_LIBRARY : LINUX_LIBRARY;
    }

    static byte @Nullable [] read(final @Nullable InputStream stream) {
      if (stream == null) {
        return null;
      }

      try (stream) {
        return stream.readAllBytes();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    }

    static Map<String, String> digests(final @Nullable InputStream stream) {
      final byte[] bytes;
      try {
        bytes = read(stream);
      } catch (final UncheckedIOException exception) {
        return Map.of();
      }

      if (bytes == null) {
        return Map.of();
      }

      final Map<String, String> digests = new HashMap<>();
      for (final String line : new String(bytes, StandardCharsets.US_ASCII).lines().toList()) {
        final Matcher matcher = DIGEST_ENTRY.matcher(line);
        if (!matcher.matches()) {
          return Map.of();
        }
        final String platform = requireNonNull(matcher.group(PLATFORM_GROUP));
        if (
          !libraryName(platform).equals(matcher.group(FILE_GROUP)) ||
          digests.put(platform, requireNonNull(matcher.group(DIGEST_GROUP))) != null
        ) {
          return Map.of();
        }
      }

      return Map.copyOf(digests);
    }

    static Resolution resolve(
      final String mode,
      final @Nullable String platform,
      final @Nullable Path folder,
      final Function<String, byte @Nullable []> resources,
      final @Nullable String highest
    ) {
      if (NATIVE_OFF.equals(mode)) {
        return Resolution.java("turned off by " + NATIVE_PROPERTY + "=" + NATIVE_OFF);
      }

      if (!NATIVE_AUTO.equals(mode)) {
        return Resolution.failure("unknown mode " + NATIVE_PROPERTY + "=" + mode);
      }

      if (platform == null) {
        return Resolution.java("no library for this operating system and processor");
      }

      final String digest = NATIVE_DIGESTS.get(platform);
      final byte[] bytes;
      try {
        bytes = digest == null ? null : resources.apply(platform);
      } catch (final UncheckedIOException exception) {
        return Resolution.failure("the library could not be read: " + exception);
      }

      if (digest == null || bytes == null) {
        return Resolution.java("no library for " + platform);
      }

      return load(platform, digest, bytes, folder, highest);
    }

    @SuppressWarnings("restricted") // loading the kernel library is a restricted method of the foreign function API
    static Resolution load(
      final String platform,
      final String digest,
      final byte[] bytes,
      final @Nullable Path folder,
      final @Nullable String highest
    ) {
      if (!digest.equals(Mcv2Resources.sha256(bytes))) {
        return Resolution.failure("the library for %s does not match its SHA-256".formatted(platform));
      }

      if (folder == null) {
        return Resolution.failure("no folder was given to extract the library into");
      }

      final Path file;
      try {
        file = extract(folder, EXTRACTED_PREFIX + digest + EXTRACTED_SEPARATOR + libraryName(platform), bytes, digest);
      } catch (final IOException exception) {
        return Resolution.failure("the library could not be extracted: %s".formatted(exception));
      }

      final SymbolLookup library;
      try {
        library = SymbolLookup.libraryLookup(file, Arena.global());
      } catch (final IllegalCallerException | IllegalArgumentException exception) {
        return Resolution.failure("the library could not be loaded: %s".formatted(exception));
      }

      return bind(library, platform, highest, ABI_VERSION);
    }

    static Path extract(final Path folder, final String name, final byte[] bytes, final String digest) throws IOException {
      Files.createDirectories(folder);
      final Path file = folder.resolve(name);
      if (Files.isRegularFile(file) && digest.equals(Mcv2Resources.sha256(Files.readAllBytes(file)))) {
        return file;
      }

      // Atomic replacement prevents concurrent readers from loading a partial library.
      final Path partial = Files.createTempFile(folder, name, PARTIAL_SUFFIX);
      try {
        Files.write(partial, bytes);
        Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE);
      } finally {
        Files.deleteIfExists(partial);
      }

      return file;
    }

    @SuppressWarnings("restricted") // the Linker downcalls are restricted methods of the foreign function API
    static Resolution bind(final SymbolLookup library, final String platform, final @Nullable String highest, final int expected) {
      final Linker linker = Linker.nativeLinker();
      try {
        final int abiVersion = (int) linker.downcallHandle(library.findOrThrow(ABI_SYMBOL), QUERY).invokeExact();
        if (abiVersion != expected) {
          return Resolution.failure("the library's interface %s is not %s".formatted(abiVersion, expected));
        }

        // The dependency-free AArch64 library cannot query the kernel for SVE permission.
        final long hardwareCapabilities = platform.startsWith(LINUX) ? readHardwareCapabilities(AUXILIARY_VECTOR_FILE) : 0;
        final MethodHandle cpuLevels = MethodHandles.insertArguments(
          linker.downcallHandle(library.findOrThrow(CPU_LEVELS_SYMBOL), LEVELS),
          FEATURES_ARGUMENT,
          hardwareCapabilities
        );
        final int levels = (int) cpuLevels.invokeExact();
        final Level level = selectLevel(levels, highest);
        return new Resolution(Binding.of(library, level), levels, "native %s (%s)".formatted(level.getSymbol(), platform), false);
      } catch (final Throwable exception) {
        return Resolution.failure("the library could not be bound: %s".formatted(exception));
      }
    }

    static long readHardwareCapabilities(final Path auxiliaryVectorFile) {
      final byte[] bytes;
      try {
        bytes = Files.readAllBytes(auxiliaryVectorFile);
      } catch (final IOException exception) {
        LOGGER.debug("Could not read the auxiliary vector {}, so no hardware capabilities are assumed.", auxiliaryVectorFile, exception);
        return 0;
      }

      final ByteBuffer entries = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
      while (entries.remaining() >= AUXILIARY_ENTRY_BYTES) {
        final long type = entries.getLong();
        final long value = entries.getLong();
        if (type == HARDWARE_CAPABILITIES_TYPE) {
          return value;
        }
      }

      return 0;
    }

    static Level selectLevel(final int levels, final @Nullable String highest) {
      boolean allowed = highest == null;
      for (final Level level : PREFERENCE) {
        allowed |= level.getSymbol().equals(highest);
        if (allowed && level.isIn(levels)) {
          return level;
        }
      }

      return Level.SCALAR;
    }
  }

  static final class Binding {

    private static final Linker.Option CRITICAL = Linker.Option.critical(true);

    private static final String SYMBOL_PREFIX = "mcv2_";
    private static final String SYMBOL_SEPARATOR = "_";

    private static final FunctionDescriptor SCORED_PREDICTION = scored(ADDRESS, JAVA_INT);

    private static final FunctionDescriptor SCORED_SOLID = scored(JAVA_INT, JAVA_INT);

    private static final FunctionDescriptor SCORED_COMPACT = scored(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT);

    private static final FunctionDescriptor PREDICT = FunctionDescriptor.ofVoid(
      ADDRESS,
      JAVA_INT,
      JAVA_INT,
      JAVA_INT,
      JAVA_INT,
      JAVA_INT,
      JAVA_INT,
      JAVA_INT,
      ADDRESS
    );

    private static final FunctionDescriptor FIT = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS);

    private static final FunctionDescriptor BLOCK_TO_ARRAY = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);

    private static final FunctionDescriptor ASSIGN = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS);

    private static final FunctionDescriptor ASSIGN_PATTERN = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);

    private static final FunctionDescriptor SEEDED = FunctionDescriptor.of(
      JAVA_INT,
      ADDRESS,
      JAVA_INT,
      JAVA_INT,
      ADDRESS,
      JAVA_INT,
      JAVA_INT,
      JAVA_INT,
      JAVA_INT,
      ADDRESS,
      JAVA_INT
    );

    private static final FunctionDescriptor LOAD_SOURCE = FunctionDescriptor.ofVoid(
      ADDRESS,
      JAVA_INT,
      JAVA_INT,
      JAVA_INT,
      JAVA_INT,
      JAVA_INT,
      ADDRESS
    );

    private static final FunctionDescriptor RESIDUAL_TARGET = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS);

    private final Level level;

    private final MethodHandle predicted;

    private final MethodHandle solid;

    private final MethodHandle palette;

    private final MethodHandle compact;

    private final MethodHandle predict;

    private final MethodHandle fit;

    private final MethodHandle cluster;

    private final MethodHandle assign;

    private final MethodHandle assignPattern;

    private final MethodHandle seeded;

    private final MethodHandle loadSource;

    private final MethodHandle halve;

    private final MethodHandle residualTarget;

    Binding(final Level level, final BiFunction<String, FunctionDescriptor, MethodHandle> handle) {
      this.level = level;
      this.predicted = handle.apply("predicted", SCORED_PREDICTION);
      this.solid = handle.apply("solid", SCORED_SOLID);
      this.palette = handle.apply("palette", SCORED_PREDICTION);
      this.compact = handle.apply("compact", SCORED_COMPACT);
      this.predict = handle.apply("predict", PREDICT);
      this.fit = handle.apply("fit", FIT);
      this.cluster = handle.apply("cluster", BLOCK_TO_ARRAY);
      this.assign = handle.apply("assign", ASSIGN);
      this.assignPattern = handle.apply("assign_pattern", ASSIGN_PATTERN);
      this.seeded = handle.apply("seeded", SEEDED);
      this.loadSource = handle.apply("load_source", LOAD_SOURCE);
      this.halve = handle.apply("halve", BLOCK_TO_ARRAY);
      this.residualTarget = handle.apply("residual_target", RESIDUAL_TARGET);
    }

    private static FunctionDescriptor scored(final MemoryLayout... inputs) {
      return FunctionDescriptor.of(JAVA_LONG, inputs).appendArgumentLayouts(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE);
    }

    @SuppressWarnings("restricted") // the Linker downcalls are restricted methods of the foreign function API
    static Binding of(final SymbolLookup library, final Level level) {
      final Linker linker = Linker.nativeLinker();
      return new Binding(level, (name, descriptor) ->
        linker.downcallHandle(library.findOrThrow(SYMBOL_PREFIX + level.getSymbol() + SYMBOL_SEPARATOR + name), descriptor, CRITICAL)
      );
    }

    Level getLevel() {
      return this.level;
    }
  }

  // Critical downcalls pass the Java arrays to native code unchecked, so every size, span and offset is checked here.
  private static final class NativeKernels implements Kernels {

    private static final int MAXIMUM_COORDINATE = 1 << 20;

    private static final int SELECTORS_AT = PALETTE_COLORS * CHANNELS;

    private static final String KERNEL_FAILED_MESSAGE = "MCV2 native kernel failed!";

    private static final int SEGMENTS = 256;

    private final Binding binding;

    private int[] source = new int[0];

    private double rate;

    private double limit;

    private long distortion;

    private final Object[] cachedArrays = new Object[SEGMENTS];

    private final MemorySegment[] cachedSegments = new MemorySegment[SEGMENTS];

    private NativeKernels(final Binding binding) {
      this.binding = binding;
    }

    private MemorySegment of(final int[] array) {
      final int slot = slot(array);
      return this.remembers(slot, array) ? this.cachedSegments[slot] : this.remember(slot, array, MemorySegment.ofArray(array));
    }

    private MemorySegment of(final byte[] array) {
      final int slot = slot(array);
      return this.remembers(slot, array) ? this.cachedSegments[slot] : this.remember(slot, array, MemorySegment.ofArray(array));
    }

    private MemorySegment of(final float[] array) {
      final int slot = slot(array);
      return this.remembers(slot, array) ? this.cachedSegments[slot] : this.remember(slot, array, MemorySegment.ofArray(array));
    }

    @SuppressWarnings("ReferenceEquality") // the cache is keyed by array identity, not content
    private boolean remembers(final int slot, final Object array) {
      return this.cachedArrays[slot] == array;
    }

    private static int slot(final Object array) {
      return System.identityHashCode(array) & (SEGMENTS - 1);
    }

    private MemorySegment remember(final int slot, final Object array, final MemorySegment segment) {
      this.cachedArrays[slot] = array;
      this.cachedSegments[slot] = segment;

      return segment;
    }

    @Override
    public void forgetArrays() {
      Arrays.fill(this.cachedArrays, null);
      Arrays.fill(this.cachedSegments, null);
    }

    private static void checkSize(final int size) {
      Preconditions.checkArgument(isBlockSize(size), INVALID_BLOCK_SIZE_MESSAGE);
    }

    private static void checkLength(final int length, final long count) {
      Preconditions.checkArgument(length >= count, "Array too short!");
    }

    private static void checkBlock(final int length, final int size) {
      checkLength(length, size * (long) size * CHANNELS);
    }

    private static void checkPicture(final int length, final int width, final int height) {
      Preconditions.checkArgument(
        width >= 1 && width <= MAXIMUM_DIMENSION && height >= 1 && height <= MAXIMUM_DIMENSION,
        "Invalid picture size!"
      );
      checkLength(length, width * (long) height * CHANNELS);
    }

    private static void checkCoordinate(final int value) {
      Preconditions.checkArgument(value >= -MAXIMUM_COORDINATE && value <= MAXIMUM_COORDINATE, "Coordinate out of range!");
    }

    private void checkScored(final int size, final int[] output) {
      checkSize(size);
      checkBlock(output.length, size);
      checkBlock(this.source.length, size);
    }

    private boolean recordDistortion(final long measured) {
      if (measured < 0) {
        return false;
      }

      this.distortion = measured;

      return true;
    }

    @Override
    public void start(final int[] source, final double rate, final double limit) {
      this.source = source;
      this.rate = rate;
      this.limit = limit;
    }

    @Override
    public long getDistortion() {
      return this.distortion;
    }

    @Override
    public boolean predicted(final int[] prediction, final int size, final int[] output) {
      this.checkScored(size, output);
      checkBlock(prediction.length, size);

      try {
        return this.recordDistortion(
          (long) this.binding.predicted.invokeExact(of(prediction), size, of(output), of(this.source), this.rate, this.limit)
        );
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
    }

    @Override
    public boolean solid(final int color, final int size, final int[] output) {
      this.checkScored(size, output);

      try {
        return this.recordDistortion(
          (long) this.binding.solid.invokeExact(color, size, of(output), of(this.source), this.rate, this.limit)
        );
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
    }

    @Override
    public boolean palette(final byte[] record, final int size, final int[] output) {
      this.checkScored(size, output);
      checkLength(record.length, SELECTORS_AT + (size * (long) size) / Byte.SIZE);

      try {
        return this.recordDistortion(
          (long) this.binding.palette.invokeExact(of(record), size, of(output), of(this.source), this.rate, this.limit)
        );
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
    }

    @Override
    public boolean compact(final int[] prediction, final byte[] record, final int quantizer, final int size, final int[] output) {
      Preconditions.checkArgument(quantizer >= 0 && quantizer <= MAXIMUM_QUANTIZER, "Invalid quantizer!");
      this.checkScored(size, output);
      checkBlock(prediction.length, size);
      checkLength(record.length, COMPACT_BYTES);

      try {
        return this.recordDistortion(
          (long) this.binding.compact.invokeExact(
            of(prediction),
            of(record),
            quantizer,
            size,
            of(output),
            of(this.source),
            this.rate,
            this.limit
          )
        );
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
    }

    @Override
    public void predict(
      final byte[] reference,
      final int width,
      final int height,
      final int left,
      final int top,
      final int size,
      final int motionX,
      final int motionY,
      final int[] output
    ) {
      checkPicture(reference.length, width, height);
      checkSize(size);
      checkBlock(output.length, size);
      checkCoordinate(left);
      checkCoordinate(top);
      checkCoordinate(motionX);
      checkCoordinate(motionY);

      try {
        this.binding.predict.invokeExact(of(reference), width, height, left, top, size, motionX, motionY, of(output));
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
    }

    @Override
    public void fit(final float[] values, final int size, final float[] output) {
      checkSize(size);
      checkLength(values.length, size * (long) size);
      checkLength(output.length, GRID_NODES);

      try {
        this.binding.fit.invokeExact(of(values), size, of(FITTING_MATRICES[sizeIndex(size)]), of(output));
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
    }

    @Override
    public void cluster(final int[] source, final int size, final int[] endpoints) {
      checkSize(size);
      checkBlock(source.length, size);
      checkLength(endpoints.length, PALETTE_COLORS * CHANNELS);

      try {
        this.binding.cluster.invokeExact(of(source), size, of(endpoints));
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
    }

    @Override
    public void finishPalette(final int[] source, final int count, final int[] endpoints, final int[] colors, final byte[] selectors) {
      Preconditions.checkArgument(count >= 0 && count <= ROOT_SIZE * ROOT_SIZE, INVALID_PIXEL_COUNT_MESSAGE);
      checkLength(source.length, count * (long) CHANNELS);
      checkLength(selectors.length, count);
      checkLength(colors.length, PALETTE_COLORS * CHANNELS);

      System.arraycopy(endpoints, 0, colors, 0, PALETTE_COLORS * CHANNELS);
      try {
        this.binding.assign.invokeExact(of(source), count, of(colors), of(selectors));
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
    }

    @Override
    public boolean finishPattern(final int[] source, final int size, final int[] endpoints, final int[] colors, final byte[] selectors) {
      checkSize(size);
      checkBlock(source.length, size);
      checkLength(selectors.length, size * (long) size);
      checkLength(colors.length, PALETTE_COLORS * CHANNELS);

      System.arraycopy(endpoints, 0, colors, 0, PALETTE_COLORS * CHANNELS);
      try {
        return (int) this.binding.assignPattern.invokeExact(of(source), size, of(colors), of(selectors)) != 0;
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
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
      checkPicture(reference.length, width, height);
      checkSize(size);
      checkBlock(source.length, size);
      checkCoordinate(left);
      checkCoordinate(top);
      Preconditions.checkArgument(range >= 0 && range <= MAXIMUM_COORDINATE, "Invalid range!");

      try {
        return (int) this.binding.seeded.invokeExact(
          of(reference),
          width,
          height,
          of(source),
          left,
          top,
          size,
          range,
          of(seeds),
          seeds.length
        );
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
    }

    @Override
    public void loadSource(
      final byte[] image,
      final int width,
      final int height,
      final int left,
      final int top,
      final int size,
      final int[] source
    ) {
      checkPicture(image.length, width, height);
      checkSize(size);
      checkBlock(source.length, size);
      Preconditions.checkArgument(left >= 0 && left < width && top >= 0 && top < height, "Block outside the picture!");

      try {
        this.binding.loadSource.invokeExact(of(image), width, height, left, top, size, of(source));
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
    }

    @Override
    public void halve(final int[] block, final int size, final int[] output) {
      // SIMD row steps require power-of-two block sizes to avoid array overruns.
      Preconditions.checkArgument(size >= PYRAMID_SCALE && size <= ROOT_SIZE && Integer.bitCount(size) == 1, INVALID_BLOCK_SIZE_MESSAGE);
      checkBlock(block.length, size);
      checkBlock(output.length, size / PYRAMID_SCALE);

      try {
        this.binding.halve.invokeExact(of(block), size, of(output));
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
    }

    @Override
    public void residualTarget(final int[] source, final int[] prediction, final int count, final float[] target) {
      Preconditions.checkArgument(count >= 0 && count <= ROOT_SIZE * ROOT_SIZE, INVALID_PIXEL_COUNT_MESSAGE);
      checkLength(source.length, count * (long) CHANNELS);
      checkLength(prediction.length, count * (long) CHANNELS);
      checkLength(target.length, count);

      try {
        this.binding.residualTarget.invokeExact(of(source), of(prediction), count, of(target));
      } catch (final Throwable exception) {
        throw new IllegalStateException(KERNEL_FAILED_MESSAGE, exception);
      }
    }
  }

  private interface Kernels {
    void start(int[] source, double rate, double limit);
    long getDistortion();
    boolean predicted(int[] prediction, int size, int[] output);
    boolean solid(int color, int size, int[] output);
    boolean palette(byte[] record, int size, int[] output);
    boolean compact(int[] prediction, byte[] record, int quantizer, int size, int[] output);
    void predict(byte[] reference, int width, int height, int left, int top, int size, int motionX, int motionY, int[] output);
    void fit(float[] values, int size, float[] output);
    void cluster(int[] source, int size, int[] endpoints);
    void finishPalette(int[] source, int count, int[] endpoints, int[] colors, byte[] selectors);
    boolean finishPattern(int[] source, int size, int[] endpoints, int[] colors, byte[] selectors);
    int seeded(byte[] reference, int width, int height, int[] source, int left, int top, int size, int range, int[] seeds);
    void loadSource(byte[] image, int width, int height, int left, int top, int size, int[] source);
    void halve(int[] block, int size, int[] output);
    void residualTarget(int[] source, int[] prediction, int count, float[] target);

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

    private boolean scoreRow(final int[] output, final int from, final int pixels) {
      int sum = 0;
      final int end = from + pixels * CHANNELS;
      for (int offset = from; offset < end; offset += CHANNELS) {
        sum += pixelError(
          this.source[offset] - output[offset],
          this.source[offset + GREEN_OFFSET] - output[offset + GREEN_OFFSET],
          this.source[offset + BLUE_OFFSET] - output[offset + BLUE_OFFSET]
        );
      }

      this.distortion += sum;

      return this.distortion / DISTORTION_SCALE + this.rate < this.limit;
    }
  }

  private static final class JavaKernels implements Kernels {

    private static final int HALF_PIXEL_SCALE = 2;

    private static final int INTERPOLATED_AXES = 2;

    private static final float LUMA_NORMALIZATION = 0.25f;

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
    public long getDistortion() {
      return this.score.distortion;
    }

    @Override
    public boolean predicted(final int[] prediction, final int size, final int[] output) {
      final int rowLength = size * CHANNELS;
      for (int row = 0; row < size; row++) {
        final int from = row * rowLength;
        System.arraycopy(prediction, from, output, from, rowLength);
        if (!this.score.scoreRow(output, from, size)) {
          return false;
        }
      }

      return true;
    }

    @Override
    public boolean solid(final int color, final int size, final int[] output) {
      final int red = (color >> RED_SHIFT) & BYTE_MASK;
      final int green = (color >> GREEN_SHIFT) & BYTE_MASK;
      final int blue = color & BYTE_MASK;
      for (int row = 0; row < size; row++) {
        final int from = row * size * CHANNELS;
        for (int column = 0; column < size; column++) {
          output[from + column * CHANNELS] = red;
          output[from + column * CHANNELS + GREEN_OFFSET] = green;
          output[from + column * CHANNELS + BLUE_OFFSET] = blue;
        }
        if (!this.score.scoreRow(output, from, size)) {
          return false;
        }
      }

      return true;
    }

    @Override
    public boolean palette(final byte[] record, final int size, final int[] output) {
      for (int row = 0; row < size; row++) {
        for (int column = 0; column < size; column++) {
          final int pixel = row * size + column;
          final int bit = (record[PALETTE_COLORS * CHANNELS + pixel / Byte.SIZE] >> (pixel % Byte.SIZE)) & 1;
          final int arrayOffset = bit * CHANNELS;
          output[pixel * CHANNELS] = record[arrayOffset] & BYTE_MASK;
          output[pixel * CHANNELS + GREEN_OFFSET] = record[arrayOffset + GREEN_OFFSET] & BYTE_MASK;
          output[pixel * CHANNELS + BLUE_OFFSET] = record[arrayOffset + BLUE_OFFSET] & BYTE_MASK;
        }
        if (!this.score.scoreRow(output, row * size * CHANNELS, size)) {
          return false;
        }
      }

      return true;
    }

    @Override
    public boolean compact(final int[] prediction, final byte[] record, final int quantizer, final int size, final int[] output) {
      for (int index = 0; index < GRID_NODES; index++) {
        this.nodes[index] = signed(
          (record[MOTION_BYTES + index / NODES_PER_BYTE] & BYTE_MASK) >> ((index % NODES_PER_BYTE) * NODE_BITS),
          NODE_BITS
        );
      }

      interpolateHorizontally(this.nodes, size, this.rows);
      final int shift = INTERPOLATED_AXES * (Integer.numberOfTrailingZeros(size) + 1);
      final int scale = HALF_PIXEL_SCALE * HALF_PIXEL_SCALE * size * size;
      for (int row = 0; row < size; row++) {
        this.interpolateVertically(size, row);
        final int from = row * size * CHANNELS;
        for (int column = 0; column < size; column++) {
          final int arrayOffset = from + column * CHANNELS;
          final int scaled = this.line[column] << quantizer;
          output[arrayOffset] = roundToChannel(prediction[arrayOffset] * scale + scaled, shift);
          output[arrayOffset + GREEN_OFFSET] = roundToChannel(prediction[arrayOffset + GREEN_OFFSET] * scale + scaled, shift);
          output[arrayOffset + BLUE_OFFSET] = roundToChannel(prediction[arrayOffset + BLUE_OFFSET] * scale + scaled, shift);
        }
        if (!this.score.scoreRow(output, from, size)) {
          return false;
        }
      }

      return true;
    }

    private void interpolateVertically(final int size, final int row) {
      final int index = sizeIndex(size);
      final int top = GRID_LOWER[index][row] * size;
      final int bottom = GRID_UPPER[index][row] * size;
      final int below = GRID_WEIGHTS[index][row];
      final int above = HALF_PIXEL_SCALE * size - below;
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
      final int[] output
    ) {
      final int left = blockLeft + motionX;
      final int top = blockTop + motionY;
      if (left >= 0 && top >= 0 && left + size <= width && top + size <= height) {
        final int rowLength = size * CHANNELS;
        for (int row = 0; row < size; row++) {
          final int from = ((top + row) * width + left) * CHANNELS;
          this.widen(reference, from, output, row * rowLength, rowLength);
        }

        return;
      }

      for (int row = 0; row < size; row++) {
        final int sourceRow = Math.min(Math.max(top + row, 0), height - 1);
        for (int column = 0; column < size; column++) {
          final int sourceColumn = Math.min(Math.max(left + column, 0), width - 1);
          final int from = (sourceRow * width + sourceColumn) * CHANNELS;
          this.widen(reference, from, output, (row * size + column) * CHANNELS, CHANNELS);
        }
      }
    }

    private void widen(final byte[] reference, final int from, final int[] output, final int destinationOffset, final int length) {
      for (int offset = 0; offset < length; offset++) {
        output[destinationOffset + offset] = reference[from + offset] & BYTE_MASK;
      }
    }

    @Override
    public void fit(final float[] values, final int size, final float[] output) {
      final float[] matrix = FITTING_MATRICES[sizeIndex(size)];
      for (int row = 0; row < size; row++) {
        for (int nodeColumn = 0; nodeColumn < GRID; nodeColumn++) {
          this.fitScratch[row * GRID + nodeColumn] = this.fitRow(values, matrix, size, row, nodeColumn);
        }
      }

      for (int nodeRow = 0; nodeRow < GRID; nodeRow++) {
        for (int nodeColumn = 0; nodeColumn < GRID; nodeColumn++) {
          output[nodeRow * GRID + nodeColumn] = (float) this.fitColumn(matrix, size, nodeRow, nodeColumn);
        }
      }
    }

    private double fitRow(final float[] values, final float[] matrix, final int size, final int row, final int nodeColumn) {
      double sum = 0;
      for (int column = 0; column < size; column++) {
        sum += (double) values[row * size + column] * matrix[nodeColumn * size + column];
      }

      return sum;
    }

    private double fitColumn(final float[] matrix, final int size, final int nodeRow, final int nodeColumn) {
      double sum = 0;
      for (int row = 0; row < size; row++) {
        sum += matrix[nodeRow * size + row] * this.fitScratch[row * GRID + nodeColumn];
      }

      return sum;
    }

    @Override
    public void cluster(final int[] source, final int size, final int[] endpoints) {
      final int step = size >= SAMPLED_SIZE ? SAMPLED_STEP : 1;
      this.seedEndpoints(source, size, step, endpoints);
      for (int iteration = 0; iteration < CLUSTER_ITERATIONS; iteration++) {
        Arrays.fill(this.clusterScratch, 0, CLUSTER_SUMS, 0);
        this.assignClusters(source, size, step, endpoints);
        this.moveEndpoints(endpoints);
      }
    }

    // The darkest and the brightest sampled pixels, the first of each in row order.
    private void seedEndpoints(final int[] source, final int size, final int step, final int[] endpoints) {
      final int samplesPerSide = (size + step - 1) / step;
      int low = 0;
      int high = 0;
      int lowLuma = Integer.MAX_VALUE;
      int highLuma = Integer.MIN_VALUE;
      for (int sample = 0; sample < samplesPerSide * samplesPerSide; sample++) {
        final int row = (sample / samplesPerSide) * step;
        final int column = (sample % samplesPerSide) * step;
        final int arrayOffset = (row * size + column) * CHANNELS;
        final int luma = source[arrayOffset] + LUMA_GREEN_WEIGHT * source[arrayOffset + GREEN_OFFSET] + source[arrayOffset + BLUE_OFFSET];
        if (luma < lowLuma) {
          lowLuma = luma;
          low = arrayOffset;
        }
        if (luma > highLuma) {
          highLuma = luma;
          high = arrayOffset;
        }
      }

      for (int channel = 0; channel < CHANNELS; channel++) {
        endpoints[channel] = source[low + channel];
        endpoints[CHANNELS + channel] = source[high + channel];
      }
    }

    private void assignClusters(final int[] source, final int size, final int step, final int[] endpoints) {
      for (int row = 0; row < size; row += step) {
        for (int column = 0; column < size; column += step) {
          this.assignCluster(source, (row * size + column) * CHANNELS, endpoints);
        }
      }
    }

    private void assignCluster(final int[] source, final int arrayOffset, final int[] endpoints) {
      final int red = source[arrayOffset];
      final int green = source[arrayOffset + GREEN_OFFSET];
      final int blue = source[arrayOffset + BLUE_OFFSET];
      final int firstRed = endpoints[0];
      final int firstGreen = endpoints[GREEN_OFFSET];
      final int firstBlue = endpoints[BLUE_OFFSET];
      final int secondRed = endpoints[CHANNELS];
      final int secondGreen = endpoints[CHANNELS + GREEN_OFFSET];
      final int secondBlue = endpoints[CHANNELS + BLUE_OFFSET];
      final int firstDistance =
        (red - firstRed) * (red - firstRed) + (green - firstGreen) * (green - firstGreen) + (blue - firstBlue) * (blue - firstBlue);
      final int secondDistance =
        (red - secondRed) * (red - secondRed) + (green - secondGreen) * (green - secondGreen) + (blue - secondBlue) * (blue - secondBlue);
      final int nearest = secondDistance < firstDistance ? 1 : 0;
      this.clusterScratch[nearest * CHANNELS] += red;
      this.clusterScratch[nearest * CHANNELS + GREEN_OFFSET] += green;
      this.clusterScratch[nearest * CHANNELS + BLUE_OFFSET] += blue;
      this.clusterScratch[CLUSTER_COUNTS_OFFSET + nearest]++;
    }

    private void moveEndpoints(final int[] endpoints) {
      for (int endpoint = 0; endpoint < PALETTE_COLORS; endpoint++) {
        final long members = this.clusterScratch[CLUSTER_COUNTS_OFFSET + endpoint];
        if (members <= 0) {
          continue;
        }
        for (int channel = 0; channel < CHANNELS; channel++) {
          final long mean = (this.clusterScratch[endpoint * CHANNELS + channel] + members / ROUNDING_DIVISOR) / members;
          endpoints[endpoint * CHANNELS + channel] = (int) mean;
        }
      }
    }

    @Override
    public void finishPalette(final int[] source, final int count, final int[] endpoints, final int[] colors, final byte[] selectors) {
      System.arraycopy(endpoints, 0, colors, 0, PALETTE_COLORS * CHANNELS);
      for (int pixel = 0; pixel < count; pixel++) {
        selectors[pixel] = nearest(source, pixel, colors);
      }
    }

    @Override
    public boolean finishPattern(final int[] source, final int size, final int[] endpoints, final int[] colors, final byte[] selectors) {
      System.arraycopy(endpoints, 0, colors, 0, PALETTE_COLORS * CHANNELS);
      boolean uniformColumns = true;
      boolean uniformRows = true;
      for (int row = 0; row < size && (uniformColumns || uniformRows); row++) {
        for (int column = 0; column < size; column++) {
          final int pixel = row * size + column;
          selectors[pixel] = nearest(source, pixel, colors);
          uniformColumns &= selectors[pixel] == selectors[column];
          uniformRows &= selectors[pixel] == selectors[row * size];
        }
      }

      return uniformColumns || uniformRows;
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
    public void halve(final int[] block, final int size, final int[] output) {
      final int half = size / PYRAMID_SCALE;
      final int rowLength = size * CHANNELS;
      for (int row = 0; row < half; row++) {
        for (int column = 0; column < half; column++) {
          final int arrayOffset = (PYRAMID_SCALE * row * size + PYRAMID_SCALE * column) * CHANNELS;
          this.averageQuad(block, arrayOffset, rowLength, output, (row * half + column) * CHANNELS);
        }
      }
    }

    private void averageQuad(final int[] block, final int arrayOffset, final int rowLength, final int[] output, final int outputOffset) {
      for (int channel = 0; channel < CHANNELS; channel++) {
        final int sum =
          block[arrayOffset + channel] +
          block[arrayOffset + CHANNELS + channel] +
          block[arrayOffset + rowLength + channel] +
          block[arrayOffset + rowLength + CHANNELS + channel];
        output[outputOffset + channel] = (sum + QUAD_AVERAGE_ROUNDING) >> QUAD_AVERAGE_SHIFT;
      }
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
          final int destinationOffset = (row * size + column) * CHANNELS;
          source[destinationOffset] = image[from] & BYTE_MASK;
          source[destinationOffset + GREEN_OFFSET] = image[from + GREEN_OFFSET] & BYTE_MASK;
          source[destinationOffset + BLUE_OFFSET] = image[from + BLUE_OFFSET] & BYTE_MASK;
        }
      }
    }

    @Override
    public void residualTarget(final int[] source, final int[] prediction, final int count, final float[] target) {
      for (int pixel = 0; pixel < count; pixel++) {
        final int offset = pixel * CHANNELS;
        final float luma =
          (source[offset] + LUMA_GREEN_WEIGHT * source[offset + GREEN_OFFSET] + source[offset + BLUE_OFFSET]) * LUMA_NORMALIZATION;
        final float red = prediction[offset];
        final float green = prediction[offset + GREEN_OFFSET];
        final float blue = prediction[offset + BLUE_OFFSET];
        target[pixel] = luma - (red + LUMA_GREEN_WEIGHT * green + blue) * LUMA_NORMALIZATION;
      }
    }
  }

  private static final class MotionSearch {

    private final byte[] reference;
    private final int width;
    private final int height;
    private final int[] source;
    private final int blockLeft;
    private final int blockTop;
    private final int size;
    private final int[] samples;

    private long best;
    private int bestX;
    private int bestY;

    MotionSearch(
      final byte[] reference,
      final int width,
      final int height,
      final int[] source,
      final int blockLeft,
      final int blockTop,
      final int size,
      final int[] samples
    ) {
      this.reference = reference;
      this.width = width;
      this.height = height;
      this.source = source;
      this.blockLeft = blockLeft;
      this.blockTop = blockTop;
      this.size = size;
      this.samples = samples;
      this.best = this.cost(0, 0);
    }

    void consider(final int motionX, final int motionY) {
      final long error = this.cost(motionX, motionY);
      if (error < this.best) {
        this.best = error;
        this.bestX = motionX;
        this.bestY = motionY;
      }
    }

    private long cost(final int motionX, final int motionY) {
      final int left = this.blockLeft + this.samples[0] + motionX;
      final int right = this.blockLeft + this.samples[SAMPLES_PER_SIDE - 1] + motionX;
      final int top = this.blockTop + this.samples[0] + motionY;
      final int bottom = this.blockTop + this.samples[SAMPLES_PER_SIDE - 1] + motionY;
      if (left >= 0 && top >= 0 && right < this.width && bottom < this.height) {
        return this.interiorCost(motionX, motionY);
      }

      return this.clampedCost(motionX, motionY);
    }

    private long interiorCost(final int motionX, final int motionY) {
      long sum = 0;
      for (int sampleRow = 0; sampleRow < SAMPLES_PER_SIDE; sampleRow++) {
        final int row = (this.blockTop + this.samples[sampleRow] + motionY) * this.width + this.blockLeft + motionX;
        final int line = this.samples[sampleRow] * this.size;
        for (int sampleColumn = 0; sampleColumn < SAMPLES_PER_SIDE; sampleColumn++) {
          final int arrayOffset = (row + this.samples[sampleColumn]) * CHANNELS;
          final int target = (line + this.samples[sampleColumn]) * CHANNELS;
          sum += Math.abs((this.reference[arrayOffset] & BYTE_MASK) - this.source[target]);
          sum += Math.abs((this.reference[arrayOffset + GREEN_OFFSET] & BYTE_MASK) - this.source[target + GREEN_OFFSET]);
          sum += Math.abs((this.reference[arrayOffset + BLUE_OFFSET] & BYTE_MASK) - this.source[target + BLUE_OFFSET]);
        }
      }

      return sum;
    }

    private long clampedCost(final int motionX, final int motionY) {
      long sum = 0;
      for (int sampleRow = 0; sampleRow < SAMPLES_PER_SIDE; sampleRow++) {
        final int row = Math.min(Math.max(this.blockTop + this.samples[sampleRow] + motionY, 0), this.height - 1);
        for (int sampleColumn = 0; sampleColumn < SAMPLES_PER_SIDE; sampleColumn++) {
          final int column = Math.min(Math.max(this.blockLeft + this.samples[sampleColumn] + motionX, 0), this.width - 1);
          final int arrayOffset = (row * this.width + column) * CHANNELS;
          final int target = (this.samples[sampleRow] * this.size + this.samples[sampleColumn]) * CHANNELS;
          sum += this.pixelDistance(arrayOffset, target);
        }
      }

      return sum;
    }

    private long pixelDistance(final int arrayOffset, final int target) {
      long sum = 0;
      for (int channel = 0; channel < CHANNELS; channel++) {
        sum += Math.abs((this.reference[arrayOffset + channel] & BYTE_MASK) - this.source[target + channel]);
      }

      return sum;
    }
  }

  private static final class RootSearch {

    private final FrameState frame;
    private final boolean[] before;
    private final BooleanSupplier late;
    private final int columns;
    private final double rootThreshold;
    private final double fineThreshold;
    private final double steady;

    private final TreeNode[] roots;
    private final int[] leafCounts;
    private final int[] motion;
    private final boolean[] splits;

    RootSearch(final FrameState frame, final boolean[] before, final BooleanSupplier late) {
      final Buffers buffers = frame.buffers;
      this.frame = frame;
      this.before = before;
      this.late = late;
      this.columns = buffers.columns;
      this.rootThreshold = ROOT_SPLIT_BITS * frame.lambda;
      this.fineThreshold = (frame.fast ? FAST_FINE_SPLIT_BITS : NORMAL_FINE_SPLIT_BITS) * frame.lambda;
      this.steady = (frame.fast ? FAST_STEADY_SPLIT_BITS : NORMAL_STEADY_SPLIT_BITS) * frame.lambda;
      this.roots = new TreeNode[buffers.roots];
      this.leafCounts = new int[buffers.roots];
      this.motion = new int[((frame.width + SMALLEST_BLOCK - 1) / SMALLEST_BLOCK) * ((frame.height + SMALLEST_BLOCK - 1) / SMALLEST_BLOCK)];
      this.splits = new boolean[buffers.roots];
    }

    void searchRoot(final BlockCoder[] coders, final int index) {
      final int left = (index % this.columns) * ROOT_SIZE;
      final int top = (index / this.columns) * ROOT_SIZE;
      final double threshold = !this.frame.keyframe && !this.before[index] ? this.steady : this.rootThreshold;
      final boolean late = this.late.getAsBoolean();
      for (final BlockCoder coder : coders) {
        coder.hurried = late;
      }

      final Choice chosen = descend(this.frame, coders, 0, left, top, NO_VECTOR, threshold, this.fineThreshold);
      final TreeNode root = chosen.node();
      this.roots[index] = root;
      this.splits[index] = root.isSplit();
      fillMotion(this.motion, this.frame.width, this.frame.height, root, left, top, ROOT_SIZE);
      this.leafCounts[index] = chosen.leaves();
    }
  }

  private static final class TreeNode {

    private static final TreeNode SKIP = new TreeNode(MODE_SKIP, 0, NO_BYTES, null);

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
      return mode == MODE_SKIP ? skip() : new TreeNode(mode, quantizer, record, null);
    }

    private static TreeNode split(final TreeNode topLeft, final TreeNode topRight, final TreeNode bottomLeft, final TreeNode bottomRight) {
      return new TreeNode(MODE_SPLIT, 0, NO_BYTES, new TreeNode[] { topLeft, topRight, bottomLeft, bottomRight });
    }

    private TreeNode getChild(final int index) {
      return Objects.requireNonNull(this.children)[index];
    }

    private boolean isSplit() {
      return this.children != null;
    }
  }

  // Parallel work within the shared CPU pool.
  private static final class Workers {

    private final ForkJoinPool pool;

    private final int threads;

    private Workers(final ForkJoinPool pool, final int threads) {
      Preconditions.checkArgument(threads >= 1, "At least one thread is needed!");

      this.pool = pool;
      this.threads = threads;
    }

    private void forEach(final int count, final IntConsumer body) {
      this.forEach(count, () -> body, IntConsumer::accept);
    }

    private <ScratchType> void forEach(final int count, final Supplier<ScratchType> scratch, final ObjIntConsumer<ScratchType> body) {
      final ForkJoinPool target = this.pool;
      final int workers = Math.min(this.threads, count);
      if (workers <= 1) {
        final ScratchType state = scratch.get();
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
            .forEach(_ -> this.runWorker(count, scratch, body, next, failure))
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

    private <ScratchType> void runWorker(
      final int count,
      final Supplier<ScratchType> scratch,
      final ObjIntConsumer<ScratchType> body,
      final AtomicInteger next,
      final AtomicReference<@Nullable Throwable> failure
    ) {
      try {
        final ScratchType state = scratch.get();
        for (int index = next.getAndIncrement(); index < count; index = next.getAndIncrement()) {
          body.accept(state, index);
        }
      } catch (final RuntimeException | Error exception) {
        failure.compareAndSet(null, exception);
      }
    }
  }

  /**
   * A shared, bounded CPU budget for encoders and synchronous encoding jobs.
   *
   * <p>This class is thread-safe: encoders on any thread may share one pool.
   *
   * @since 1.0.0
   */
  public static final class Pool implements AutoCloseable {

    /**
     * Maximum worker count.
     *
     * @since 1.0.0
     */
    public static final int MAXIMUM_THREADS = 256;

    private static final long KEEP_ALIVE_SECONDS = 30;

    private static final Logger LOGGER = LoggerFactory.getLogger(Pool.class);

    private static final String THREAD_NAME_PREFIX = "mcav-mcv2-encoder-";

    private static final String THREAD_GROUP_NAME = "mcav-mcv2-encoders";

    private static final int CORE_POOL_SIZE = 0;
    private static final int MINIMUM_RUNNABLE = 1;

    private static final int PROCESSORS_PER_DEFAULT_THREAD = 2;

    private static final ThreadGroup GROUP = lowPriorityGroup();

    private static @Nullable Pool shared;

    private static int sharedThreads;

    private final ForkJoinPool pool;

    private final int threads;

    /**
     * Creates low-priority daemon workers that share one concurrency limit.
     *
     * @param threads worker count, 1 through {@link #MAXIMUM_THREADS}
     * @throws IllegalArgumentException if {@code threads} is outside that range
     * @since 1.0.0
     */
    public Pool(final int threads) {
      Preconditions.checkArgument(
        threads >= 1 && threads <= MAXIMUM_THREADS,
        "Threads %s must be between 1 and %s inclusive!",
        threads,
        MAXIMUM_THREADS
      );
      this.threads = threads;
      final AtomicInteger created = new AtomicInteger();
      final ForkJoinPool.ForkJoinWorkerThreadFactory factory = forkJoinPool -> {
        final ForkJoinWorkerThread thread = new EncoderThread(forkJoinPool);
        thread.setName(THREAD_NAME_PREFIX + created.incrementAndGet());
        return thread;
      };

      this.pool = new ForkJoinPool(
        threads,
        factory,
        this::logThreadFailure,
        false,
        CORE_POOL_SIZE,
        threads,
        MINIMUM_RUNNABLE,
        _ -> true,
        KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS
      );
    }

    /**
     * Returns the lazily created application-wide pool.
     *
     * @return the current shared pool
     * @since 1.0.0
     */
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

    private static ThreadGroup lowPriorityGroup() {
      final ThreadGroup group = new ThreadGroup(THREAD_GROUP_NAME);
      group.setMaxPriority(Thread.MIN_PRIORITY);
      return group;
    }

    static int defaultThreads(final int processors) {
      return Math.min(MAXIMUM_THREADS, Math.max(1, processors / PROCESSORS_PER_DEFAULT_THREAD));
    }

    /**
     * Sets the worker count for the next shared pool, leaving existing encoders on their pool.
     *
     * @param threads worker count, or zero to use the processor-based default
     * @throws IllegalArgumentException if {@code threads} is negative or exceeds {@link #MAXIMUM_THREADS}
     * @since 1.0.0
     */
    public static synchronized void setSharedThreads(final int threads) {
      Preconditions.checkArgument(
        threads >= 0 && threads <= MAXIMUM_THREADS,
        "Threads %s must be between 0 and %s inclusive!",
        threads,
        MAXIMUM_THREADS
      );

      if (threads != sharedThreads) {
        sharedThreads = threads;
        shared = null;
      }
    }

    /**
     * Creates a stream encoder sharing this pool's worker budget.
     *
     * @param settings     live search settings
     * @param verification whether {@link Mcv2#finish(Pending)} verifies the reconstruction
     * @return a new stream encoder
     * @throws NullPointerException if {@code settings} or {@code verification} is null
     * @since 1.0.0
     */
    public Mcv2 encoder(final Settings settings, final Verification verification) {
      return new Mcv2(settings, this.pool, this.threads, verification);
    }

    /**
     * Runs a job within the shared worker budget and propagates its failures.
     *
     * @param task         encoding job
     * @param <ResultType> result type
     * @return the job result
     * @throws NullPointerException if {@code task} is null
     * @throws Mcv2Exception        if the job throws a checked exception, or the calling thread is interrupted before or
     *                              while awaiting the job, which cancels the job and leaves the thread interrupted
     * @since 1.0.0
     */
    public <ResultType> ResultType run(final Callable<ResultType> task) {
      Preconditions.checkNotNull(task, "Task must not be null!");

      try {
        return this.runInterruptibly(task);
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();

        final String message = "The encode was interrupted!";
        throw new Mcv2Exception(message, exception);
      }
    }

    <ResultType> ResultType runInterruptibly(final Callable<ResultType> task) throws InterruptedException {
      if (Thread.interrupted()) {
        final String message = "Interrupted before the task started!";
        throw new InterruptedException(message);
      }

      final ForkJoinTask<ResultType> job = this.pool.submit(task);
      try {
        return job.get();
      } catch (final InterruptedException exception) {
        job.cancel(true);
        throw exception;
      } catch (final ExecutionException exception) {
        final Throwable cause = Objects.requireNonNull(exception.getCause()); // checker framework
        if (cause instanceof final RuntimeException runtimeException) {
          throw runtimeException;
        }

        if (cause instanceof final Error error) {
          throw error;
        }

        final String message = "An encode failed!";
        throw new Mcv2Exception(message, cause);
      }
    }

    private void logThreadFailure(@UnderInitialization Pool this, final Thread thread, final Throwable exception) {
      final String threadName = thread.getName();
      LOGGER.error("MCV2 encoder thread {} failed.", threadName, exception);
    }

    /**
     * Stops this pool and requests cancellation of its outstanding jobs.
     *
     * @since 1.0.0
     */
    @Override
    public void close() {
      this.pool.shutdownNow();
    }

    /**
     * Returns this pool's concurrency limit.
     *
     * @return configured workers
     * @since 1.0.0
     */
    public int getThreads() {
      return this.threads;
    }

    private static final class EncoderThread extends ForkJoinWorkerThread {

      private EncoderThread(final ForkJoinPool pool) {
        super(GROUP, pool, false);
      }
    }
  }

  private enum SearchEffort {
    BUDGETED,
    TRIVIAL,
  }
}
