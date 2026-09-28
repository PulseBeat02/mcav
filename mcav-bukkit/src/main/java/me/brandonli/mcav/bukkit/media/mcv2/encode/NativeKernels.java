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

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHANNELS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHROMA_PLANES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MAX_GRID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.PALETTE_COLORS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ROOT_SIZE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SMALLEST_BLOCK;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import me.brandonli.mcav.bukkit.media.mcv2.CompactRecord;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The encoder's kernels in the native library, at one dispatch level. Every call is one downcall that does one block's
 * arithmetic, well under a millisecond, on the Java arrays themselves ({@link Linker.Option#critical}): no copy, no
 * native memory. Before each call every size, offset and length the library will index with is checked here, so the
 * library reads and writes only inside the arrays it is given; the values themselves need no check, the library
 * computes with them as Java would. The library never sees a frame's bytes, only the encoder's own pictures.
 */
final class NativeKernels extends Kernels {

  /** The dispatch levels of the library, as bits of its {@code mcv2_cpu_levels} and in its symbols' names. */
  enum Level {
    /** Plain code for every CPU of the architecture, used only when asked for. */
    SCALAR(1, "scalar"),
    /** x86-64, whose every CPU has SSE2. */
    SSE2(16, "sse2"),
    /** x86-64 with SSE4.1. */
    SSE41(2, "sse41"),
    /** x86-64 with AVX2, which the operating system saves. */
    AVX2(4, "avx2"),
    /** x86-64 with the AVX-512 of Ice Lake and later: F, DQ, BW, VL, VBMI, VBMI2, VNNI and BITALG. */
    AVX512(32, "avx512"),
    /** AArch64, whose every CPU has NEON. */
    NEON(8, "neon"),
    /** AArch64 with SVE at a vector length of 256 bits. */
    SVE256(64, "sve256"),
    /** AArch64 with SVE at a vector length of 512 bits. */
    SVE512(128, "sve512");

    private final int bit;

    private final String symbol;

    Level(final int bit, final String symbol) {
      this.bit = bit;
      this.symbol = symbol;
    }

    /**
     * Whether a set of levels includes this one.
     *
     * @param levels the levels, as the library's bits
     * @return whether it does
     */
    boolean in(final int levels) {
      return (levels & this.bit) != 0;
    }

    /**
     * Gets the name the library gives this level's symbols.
     *
     * @return the name, such as {@code avx2}
     */
    String symbol() {
      return this.symbol;
    }
  }

  private static final Linker.Option CRITICAL = Linker.Option.critical(true);

  private static final FunctionDescriptor PREDICTED = FunctionDescriptor.of(
    JAVA_LONG,
    ADDRESS,
    JAVA_INT,
    ADDRESS,
    ADDRESS,
    JAVA_DOUBLE,
    JAVA_DOUBLE
  );

  private static final FunctionDescriptor SOLID = FunctionDescriptor.of(
    JAVA_LONG,
    JAVA_INT,
    JAVA_INT,
    ADDRESS,
    ADDRESS,
    JAVA_DOUBLE,
    JAVA_DOUBLE
  );

  private static final FunctionDescriptor PALETTE = FunctionDescriptor.of(
    JAVA_LONG,
    ADDRESS,
    JAVA_INT,
    JAVA_INT,
    ADDRESS,
    ADDRESS,
    JAVA_DOUBLE,
    JAVA_DOUBLE
  );

  private static final FunctionDescriptor INTRA_GRID = FunctionDescriptor.of(
    JAVA_LONG,
    ADDRESS,
    JAVA_INT,
    JAVA_INT,
    JAVA_INT,
    ADDRESS,
    ADDRESS,
    JAVA_DOUBLE,
    JAVA_DOUBLE
  );

  private static final FunctionDescriptor RESIDUAL_GRID = FunctionDescriptor.of(
    JAVA_LONG,
    ADDRESS,
    ADDRESS,
    JAVA_INT,
    JAVA_INT,
    JAVA_INT,
    JAVA_INT,
    ADDRESS,
    ADDRESS,
    JAVA_DOUBLE,
    JAVA_DOUBLE
  );

  private static final FunctionDescriptor REDUCED = FunctionDescriptor.of(
    JAVA_LONG,
    ADDRESS,
    JAVA_INT,
    ADDRESS,
    JAVA_INT,
    JAVA_INT,
    JAVA_INT,
    JAVA_INT,
    JAVA_INT,
    ADDRESS,
    ADDRESS,
    JAVA_DOUBLE,
    JAVA_DOUBLE
  );

  private static final FunctionDescriptor COMPACT = FunctionDescriptor.of(
    JAVA_LONG,
    ADDRESS,
    ADDRESS,
    JAVA_INT,
    JAVA_INT,
    JAVA_INT,
    JAVA_INT,
    ADDRESS,
    ADDRESS,
    JAVA_DOUBLE,
    JAVA_DOUBLE
  );

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

  private static final FunctionDescriptor FIT = FunctionDescriptor.ofVoid(
    ADDRESS,
    JAVA_INT,
    JAVA_INT,
    JAVA_INT,
    JAVA_INT,
    ADDRESS,
    ADDRESS,
    JAVA_INT,
    JAVA_INT
  );

  private static final FunctionDescriptor BLOCK_TO_ARRAY = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);

  private static final FunctionDescriptor LUMA_RESIDUAL = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS);

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

  private static final FunctionDescriptor YCOCG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

  private static final FunctionDescriptor RESIDUAL_TARGET = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

  private static final FunctionDescriptor CELL_MEANS = FunctionDescriptor.ofVoid(
    ADDRESS,
    JAVA_INT,
    JAVA_INT,
    JAVA_INT,
    ADDRESS,
    JAVA_INT,
    JAVA_INT
  );

  /** The largest coordinate or displacement a caller may pass: sums of them stay far inside an int. */
  private static final int MAX_COORDINATE = 1 << 20;

  /** The largest picture side the encoder accepts. */
  private static final int MAX_SIDE = 4096;

  /** Where a palette record's selector bits start: after its two colours. */
  private static final int SELECTORS_AT = PALETTE_COLORS * CHANNELS;

  /** The compact classes the library reconstructs: the DC, the grids and the luma plane. */
  private static final int NATIVE_CLASSES =
    (1 << CompactRecord.DC_Y) |
    (1 << CompactRecord.GRID2_YC) |
    (1 << CompactRecord.GRID4_N4_YC) |
    (1 << CompactRecord.GRID4_N4_Y) |
    (1 << CompactRecord.GRID4_YC) |
    (1 << CompactRecord.LOW2);

  private static final String FAILED = "MCV2 native kernel failed";

  /** The segments a coder's kernels remember, a power of two: more than the buffers a coder passes. */
  private static final int SEGMENTS = 256;

  /** The bound kernels of one level of a loaded library, shared by every coder. */
  static final class Binding {

    private final Level level;

    private final MethodHandle predicted;

    private final MethodHandle solid;

    private final MethodHandle palette;

    private final MethodHandle intraGrid;

    private final MethodHandle residualGrid;

    private final MethodHandle reduced;

    private final MethodHandle compact;

    private final MethodHandle predict;

    private final MethodHandle fit;

    private final MethodHandle cellSums;

    private final MethodHandle lumaResidual;

    private final MethodHandle cluster;

    private final MethodHandle paletteCluster;

    private final MethodHandle assign;

    private final MethodHandle assignPattern;

    private final MethodHandle seeded;

    private final MethodHandle loadSource;

    private final MethodHandle halve;

    private final MethodHandle ycocg;

    private final MethodHandle residualTarget;

    private final MethodHandle cellMeans;

    /**
     * Binds a level's kernels.
     *
     * @param level  the level
     * @param handle gives the handle of a kernel from its name in the library, such as {@code compact}, and its
     *               descriptor
     */
    Binding(final Level level, final BiFunction<String, FunctionDescriptor, MethodHandle> handle) {
      this.level = level;
      this.predicted = handle.apply("predicted", PREDICTED);
      this.solid = handle.apply("solid", SOLID);
      this.palette = handle.apply("palette", PALETTE);
      this.intraGrid = handle.apply("intra_grid", INTRA_GRID);
      this.residualGrid = handle.apply("residual_grid", RESIDUAL_GRID);
      this.reduced = handle.apply("reduced", REDUCED);
      this.compact = handle.apply("compact", COMPACT);
      this.predict = handle.apply("predict", PREDICT);
      this.fit = handle.apply("fit", FIT);
      this.cellSums = handle.apply("cell_sums", BLOCK_TO_ARRAY);
      this.lumaResidual = handle.apply("luma_residual", LUMA_RESIDUAL);
      this.cluster = handle.apply("cluster", BLOCK_TO_ARRAY);
      this.paletteCluster = handle.apply("palette_cluster", BLOCK_TO_ARRAY);
      this.assign = handle.apply("assign", ASSIGN);
      this.assignPattern = handle.apply("assign_pattern", ASSIGN_PATTERN);
      this.seeded = handle.apply("seeded", SEEDED);
      this.loadSource = handle.apply("load_source", LOAD_SOURCE);
      this.halve = handle.apply("halve", BLOCK_TO_ARRAY);
      this.ycocg = handle.apply("ycocg", YCOCG);
      this.residualTarget = handle.apply("residual_target", RESIDUAL_TARGET);
      this.cellMeans = handle.apply("cell_means", CELL_MEANS);
    }

    /**
     * Binds a level's kernels from a loaded library.
     *
     * @param library the library's symbols
     * @param level   the level, which the CPU must run
     * @return the kernels
     * @throws NoSuchElementException if the library lacks one of them
     */
    // the downcalls are the accelerator itself, and Mcv2Natives only binds a library it checked
    @SuppressWarnings("restricted")
    static Binding of(final SymbolLookup library, final Level level) {
      final Linker linker = Linker.nativeLinker();
      return new Binding(level, (name, descriptor) ->
        linker.downcallHandle(library.findOrThrow("mcv2_" + level.symbol() + "_" + name), descriptor, CRITICAL)
      );
    }

    /**
     * Gets the level these kernels run at.
     *
     * @return the level
     */
    Level level() {
      return this.level;
    }
  }

  private final Binding binding;

  /** The block the next scored kernel measures, and its rate and limit, from {@link #start}. */
  private int[] source = new int[0];

  private double rate;

  private double limit;

  private long distortion;

  /** The Java kernels for what the library does not do: compact classes the encoder never tries. */
  private final JavaKernels fallback = new JavaKernels();

  /**
   * The heap segments of the arrays passed lately, by the slot of each array's identity hash: a coder passes the same
   * buffers block after block, and a segment made for every call was most of what a live frame allocated.
   */
  private final Object[] cachedArrays = new Object[SEGMENTS];

  private final MemorySegment[] cachedSegments = new MemorySegment[SEGMENTS];

  NativeKernels(final Binding binding) {
    this.binding = binding;
  }

  /**
   * Gets the level these kernels run at.
   *
   * @return the level
   */
  Level level() {
    return this.binding.level();
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

  /** Whether a slot holds an array's segment: identity is the point, since a segment wraps that one array. */
  @SuppressWarnings("ReferenceEquality")
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

  /**
   * Whether the kernels keep an array.
   *
   * @param array the array
   * @return true if its segment is kept
   */
  @VisibleForTesting
  boolean keeps(final Object array) {
    return this.remembers(slot(array), array);
  }

  @Override
  void forgetArrays() {
    Arrays.fill(this.cachedArrays, null);
    Arrays.fill(this.cachedSegments, null);
  }

  private static void checkSize(final int size) {
    Preconditions.checkArgument(size == SMALLEST_BLOCK || size == 2 * SMALLEST_BLOCK || size == ROOT_SIZE, "Invalid block size");
  }

  private static void checkGrid(final int grid) {
    Preconditions.checkArgument(grid > 0 && grid <= MAX_GRID && Integer.bitCount(grid) == 1, "Invalid grid width");
  }

  /** Checks that an array holds {@code count} values from {@code offset}; every count asked for is positive. */
  private static void checkRange(final int length, final int offset, final int count) {
    Preconditions.checkArgument(offset >= 0 && offset <= length - count, "Array too short");
  }

  /** Checks that an array holds a block's channels. */
  private static void checkBlock(final int length, final int size) {
    checkRange(length, 0, size * size * CHANNELS);
  }

  /** Checks {@code values} values from {@code offset}, one every {@code stride}. */
  private static void checkStrided(final int length, final int offset, final int stride, final int values) {
    Preconditions.checkArgument(stride > 0 && stride <= CHANNELS, "Invalid stride");
    checkRange(length, offset, (values - 1) * stride + 1);
  }

  private static void checkPicture(final int length, final int width, final int height) {
    Preconditions.checkArgument(width >= 1 && width <= MAX_SIDE && height >= 1 && height <= MAX_SIDE, "Invalid picture size");
    checkRange(length, 0, width * height * CHANNELS);
  }

  private static void checkCoordinate(final int value) {
    Preconditions.checkArgument(value >= -MAX_COORDINATE && value <= MAX_COORDINATE, "Coordinate out of range");
  }

  /** Checks the arrays of a scored kernel. */
  private void checkScored(final int size, final int[] out) {
    checkSize(size);
    checkBlock(out.length, size);
    checkBlock(this.source.length, size);
  }

  /** Keeps a scored kernel's distortion: whether it finished. */
  private boolean finished(final long measured) {
    if (measured < 0) {
      return false;
    }
    this.distortion = measured;
    return true;
  }

  @Override
  void start(final int[] source, final double rate, final double limit) {
    this.source = source;
    this.rate = rate;
    this.limit = limit;
    this.fallback.start(source, rate, limit);
  }

  @Override
  long distortion() {
    return this.distortion;
  }

  @Override
  boolean predicted(final int[] prediction, final int size, final int[] out) {
    this.checkScored(size, out);
    checkBlock(prediction.length, size);
    try {
      return this.finished(
        (long) this.binding.predicted.invokeExact(of(prediction), size, of(out), of(this.source), this.rate, this.limit)
      );
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  boolean solid(final int color, final int size, final int[] out) {
    this.checkScored(size, out);
    try {
      return this.finished((long) this.binding.solid.invokeExact(color, size, of(out), of(this.source), this.rate, this.limit));
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  boolean palette(final byte[] record, final int offset, final int size, final int[] out) {
    this.checkScored(size, out);
    checkRange(record.length, offset, SELECTORS_AT + (size * size) / Byte.SIZE);
    try {
      return this.finished(
        (long) this.binding.palette.invokeExact(of(record), offset, size, of(out), of(this.source), this.rate, this.limit)
      );
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  boolean intraGrid(final byte[] record, final int offset, final int grid, final int size, final int[] out) {
    this.checkScored(size, out);
    checkGrid(grid);
    checkRange(record.length, offset, CHANNELS * grid * grid);
    try {
      return this.finished(
        (long) this.binding.intraGrid.invokeExact(of(record), offset, grid, size, of(out), of(this.source), this.rate, this.limit)
      );
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  boolean residualGrid(
    final int[] prediction,
    final byte[] record,
    final int offset,
    final int grid,
    final int quantizer,
    final int size,
    final int[] out
  ) {
    this.checkScored(size, out);
    checkBlock(prediction.length, size);
    checkGrid(grid);
    checkRange(record.length, offset, CHANNELS * grid * grid);
    try {
      return this.finished(
        (long) this.binding.residualGrid.invokeExact(
          of(prediction),
          of(record),
          offset,
          grid,
          quantizer,
          size,
          of(out),
          of(this.source),
          this.rate,
          this.limit
        )
      );
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  boolean reduced(
    final int @Nullable [] prediction,
    final byte[] record,
    final int offset,
    final int luma,
    final int chroma,
    final int quantizer,
    final int size,
    final int[] out
  ) {
    this.checkScored(size, out);
    checkGrid(luma);
    checkGrid(chroma);
    checkRange(record.length, offset, luma * luma + CHROMA_PLANES * chroma * chroma);
    final MemorySegment predicted;
    if (prediction == null) {
      predicted = MemorySegment.NULL;
    } else {
      checkBlock(prediction.length, size);
      predicted = of(prediction);
    }
    try {
      return this.finished(
        (long) this.binding.reduced.invokeExact(
          predicted,
          prediction == null ? 1 : 0,
          of(record),
          offset,
          luma,
          chroma,
          quantizer,
          size,
          of(out),
          of(this.source),
          this.rate,
          this.limit
        )
      );
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  boolean compact(
    final int[] prediction,
    final byte[] record,
    final int body,
    final int kind,
    final int quantizer,
    final int size,
    final int[] out
  ) {
    Preconditions.checkArgument(kind >= 0 && kind <= CompactRecord.LOW2, "Invalid compact class");
    if (((NATIVE_CLASSES >> kind) & 1) == 0) {
      if (!this.fallback.compact(prediction, record, body, kind, quantizer, size, out)) {
        return false;
      }
      this.distortion = this.fallback.distortion();
      return true;
    }
    this.checkScored(size, out);
    checkBlock(prediction.length, size);
    checkRange(record.length, body, CompactRecord.bodyBytes(kind));
    try {
      return this.finished(
        (long) this.binding.compact.invokeExact(
          of(prediction),
          of(record),
          body,
          kind,
          quantizer,
          size,
          of(out),
          of(this.source),
          this.rate,
          this.limit
        )
      );
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  void predict(
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
    checkPicture(reference.length, width, height);
    checkSize(size);
    checkBlock(out.length, size);
    checkCoordinate(blockLeft);
    checkCoordinate(blockTop);
    checkCoordinate(motionX);
    checkCoordinate(motionY);
    try {
      this.binding.predict.invokeExact(of(reference), width, height, blockLeft, blockTop, size, motionX, motionY, of(out));
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  void fit(
    final float[] values,
    final int offset,
    final int stride,
    final int size,
    final int grid,
    final float[] out,
    final int outOffset,
    final int outStride
  ) {
    checkSize(size);
    checkGrid(grid);
    checkStrided(values.length, offset, stride, size * size);
    Preconditions.checkArgument(outStride > 0, "Invalid stride");
    checkRange(out.length, outOffset, (grid * grid - 1) * outStride + 1);
    try {
      this.binding.fit.invokeExact(of(values), offset, stride, size, grid, of(Fits.matrix(size, grid)), of(out), outOffset, outStride);
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  void cellSums(final int[] source, final int size, final int[] sums) {
    checkSize(size);
    checkBlock(source.length, size);
    checkRange(sums.length, 0, FastFits.CELL_SUMS);
    try {
      this.binding.cellSums.invokeExact(of(source), size, of(sums));
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  void lumaResidual(final int[] source, final int[] prediction, final int size, final float[] nodes) {
    checkSize(size);
    checkBlock(source.length, size);
    checkBlock(prediction.length, size);
    checkRange(nodes.length, 0, FastFits.CELL_GRID * FastFits.CELL_GRID);
    try {
      this.binding.lumaResidual.invokeExact(of(source), of(prediction), size, of(nodes));
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  void cluster(final int[] source, final int size, final float[] endpoints) {
    checkSize(size);
    checkBlock(source.length, size);
    checkRange(endpoints.length, 0, PALETTE_COLORS * CHANNELS);
    try {
      this.binding.cluster.invokeExact(of(source), size, of(endpoints));
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  void paletteCluster(final int[] source, final int count, final float[] endpoints) {
    Preconditions.checkArgument(count > 0 && count <= ROOT_SIZE * ROOT_SIZE, "Invalid pixel count");
    checkRange(source.length, 0, count * CHANNELS);
    checkRange(endpoints.length, 0, PALETTE_COLORS * CHANNELS);
    try {
      this.binding.paletteCluster.invokeExact(of(source), count, of(endpoints));
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  void finish(
    final int[] source,
    final int count,
    final float[] endpoints,
    final boolean quantize,
    final int[] colors,
    final byte[] selectors
  ) {
    Preconditions.checkArgument(count >= 0 && count <= ROOT_SIZE * ROOT_SIZE, "Invalid pixel count");
    checkRange(source.length, 0, count * CHANNELS);
    checkRange(selectors.length, 0, count);
    PaletteFit.round(endpoints, quantize, colors);
    try {
      this.binding.assign.invokeExact(of(source), count, of(colors), of(selectors));
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  boolean finishPattern(
    final int[] source,
    final int size,
    final float[] endpoints,
    final boolean quantize,
    final int[] colors,
    final byte[] selectors
  ) {
    checkSize(size);
    checkBlock(source.length, size);
    checkRange(selectors.length, 0, size * size);
    PaletteFit.round(endpoints, quantize, colors);
    try {
      return (int) this.binding.assignPattern.invokeExact(of(source), size, of(colors), of(selectors)) != 0;
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  int seeded(
    final byte[] reference,
    final int width,
    final int height,
    final int[] source,
    final int blockLeft,
    final int blockTop,
    final int size,
    final int globalX,
    final int globalY,
    final int range,
    final boolean halfPixel,
    final int[] seeds
  ) {
    checkPicture(reference.length, width, height);
    checkSize(size);
    checkBlock(source.length, size);
    checkCoordinate(blockLeft);
    checkCoordinate(blockTop);
    checkCoordinate(globalX);
    checkCoordinate(globalY);
    Preconditions.checkArgument(range >= 0 && range <= MAX_COORDINATE, "Invalid range");
    try {
      return (int) this.binding.seeded.invokeExact(
        of(reference),
        width,
        height,
        of(source),
        blockLeft,
        blockTop,
        size,
        globalX,
        globalY,
        range,
        halfPixel ? 1 : 0,
        of(seeds),
        seeds.length
      );
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  void loadSource(
    final byte[] image,
    final int width,
    final int height,
    final int blockLeft,
    final int blockTop,
    final int size,
    final int[] source
  ) {
    checkPicture(image.length, width, height);
    checkSize(size);
    checkBlock(source.length, size);
    Preconditions.checkArgument(blockLeft >= 0 && blockLeft < width && blockTop >= 0 && blockTop < height, "Block outside the picture");
    try {
      this.binding.loadSource.invokeExact(of(image), width, height, blockLeft, blockTop, size, of(source));
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  void halve(final int[] block, final int size, final int[] out) {
    Preconditions.checkArgument(size >= 2 && size <= ROOT_SIZE && size % 2 == 0, "Invalid block size");
    checkBlock(block.length, size);
    checkBlock(out.length, size / 2);
    try {
      this.binding.halve.invokeExact(of(block), size, of(out));
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  void ycocg(final int[] source, final int count, final boolean chroma, final float[] out) {
    Preconditions.checkArgument(count >= 0 && count <= ROOT_SIZE * ROOT_SIZE, "Invalid pixel count");
    checkRange(source.length, 0, count * CHANNELS);
    checkRange(out.length, 0, count * CHANNELS);
    try {
      this.binding.ycocg.invokeExact(of(source), count, chroma ? 1 : 0, of(out));
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  void residualTarget(final float[] ycocg, final int[] prediction, final int count, final boolean chroma, final float[] target) {
    Preconditions.checkArgument(count >= 0 && count <= ROOT_SIZE * ROOT_SIZE, "Invalid pixel count");
    checkRange(ycocg.length, 0, count * CHANNELS);
    checkRange(prediction.length, 0, count * CHANNELS);
    checkRange(target.length, 0, count * CHANNELS);
    try {
      this.binding.residualTarget.invokeExact(of(ycocg), of(prediction), count, chroma ? 1 : 0, of(target));
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }

  @Override
  void cellMeans(
    final float[] target,
    final int size,
    final int channel,
    final int grid,
    final float[] out,
    final int outOffset,
    final int outStride
  ) {
    checkSize(size);
    checkGrid(grid);
    Preconditions.checkArgument(channel >= 0 && channel < CHANNELS, "Invalid channel");
    checkBlock(target.length, size);
    Preconditions.checkArgument(outStride > 0, "Invalid stride");
    checkRange(out.length, outOffset, (grid * grid - 1) * outStride + 1);
    try {
      this.binding.cellMeans.invokeExact(of(target), size, channel, grid, of(out), outOffset, outStride);
    } catch (final Throwable failure) {
      throw new IllegalStateException(FAILED, failure);
    }
  }
}
