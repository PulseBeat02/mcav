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

import com.google.common.base.Preconditions;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Validates and decodes MCV2 version 3. Parsed frames own their bytes and are immutable.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class Mcv2Decoder {

  /**
   * Fixed frame header size in bytes.
   *
   * @since 1.0.0
   */
  public static final int HEADER_BYTES = 20;
  /**
   * Largest accepted frame length in bytes.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_FRAME_BYTES = 131071;
  /**
   * Largest width or height in pixels.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_DIMENSION = 4096;

  static final int MAGIC = 0x3256434D;
  static final int VERSION = 3;

  static final int ROOT_SIZE = 32;
  static final int SMALLEST_BLOCK = 8;
  static final int BLOCK_SIZES = 3;
  static final int QUARTERS = 4;
  static final int GROUP_ROOTS = 32;
  static final int CHECKPOINT_GROUPS = 8;
  static final int WALK_SPAN = 8;

  static final int CHANNELS = 3;
  private static final int MAXIMUM_CHANNEL = 255;

  static final int MODE_SKIP = 0;
  static final int MODE_MOTION = 1;
  static final int MODE_SOLID = 2;
  static final int MODE_PALETTE = 3;
  static final int MODE_PATTERN = 4;
  static final int MODE_COMPACT = 5;
  static final int MODE_SPLIT = 6;
  private static final int MODE_MASK = 31;

  static final int QUANTIZER_SHIFT = 5;
  static final int MAXIMUM_QUANTIZER = 2;
  static final int NO_QUANTIZER = 0;

  static final int COMPACT_BYTES = 10;

  private static final int MAGIC_OFFSET = 0;
  private static final int VERSION_OFFSET = 4;
  static final int DIMENSIONS_OFFSET = 8;
  /**
   * Header byte offset of the frame id.
   *
   * @since 1.0.0
   */
  public static final int FRAME_ID_OFFSET = 12;
  /**
   * Header byte offset of the reference id.
   *
   * @since 1.0.0
   */
  public static final int REFERENCE_ID_OFFSET = 16;

  /**
   * Largest unsigned 32-bit value.
   *
   * @since 1.0.0
   */
  public static final long MAXIMUM_UNSIGNED_INT = 0xFFFFFFFFL;

  private static final long LEGACY_MAGIC = 0x3156434D;
  private static final int LEGACY_VERSION = 2;

  private static final int LEAF_INTS = 6;
  private static final int POSITION_INTS = 3;

  static final int CURSOR_BITS = 17;

  private static final int GRID = 4;

  private static final byte[] NO_REFERENCE = new byte[0];

  private static final int NO_RECORD_OFFSET = -1;

  private static final int TOP_INDEX = 1;
  private static final int SIZE_INDEX = 2;
  private static final int MODE_INDEX = 3;
  private static final int QUANTIZER_INDEX = 4;
  private static final int RECORD_OFFSET_INDEX = 5;

  private static final int LEVEL_TWO_COUNT_INDEX = 2;

  private static final int QUARTERS_PER_SIDE = 2;

  private static final int SMALLEST_BLOCK_SHIFT = 3;

  private static final int RECORD_COLORS = 2;

  private static final int MOTION_BYTES = 2;
  private static final int PATTERN_SELECTOR_BYTES = 1;

  private static final int NODES_PER_BYTE = 2;
  private static final int NODE_BITS = 4;

  private static final int HALF_PIXEL_SCALE = 2;

  private static final int INTERPOLATED_AXES = 2;

  private static final int BYTE_MASK = 0xFF;
  private static final long LONG_BYTE_MASK = 0xFFL;

  private static final int SECOND_BYTE_OFFSET = 1;
  private static final int THIRD_BYTE_OFFSET = 2;
  private static final int FOURTH_BYTE_OFFSET = 3;

  private static final int SECOND_BYTE_SHIFT = 8;
  private static final int THIRD_BYTE_SHIFT = 16;
  private static final int FOURTH_BYTE_SHIFT = 24;

  private static final int GREEN_OFFSET = 1;
  private static final int BLUE_OFFSET = 2;

  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final int FRAME_ID_WINDOW_BITS = 31;

  static final int[][] GRID_LOWER = new int[BLOCK_SIZES][];
  static final int[][] GRID_UPPER = new int[BLOCK_SIZES][];
  static final int[][] GRID_WEIGHTS = new int[BLOCK_SIZES][];

  static {
    for (int index = 0; index < BLOCK_SIZES; index++) {
      final int size = SMALLEST_BLOCK << index;
      final int span = HALF_PIXEL_SCALE * size;
      GRID_LOWER[index] = new int[size];
      GRID_UPPER[index] = new int[size];
      GRID_WEIGHTS[index] = new int[size];
      for (int pixel = 0; pixel < size; pixel++) {
        final int position = Math.min(Math.max((HALF_PIXEL_SCALE * pixel + 1) * GRID - size, 0), (GRID - 1) * span);
        GRID_LOWER[index][pixel] = position / span;
        GRID_UPPER[index][pixel] = Math.min(position / span + 1, GRID - 1);
        GRID_WEIGHTS[index][pixel] = position % span;
      }
    }
  }

  private Mcv2Decoder() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  /**
   * Validates every header, tree, checkpoint and record before retaining an owned copy.
   *
   * @param bytes one complete frame
   * @return immutable validated frame
   * @throws NullPointerException if bytes is null
   * @throws Mcv2Exception        if any version 3 validation rule fails, including unsupported older versions
   * @since 1.0.0
   */
  public static Frame parse(final byte[] bytes) {
    Preconditions.checkNotNull(bytes, "Frame bytes must not be null!");

    if (bytes.length >= Integer.BYTES && readUnsignedInt(bytes, MAGIC_OFFSET) == LEGACY_MAGIC) {
      final String message = "MCV1 version 1 is no longer supported; re-encode as MCV2 version 3!";
      throw new Mcv2Exception(message);
    }

    if (bytes.length < Integer.BYTES || readUnsignedInt(bytes, MAGIC_OFFSET) != MAGIC) {
      final String message = "Not an MCV2 frame!";
      throw new Mcv2Exception(message);
    }

    if (bytes.length > Integer.BYTES && bytes[VERSION_OFFSET] == LEGACY_VERSION) {
      final String message = "MCV2 version 2 is no longer supported; re-encode as version 3!";
      throw new Mcv2Exception(message);
    }

    if (bytes.length < HEADER_BYTES || bytes.length > MAXIMUM_FRAME_BYTES) {
      final String message = "Invalid frame length!";
      throw new Mcv2Exception(message);
    }

    final byte[] data = bytes.clone();
    if (readUnsignedInt(data, VERSION_OFFSET) != VERSION) {
      final String message = "Not an MCV2 version 3 frame!";
      throw new Mcv2Exception(message);
    }

    final int width = readUnsignedShort(data, DIMENSIONS_OFFSET);
    final int height = readUnsignedShort(data, DIMENSIONS_OFFSET + Short.BYTES);
    if (width < 1 || width > MAXIMUM_DIMENSION || height < 1 || height > MAXIMUM_DIMENSION) {
      final String message = "Invalid dimensions!";
      throw new Mcv2Exception(message);
    }

    final boolean keyframe = readUnsignedInt(data, FRAME_ID_OFFSET) == readUnsignedInt(data, REFERENCE_ID_OFFSET);
    return new Parser(data, width, height, keyframe).parse();
  }

  private static void writePosition(final int[] positions, final int index, final int left, final int top, final int size) {
    final int arrayOffset = index * POSITION_INTS;
    positions[arrayOffset] = left;
    positions[arrayOffset + TOP_INDEX] = top;
    positions[arrayOffset + SIZE_INDEX] = size;
  }

  private static void writeLeaf(
    final int[] leaves,
    final int index,
    final int left,
    final int top,
    final int size,
    final int mode,
    final int quantizer,
    final int offset
  ) {
    final int arrayOffset = index * LEAF_INTS;
    leaves[arrayOffset] = left;
    leaves[arrayOffset + TOP_INDEX] = top;
    leaves[arrayOffset + SIZE_INDEX] = size;
    leaves[arrayOffset + MODE_INDEX] = mode;
    leaves[arrayOffset + QUANTIZER_INDEX] = quantizer;
    leaves[arrayOffset + RECORD_OFFSET_INDEX] = offset;
  }

  /**
   * Parses and reconstructs a complete frame.
   *
   * @param bytes       complete encoded frame
   * @param reference   previous RGB24 picture; may be null for keyframes
   * @param referenceId id of that picture; ignored for keyframes
   * @return newly allocated RGB24 picture
   * @throws NullPointerException if bytes is null
   * @throws Mcv2Exception        if syntax or reference validation fails
   * @since 1.0.0
   */
  public static byte[] decode(final byte[] bytes, final byte @Nullable [] reference, final long referenceId) {
    return decode(parse(bytes), reference, referenceId);
  }

  /**
   * Reconstructs a validated frame into a new RGB24 array.
   *
   * @param frame       validated frame
   * @param reference   previous RGB24 picture; may be null for keyframes
   * @param referenceId id of that picture; ignored for keyframes
   * @return newly allocated RGB24 picture
   * @throws NullPointerException if frame is null
   * @throws Mcv2Exception        if the required reference is missing or has the wrong id or byte length
   * @since 1.0.0
   */
  public static byte[] decode(final Frame frame, final byte @Nullable [] reference, final long referenceId) {
    Preconditions.checkNotNull(frame, "Frame must not be null!");

    final byte[] output = new byte[frame.width * frame.height * CHANNELS];
    decode(frame, reference, referenceId, output);
    return output;
  }

  /**
   * Reconstructs a complete frame. An output alias of the reference uses an owned snapshot.
   *
   * @param frame       validated frame
   * @param reference   previous RGB24 picture; may be null for keyframes
   * @param referenceId id of that picture; ignored for keyframes
   * @param output      array of exactly width * height * 3 bytes
   * @throws NullPointerException     if frame or output is null
   * @throws IllegalArgumentException if output has the wrong length
   * @throws Mcv2Exception            if the required reference is missing or has the wrong id or byte length
   * @since 1.0.0
   */
  public static void decode(final Frame frame, final byte @Nullable [] reference, final long referenceId, final byte[] output) {
    Preconditions.checkNotNull(frame, "Frame must not be null!");
    Preconditions.checkNotNull(output, "Output must not be null!");

    final byte[] prediction = requireMatchingReference(frame, reference, referenceId);
    decodeRows(frame, samePicture(prediction, output) ? prediction.clone() : prediction, referenceId, output, 0, frame.height);
  }

  static void decodeRows(
    final Frame frame,
    final byte @Nullable [] reference,
    final long referenceId,
    final byte[] output,
    final int fromRow,
    final int toRow
  ) {
    final byte[] prediction = requireMatchingReference(frame, reference, referenceId);
    Preconditions.checkArgument(output.length == frame.width * frame.height * CHANNELS, "Output size does not match the frame!");
    Preconditions.checkArgument(fromRow >= 0 && fromRow <= toRow && toRow <= frame.height, "Invalid row range!");
    Preconditions.checkArgument(!samePicture(prediction, output), "Row decoding needs a separate reference!");

    final Context context = new Context(frame, prediction, output);
    for (int arrayOffset = 0; arrayOffset < frame.leaves.length; arrayOffset += LEAF_INTS) {
      context.decodeLeaf(arrayOffset, fromRow, toRow);
    }
  }

  private static byte[] requireMatchingReference(final Frame frame, final byte @Nullable [] reference, final long referenceId) {
    if (frame.keyframe) {
      return NO_REFERENCE;
    }

    if (reference == null || referenceId != frame.referenceId || reference.length != frame.width * frame.height * CHANNELS) {
      final String message = "Reference frame mismatch!";
      throw new Mcv2Exception(message);
    }

    return reference;
  }

  @SuppressWarnings("ReferenceEquality") // pictures are pooled buffers told apart by identity, not content
  private static boolean samePicture(final byte[] first, final byte[] second) {
    return first == second;
  }

  static void interpolateHorizontally(final int[] nodes, final int size, final int[] rows) {
    final int[] lower = GRID_LOWER[sizeIndex(size)];
    final int[] upper = GRID_UPPER[sizeIndex(size)];
    final int[] weights = GRID_WEIGHTS[sizeIndex(size)];
    final int span = HALF_PIXEL_SCALE * size;
    for (int row = 0; row < GRID; row++) {
      for (int column = 0; column < size; column++) {
        final int weight = weights[column];
        rows[row * size + column] = nodes[row * GRID + lower[column]] * (span - weight) + nodes[row * GRID + upper[column]] * weight;
      }
    }
  }

  static int roundToChannel(final int value, final int shift) {
    return Math.min(Math.max((value + (1 << (shift - 1))) >> shift, 0), MAXIMUM_CHANNEL);
  }

  private static int readColor(final byte[] data, final int arrayOffset) {
    return (
      ((data[arrayOffset] & BYTE_MASK) << RED_SHIFT) |
      ((data[arrayOffset + GREEN_OFFSET] & BYTE_MASK) << GREEN_SHIFT) |
      (data[arrayOffset + BLUE_OFFSET] & BYTE_MASK)
    );
  }

  /**
   * Reads an unsigned little-endian 16-bit integer.
   *
   * @param data        source bytes
   * @param arrayOffset first byte offset
   * @return unsigned value
   * @throws IndexOutOfBoundsException if either byte lies outside data
   * @since 1.0.0
   */
  public static int readUnsignedShort(final byte[] data, final int arrayOffset) {
    return (data[arrayOffset] & BYTE_MASK) | ((data[arrayOffset + SECOND_BYTE_OFFSET] & BYTE_MASK) << SECOND_BYTE_SHIFT);
  }

  /**
   * Reads an unsigned little-endian 32-bit integer.
   *
   * @param data        source bytes
   * @param arrayOffset first byte offset
   * @return unsigned value
   * @throws IndexOutOfBoundsException if any byte lies outside data
   * @since 1.0.0
   */
  public static long readUnsignedInt(final byte[] data, final int arrayOffset) {
    return (
      (data[arrayOffset] & LONG_BYTE_MASK) |
      ((data[arrayOffset + SECOND_BYTE_OFFSET] & LONG_BYTE_MASK) << SECOND_BYTE_SHIFT) |
      ((data[arrayOffset + THIRD_BYTE_OFFSET] & LONG_BYTE_MASK) << THIRD_BYTE_SHIFT) |
      ((data[arrayOffset + FOURTH_BYTE_OFFSET] & LONG_BYTE_MASK) << FOURTH_BYTE_SHIFT)
    );
  }

  /**
   * Writes the low 16 bits in little-endian order.
   *
   * @param data        destination bytes
   * @param arrayOffset first byte offset
   * @param value       value to write
   * @throws IndexOutOfBoundsException if either byte lies outside data
   * @since 1.0.0
   */
  public static void putUnsignedShort(final byte[] data, final int arrayOffset, final int value) {
    data[arrayOffset] = (byte) value;
    data[arrayOffset + SECOND_BYTE_OFFSET] = (byte) (value >>> SECOND_BYTE_SHIFT);
  }

  /**
   * Writes the low 32 bits in little-endian order.
   *
   * @param data        destination bytes
   * @param arrayOffset first byte offset
   * @param value       value to write
   * @throws IndexOutOfBoundsException if any byte lies outside data
   * @since 1.0.0
   */
  public static void putUnsignedInt(final byte[] data, final int arrayOffset, final long value) {
    for (int index = 0; index < Integer.BYTES; index++) {
      data[arrayOffset + index] = (byte) (value >>> (index * Byte.SIZE));
    }
  }

  static boolean follows(final long nextFrameId, final long lastFrameId) {
    final long distance = (nextFrameId - lastFrameId) & MAXIMUM_UNSIGNED_INT;
    return distance != 0 && distance < 1L << FRAME_ID_WINDOW_BITS;
  }

  static boolean isBlockSize(final int size) {
    return size == SMALLEST_BLOCK || size == QUARTERS_PER_SIDE * SMALLEST_BLOCK || size == ROOT_SIZE;
  }

  static int sizeIndex(final int size) {
    return Integer.numberOfTrailingZeros(size) - SMALLEST_BLOCK_SHIFT;
  }

  static int signed(final int value, final int bits) {
    return (value << (Integer.SIZE - bits)) >> (Integer.SIZE - bits);
  }

  static int patternSize(final int size) {
    return RECORD_COLORS * CHANNELS + PATTERN_SELECTOR_BYTES + size / Byte.SIZE;
  }

  static int recordSize(final int mode, final int size) {
    return switch (mode) {
      case MODE_SKIP, MODE_SPLIT -> 0;
      case MODE_MOTION -> MOTION_BYTES;
      case MODE_COMPACT -> COMPACT_BYTES;
      case MODE_SOLID -> CHANNELS;
      case MODE_PALETTE -> RECORD_COLORS * CHANNELS + (size * size) / Byte.SIZE;
      case MODE_PATTERN -> patternSize(size);
      default -> {
        final String message = "Invalid record mode!";
        throw new IllegalArgumentException(message);
      }
    };
  }

  record Leaf(int left, int top, int size, int mode, int quantizer, int offset) {}

  /**
   * A complete validated frame. Accessors copy mutable data.
   *
   * <p>This class is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public static final class Frame {

    private final byte[] data;
    private final int width;
    private final int height;
    private final long frameId;
    private final long referenceId;
    private final boolean keyframe;
    private final int payloadStart;
    private final int[] leaves;

    private Frame(final Parser parser, final int[] leaves) {
      this.data = parser.data;
      this.width = parser.width;
      this.height = parser.height;
      this.frameId = readUnsignedInt(this.data, FRAME_ID_OFFSET);
      this.referenceId = readUnsignedInt(this.data, REFERENCE_ID_OFFSET);
      this.keyframe = parser.keyframe;
      this.payloadStart = parser.start;
      this.leaves = leaves;
    }

    Leaf getLeaf(final int index) {
      Preconditions.checkElementIndex(index, this.getLeafCount(), "Leaf index");

      final int arrayOffset = index * LEAF_INTS;
      return new Leaf(
        this.leaves[arrayOffset],
        this.leaves[arrayOffset + TOP_INDEX],
        this.leaves[arrayOffset + SIZE_INDEX],
        this.leaves[arrayOffset + MODE_INDEX],
        this.leaves[arrayOffset + QUANTIZER_INDEX],
        this.leaves[arrayOffset + RECORD_OFFSET_INDEX]
      );
    }

    /**
     * Returns an independent copy of the frame bytes.
     *
     * @return an independent copy of the frame bytes
     * @since 1.0.0
     */
    public byte[] getData() {
      return this.data.clone();
    }

    /**
     * Returns picture width in pixels.
     *
     * @return picture width in pixels
     * @since 1.0.0
     */
    public int getWidth() {
      return this.width;
    }

    /**
     * Returns picture height in pixels.
     *
     * @return picture height in pixels
     * @since 1.0.0
     */
    public int getHeight() {
      return this.height;
    }

    /**
     * Returns unsigned 32-bit frame id.
     *
     * @return unsigned 32-bit frame id
     * @since 1.0.0
     */
    public long getFrameId() {
      return this.frameId;
    }

    /**
     * Returns unsigned 32-bit reference id.
     *
     * @return unsigned 32-bit reference id
     * @since 1.0.0
     */
    public long getReferenceId() {
      return this.referenceId;
    }

    /**
     * Returns whether the picture is independent of any reference.
     *
     * @return whether the picture is independent of any reference
     * @since 1.0.0
     */
    public boolean isKeyframe() {
      return this.keyframe;
    }

    int getPayloadStart() {
      return this.payloadStart;
    }

    int getLeafCount() {
      return this.leaves.length / LEAF_INTS;
    }
  }

  private static final class Parser {

    private final byte[] data;
    private final int width;
    private final int height;
    private final boolean keyframe;
    private final int columns;
    private final int roots;

    private int start;

    private Parser(final byte[] data, final int width, final int height, final boolean keyframe) {
      this.data = data;
      this.width = width;
      this.height = height;
      this.keyframe = keyframe;
      this.columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
      this.roots = this.columns * ((height + ROOT_SIZE - 1) / ROOT_SIZE);
    }

    private Frame parse() {
      final IndexLayout layout = this.readIndexLength();
      final int[] present = this.readDirectory(layout.groups, layout.firstChildren);
      final int splits = this.countSplits(layout);
      final int[] positions = this.placeChildren(layout, present);
      final int[] leaves = this.readLeaves(layout, positions, splits);
      this.addSkippedRoots(layout, present, splits, leaves);

      return new Frame(this, leaves);
    }

    private IndexLayout readIndexLength() {
      final int groups = (this.roots + GROUP_ROOTS - 1) / GROUP_ROOTS;
      final int checkpoints = (groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS;
      final int countsAt = HEADER_BYTES + (groups + checkpoints) * Integer.BYTES;
      final int descriptorsAt = countsAt + BLOCK_SIZES * Integer.BYTES;
      if (descriptorsAt > this.data.length) {
        final String message = "Truncated index!";
        throw new Mcv2Exception(message);
      }

      // Unsigned counts must fit the input before narrowing to int.
      final long levelZero = readUnsignedInt(this.data, countsAt);
      final long levelOne = readUnsignedInt(this.data, countsAt + Integer.BYTES);
      final long levelTwo = readUnsignedInt(this.data, countsAt + LEVEL_TWO_COUNT_INDEX * Integer.BYTES);
      final long count = levelZero + levelOne + levelTwo;
      final long start = descriptorsAt + count + ((count + WALK_SPAN - 1) / WALK_SPAN) * Integer.BYTES;
      if (start > this.data.length) {
        final String message = "Invalid index length!";
        throw new Mcv2Exception(message);
      }

      this.start = (int) start;
      final int descriptors = (int) count;
      final int firstChildren = (int) levelZero;
      final int lastLevel = (int) (levelZero + levelOne);
      final int walksAt = descriptorsAt + descriptors;
      return new IndexLayout(groups, descriptorsAt, descriptors, firstChildren, lastLevel, walksAt, levelOne, levelTwo);
    }

    private int countSplits(final IndexLayout layout) {
      int splits = 0;
      int firstSplits = 0;
      for (int index = 0; index < layout.descriptors; index++) {
        final int mode = this.readDescriptorMode(layout.descriptorsAt + index);
        if (mode != MODE_SPLIT) {
          continue;
        }
        if (index >= layout.lastLevel) {
          final String message = "Split below the bounded depth!";
          throw new Mcv2Exception(message);
        }
        splits++;
        if (index < layout.firstChildren) {
          firstSplits++;
        }
      }

      if ((long) QUARTERS * firstSplits != layout.levelOne || (long) QUARTERS * (splits - firstSplits) != layout.levelTwo) {
        final String message = "Level counts disagree with splits!";
        throw new Mcv2Exception(message);
      }

      return splits;
    }

    private int[] placeChildren(final IndexLayout layout, final int[] present) {
      final int[] positions = new int[layout.descriptors * POSITION_INTS];
      for (int index = 0; index < layout.firstChildren; index++) {
        writePosition(
          positions,
          index,
          (present[index] % this.columns) * ROOT_SIZE,
          (present[index] / this.columns) * ROOT_SIZE,
          ROOT_SIZE
        );
      }

      int child = layout.firstChildren;
      for (int index = 0; index < layout.lastLevel; index++) {
        if ((this.data[layout.descriptorsAt + index] & MODE_MASK) != MODE_SPLIT) {
          continue;
        }
        final int arrayOffset = index * POSITION_INTS;
        final int half = positions[arrayOffset + SIZE_INDEX] / QUARTERS_PER_SIDE;
        for (int corner = 0; corner < QUARTERS; corner++) {
          writePosition(
            positions,
            child++,
            positions[arrayOffset] + (corner % QUARTERS_PER_SIDE) * half,
            positions[arrayOffset + TOP_INDEX] + (corner / QUARTERS_PER_SIDE) * half,
            half
          );
        }
      }

      return positions;
    }

    private int[] readLeaves(final IndexLayout layout, final int[] positions, final int totalSplits) {
      final int[] leaves = new int[(this.roots + layout.descriptors - layout.firstChildren - totalSplits) * LEAF_INTS];
      int leaf = 0;
      int cursor = 0;
      int splits = 0;
      final int payloadEnd = this.data.length;
      for (int index = 0; index < layout.descriptors; index++) {
        if (
          index % WALK_SPAN == 0 &&
          readUnsignedInt(this.data, layout.walksAt + (index / WALK_SPAN) * Integer.BYTES) != (cursor | ((long) splits << CURSOR_BITS))
        ) {
          final String message = "Invalid walk checkpoint!";
          throw new Mcv2Exception(message);
        }
        final int descriptor = this.data[layout.descriptorsAt + index] & BYTE_MASK;
        final int mode = descriptor & MODE_MASK;
        if (mode == MODE_SPLIT) {
          splits++;
          continue;
        }
        final int arrayOffset = index * POSITION_INTS;
        final int offset = this.start + cursor;
        final int size = positions[arrayOffset + SIZE_INDEX];
        cursor += this.checkRecord(mode, size, offset);
        writeLeaf(
          leaves,
          leaf++,
          positions[arrayOffset],
          positions[arrayOffset + TOP_INDEX],
          size,
          mode,
          descriptor >> QUANTIZER_SHIFT,
          offset
        );
      }

      if (this.start + cursor != payloadEnd) {
        final String message = "Records do not end at the frame end!";
        throw new Mcv2Exception(message);
      }

      return leaves;
    }

    private void addSkippedRoots(final IndexLayout layout, final int[] present, final int splits, final int[] leaves) {
      int leaf = layout.descriptors - splits;
      int listed = 0;
      for (int root = 0; root < this.roots; root++) {
        if (listed < present.length && present[listed] == root) {
          listed++;
        } else {
          writeLeaf(
            leaves,
            leaf++,
            (root % this.columns) * ROOT_SIZE,
            (root / this.columns) * ROOT_SIZE,
            ROOT_SIZE,
            MODE_SKIP,
            NO_QUANTIZER,
            NO_RECORD_OFFSET
          );
        }
      }
    }

    private int[] readDirectory(final int groups, final int count) {
      final int[] present = new int[count];
      int seen = 0;
      for (int group = 0; group < groups; group++) {
        final long mask = readUnsignedInt(this.data, HEADER_BYTES + group * Integer.BYTES);
        final int valid = Math.min(GROUP_ROOTS, this.roots - group * GROUP_ROOTS);
        if (mask >>> valid != 0 || Long.bitCount(mask) > count - seen) {
          final String message = "Invalid presence mask!";
          throw new Mcv2Exception(message);
        }
        if (
          group % CHECKPOINT_GROUPS == 0 &&
          readUnsignedInt(this.data, HEADER_BYTES + (groups + group / CHECKPOINT_GROUPS) * Integer.BYTES) != seen
        ) {
          final String message = "Invalid directory checkpoint!";
          throw new Mcv2Exception(message);
        }
        seen = this.listPresent(present, seen, mask, valid, group);
      }

      if (seen != count) {
        final String message = "Presence count differs from level zero!";
        throw new Mcv2Exception(message);
      }

      return present;
    }

    private int listPresent(final int[] present, final int seen, final long mask, final int valid, final int group) {
      int listed = seen;
      for (int bit = 0; bit < valid; bit++) {
        if (((mask >>> bit) & 1) != 0) {
          present[listed++] = group * GROUP_ROOTS + bit;
        }
      }

      return listed;
    }

    private int readDescriptorMode(final int position) {
      final int descriptor = this.data[position] & BYTE_MASK;
      final int mode = descriptor & MODE_MASK;
      final int quantizer = descriptor >> QUANTIZER_SHIFT;
      if (
        mode > MODE_SPLIT ||
        quantizer > MAXIMUM_QUANTIZER ||
        (quantizer != 0 && mode != MODE_COMPACT) ||
        (this.keyframe && (mode == MODE_MOTION || mode == MODE_COMPACT))
      ) {
        final String message = "Invalid descriptor!";
        throw new Mcv2Exception(message);
      }

      return mode;
    }

    private int checkRecord(final int mode, final int size, final int offset) {
      final int length = recordSize(mode, size);
      if (length > this.data.length - offset) {
        final String message = "Truncated record!";
        throw new Mcv2Exception(message);
      }

      if (mode == MODE_PATTERN && (this.data[offset + RECORD_COLORS * CHANNELS] & BYTE_MASK) > 1) {
        final String message = "Invalid pattern selector!";
        throw new Mcv2Exception(message);
      }

      return length;
    }

    private record IndexLayout(
      int groups,
      int descriptorsAt,
      int descriptors,
      int firstChildren,
      int lastLevel,
      int walksAt,
      long levelOne,
      long levelTwo
    ) {}
  }

  private static final class Context {

    private final Frame frame;
    private final byte[] reference;
    private final byte[] output;
    private final int[] nodes;
    private final int[] rows;

    private int left;
    private int top;
    private int size;
    private int mode;
    private int quantizer;
    private int offset;
    private int motionX;
    private int motionY;
    private int color0;
    private int color1;
    private int word;
    private int orientation;
    private int scale;
    private int shift;

    private Context(final Frame frame, final byte[] reference, final byte[] output) {
      this.frame = frame;
      this.reference = reference;
      this.output = output;
      this.nodes = new int[GRID * GRID];
      this.rows = new int[GRID * ROOT_SIZE];
    }

    private void decodeLeaf(final int arrayOffset, final int fromRow, final int toRow) {
      final int[] leaves = this.frame.leaves;
      final int leafLeft = leaves[arrayOffset];
      final int leafTop = leaves[arrayOffset + TOP_INDEX];
      final int leafSize = leaves[arrayOffset + SIZE_INDEX];
      final int firstRow = Math.max(leafTop, fromRow);
      final int lastRow = Math.min(leafTop + leafSize, toRow);
      final int right = Math.min(leafLeft + leafSize, this.frame.width);
      if (firstRow >= lastRow || leafLeft >= right) {
        return;
      }

      this.left = leafLeft;
      this.top = leafTop;
      this.size = leafSize;
      this.mode = leaves[arrayOffset + MODE_INDEX];
      this.quantizer = leaves[arrayOffset + QUANTIZER_INDEX];
      this.offset = leaves[arrayOffset + RECORD_OFFSET_INDEX];
      this.readRecord();
      this.scale = HALF_PIXEL_SCALE * HALF_PIXEL_SCALE * leafSize * leafSize;
      this.shift = INTERPOLATED_AXES * (Integer.numberOfTrailingZeros(leafSize) + 1);
      for (int row = firstRow; row < lastRow; row++) {
        this.decodeRow(row, right);
      }
    }

    private void readRecord() {
      final byte[] data = this.frame.data;
      this.motionX = 0;
      this.motionY = 0;
      this.color0 = 0;
      this.color1 = 0;
      this.word = 0;
      this.orientation = 0;
      if (this.mode == MODE_SOLID || this.mode == MODE_PALETTE) {
        this.color0 = readColor(data, this.offset);

        if (this.mode == MODE_PALETTE) {
          this.color1 = readColor(data, this.offset + CHANNELS);
        }
      } else if (this.mode == MODE_PATTERN) {
        this.color0 = readColor(data, this.offset);
        this.color1 = readColor(data, this.offset + CHANNELS);
        this.word = this.offset + RECORD_COLORS * CHANNELS;
        this.orientation = data[this.word];
        this.word++;
      } else if (this.mode == MODE_MOTION) {
        this.motionX = data[this.offset];
        this.motionY = data[this.offset + 1];
      } else if (this.mode == MODE_COMPACT) {
        this.motionX = data[this.offset];
        this.motionY = data[this.offset + 1];
        this.readNodes(data);
        interpolateHorizontally(this.nodes, this.size, this.rows);
      }
    }

    private void readNodes(final byte[] data) {
      for (int node = 0; node < GRID * GRID; node++) {
        this.nodes[node] = signed(
          (data[this.offset + MOTION_BYTES + node / NODES_PER_BYTE] & BYTE_MASK) >> ((node % NODES_PER_BYTE) * NODE_BITS),
          NODE_BITS
        );
      }
    }

    private void decodeRow(final int row, final int right) {
      final int localRow = row - this.top;
      final int lower = GRID_LOWER[sizeIndex(this.size)][localRow];
      final int upper = GRID_UPPER[sizeIndex(this.size)][localRow];
      final int weight = GRID_WEIGHTS[sizeIndex(this.size)][localRow];
      final int sourceRow = Math.min(Math.max(row + this.motionY, 0), this.frame.height - 1) * this.frame.width;
      for (int column = this.left; column < right; column++) {
        final int localColumn = column - this.left;
        final int target = (row * this.frame.width + column) * CHANNELS;
        if (this.isPredicted()) {
          final int source = (sourceRow + Math.min(Math.max(column + this.motionX, 0), this.frame.width - 1)) * CHANNELS;
          this.predictPixel(source, target, lower * this.size + localColumn, upper * this.size + localColumn, weight);
          continue;
        }
        final int color = this.pixelColor(localRow, localColumn);
        this.output[target] = (byte) (color >> RED_SHIFT);
        this.output[target + GREEN_OFFSET] = (byte) (color >> GREEN_SHIFT);
        this.output[target + BLUE_OFFSET] = (byte) color;
      }
    }

    private boolean isPredicted() {
      return this.mode == MODE_MOTION || this.mode == MODE_COMPACT || (this.mode == MODE_SKIP && !this.frame.keyframe);
    }

    private void predictPixel(final int source, final int target, final int lowerRow, final int upperRow, final int weight) {
      if (this.mode != MODE_COMPACT) {
        System.arraycopy(this.reference, source, this.output, target, CHANNELS);
        return;
      }

      final int luma = this.rows[lowerRow] * (HALF_PIXEL_SCALE * this.size - weight) + this.rows[upperRow] * weight;
      this.output[target] = (byte) roundToChannel((this.reference[source] & BYTE_MASK) * this.scale + (luma << this.quantizer), this.shift);
      this.output[target + GREEN_OFFSET] = (byte) roundToChannel(
        (this.reference[source + GREEN_OFFSET] & BYTE_MASK) * this.scale + (luma << this.quantizer),
        this.shift
      );
      this.output[target + BLUE_OFFSET] = (byte) roundToChannel(
        (this.reference[source + BLUE_OFFSET] & BYTE_MASK) * this.scale + (luma << this.quantizer),
        this.shift
      );
    }

    private int pixelColor(final int localRow, final int localColumn) {
      final byte[] data = this.frame.data;
      if (this.mode == MODE_PALETTE) {
        final int pixel = localRow * this.size + localColumn;
        return ((data[this.offset + RECORD_COLORS * CHANNELS + pixel / Byte.SIZE] >> (pixel % Byte.SIZE)) & 1) == 0
          ? this.color0
          : this.color1;
      }

      if (this.mode == MODE_PATTERN) {
        final int axis = this.orientation == 0 ? localColumn : localRow;
        return ((data[this.word + axis / Byte.SIZE] >> (axis % Byte.SIZE)) & 1) == 0 ? this.color0 : this.color1;
      }

      return this.color0;
    }
  }
}
