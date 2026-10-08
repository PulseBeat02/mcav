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

/** Validates and decodes MCV2 version 3. Parsed frames own their bytes and are immutable. */
public final class Mcv2Decoder {

  /** Fixed frame header size in bytes. */
  public static final int HEADER_BYTES = 20;
  /** Largest accepted frame length in bytes. */
  public static final int MAX_FRAME_BYTES = 131071;
  /** Largest width or height in pixels. */
  public static final int MAX_DIMENSION = 4096;
  /** Little-endian MCV2 magic word. */
  public static final int MAGIC = 0x3256434D;
  /** Accepted format version. */
  public static final int VERSION = 3;
  /** Superblock side in pixels. */
  public static final int ROOT_SIZE = 32;
  /** Smallest leaf side in pixels. */
  public static final int SMALLEST_BLOCK = 8;
  /** Number of tree levels. */
  public static final int BLOCK_SIZES = 3;
  /** RGB channels per pixel. */
  public static final int CHANNELS = 3;
  /** Largest channel value. */
  public static final int MAX_CHANNEL = 255;
  /** Children of a split node. */
  public static final int QUARTERS = 4;
  /** Superblocks per presence mask. */
  public static final int GROUP_ROOTS = 32;
  /** Presence masks per directory entry. */
  public static final int CHECKPOINT_GROUPS = 8;
  /** Descriptors per walk checkpoint. */
  public static final int WALK_SPAN = 8;
  /** Black keyframe leaf or co-located prediction leaf. */
  public static final int MODE_SKIP = 0;
  /** Whole-pixel translated prediction leaf. */
  public static final int MODE_MOTION = 1;
  /** Single-colour leaf. */
  public static final int MODE_SOLID = 2;
  /** Two-colour leaf with one selector per pixel. */
  public static final int MODE_PALETTE = 3;
  /** Two-colour leaf with selectors repeating along one axis. */
  public static final int MODE_PATTERN = 4;
  /** Motion prediction plus a compact residual. */
  public static final int MODE_COMPACT = 5;
  /** Four-child tree node. */
  public static final int MODE_SPLIT = 6;
  /** Descriptor bits holding the mode. */
  public static final int MODE_MASK = 31;
  /** First descriptor bit holding the quantizer. */
  public static final int QUANTIZER_SHIFT = 5;
  /** Largest wire quantizer exponent. */
  public static final int MAX_QUANTIZER = 7;
  /** Constant luma residual class. */
  public static final int COMPACT_DC = 0;
  /** Four-by-four luma grid with constant chroma. */
  public static final int COMPACT_GRID = 1;
  /** Four-by-four luma grid without chroma. */
  public static final int COMPACT_GRID_Y = 2;
  /** Header byte offset of width and height. */
  public static final int DIMENSIONS_OFFSET = 8;
  /** Header byte offset of the frame id. */
  public static final int FRAME_ID_OFFSET = 12;
  /** Header byte offset of the reference id. */
  public static final int REFERENCE_ID_OFFSET = 16;
  /** Largest unsigned 32-bit value. */
  public static final long MAX_U32 = 0xFFFFFFFFL;

  private static final int LEAF_INTS = 6;
  private static final int POSITION_INTS = 3;
  private static final int CURSOR_BITS = 17;
  private static final int GRID = 4;
  private static final int[] COMPACT_BYTES = { 1, 10, 8 };
  private static final byte[] NO_REFERENCE = new byte[0];

  private Mcv2Decoder() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * A validated leaf, including its position outside the visible picture.
   *
   * @param left horizontal origin in pixels
   * @param top vertical origin in pixels
   * @param size square side, 8, 16 or 32 pixels
   * @param mode leaf mode, SKIP through COMPACT
   * @param quantizer residual scale exponent, zero for other modes
   * @param offset record byte offset; an absent root has no record
   */
  public record Leaf(int left, int top, int size, int mode, int quantizer, int offset) {}

  /** A complete validated frame. Accessors copy mutable data. */
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
      this.frameId = u32(this.data, FRAME_ID_OFFSET);
      this.referenceId = u32(this.data, REFERENCE_ID_OFFSET);
      this.keyframe = parser.keyframe;
      this.payloadStart = parser.start;
      this.leaves = leaves;
    }

    /**
     * Returns an independent copy of the frame bytes.
     *
     * @return an independent copy of the frame bytes
     */
    public byte[] getData() {
      return this.data.clone();
    }

    /**
     * Returns picture width in pixels.
     *
     * @return picture width in pixels
     */
    public int getWidth() {
      return this.width;
    }

    /**
     * Returns picture height in pixels.
     *
     * @return picture height in pixels
     */
    public int getHeight() {
      return this.height;
    }

    /**
     * Returns unsigned 32-bit frame id.
     *
     * @return unsigned 32-bit frame id
     */
    public long getFrameId() {
      return this.frameId;
    }

    /**
     * Returns unsigned 32-bit reference id.
     *
     * @return unsigned 32-bit reference id
     */
    public long getReferenceId() {
      return this.referenceId;
    }

    /**
     * Returns whether the picture is independent of any reference.
     *
     * @return whether the picture is independent of any reference
     */
    public boolean isKeyframe() {
      return this.keyframe;
    }

    /**
     * Returns byte offset immediately after the index.
     *
     * @return byte offset immediately after the index
     */
    public int getPayloadStart() {
      return this.payloadStart;
    }

    /**
     * Returns number of leaves, including absent roots and off-picture leaves.
     *
     * @return number of leaves, including absent roots and off-picture leaves
     */
    public int getLeafCount() {
      return this.leaves.length / LEAF_INTS;
    }

    /**
     * Returns one leaf in root order, visiting split children in raster order.
     *
     * @param index zero-based leaf index
     * @return immutable leaf metadata
     * @throws IndexOutOfBoundsException if the index is outside the leaf array
     */
    public Leaf getLeaf(final int index) {
      Preconditions.checkElementIndex(index, this.getLeafCount(), "Leaf index");
      final int at = index * LEAF_INTS;
      return new Leaf(
        this.leaves[at],
        this.leaves[at + 1],
        this.leaves[at + 2],
        this.leaves[at + 3],
        this.leaves[at + 4],
        this.leaves[at + 5]
      );
    }
  }

  /**
   * Validates every header, tree, checkpoint and record before retaining an owned copy.
   *
   * @param bytes one complete frame
   * @return immutable validated frame
   * @throws NullPointerException if bytes is null
   * @throws Mcv2Exception if any version 3 validation rule fails, including unsupported older versions
   */
  public static Frame parse(final byte[] bytes) throws Mcv2Exception {
    Preconditions.checkNotNull(bytes, "Frame bytes must not be null");
    if (bytes.length >= Integer.BYTES && u32(bytes, 0) == 0x3156434D) {
      throw new Mcv2Exception("MCV1 version 1 is no longer supported; re-encode as MCV2 version 3");
    }
    if (bytes.length < Integer.BYTES || u32(bytes, 0) != MAGIC) {
      throw new Mcv2Exception("Not an MCV2 frame");
    }
    if (bytes.length > Integer.BYTES && bytes[4] == 2) {
      throw new Mcv2Exception("MCV2 version 2 is no longer supported; re-encode as version 3");
    }
    if (bytes.length < HEADER_BYTES || bytes.length > MAX_FRAME_BYTES) {
      throw new Mcv2Exception("Invalid frame length");
    }
    final byte[] data = bytes.clone();
    if (u32(data, 4) != VERSION) {
      throw new Mcv2Exception("Not an MCV2 version 3 frame");
    }
    final int width = u16(data, DIMENSIONS_OFFSET);
    final int height = u16(data, DIMENSIONS_OFFSET + Short.BYTES);
    if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION) {
      throw new Mcv2Exception("Invalid dimensions");
    }
    final boolean keyframe = u32(data, FRAME_ID_OFFSET) == u32(data, REFERENCE_ID_OFFSET);
    return new Parser(data, width, height, keyframe).parse();
  }

  private static final class Parser {

    private final byte[] data;
    private final int width;
    private final int height;
    private final boolean keyframe;
    private int start;
    private final int columns;
    private final int roots;

    private Parser(final byte[] data, final int width, final int height, final boolean keyframe) {
      this.data = data;
      this.width = width;
      this.height = height;
      this.keyframe = keyframe;
      this.columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
      this.roots = this.columns * ((height + ROOT_SIZE - 1) / ROOT_SIZE);
    }

    private Frame parse() throws Mcv2Exception {
      final int groups = (this.roots + GROUP_ROOTS - 1) / GROUP_ROOTS;
      final int checkpoints = (groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS;
      final int countsAt = HEADER_BYTES + (groups + checkpoints) * Integer.BYTES;
      final int descriptorsAt = countsAt + BLOCK_SIZES * Integer.BYTES;
      if (descriptorsAt > this.data.length) {
        throw new Mcv2Exception("Truncated index");
      }
      // Unsigned counts stay wide until their sum and the whole index fit inside the input.
      final long levelZero = u32(this.data, countsAt);
      final long levelOne = u32(this.data, countsAt + Integer.BYTES);
      final long levelTwo = u32(this.data, countsAt + 2 * Integer.BYTES);
      final long count = levelZero + levelOne + levelTwo;
      final long start = descriptorsAt + count + ((count + WALK_SPAN - 1) / WALK_SPAN) * Integer.BYTES;
      if (start > this.data.length) {
        throw new Mcv2Exception("Invalid index length");
      }
      this.start = (int) start;
      final int descriptors = (int) count;
      final int firstChildren = (int) levelZero;
      final int lastLevel = (int) (levelZero + levelOne);
      final int walksAt = descriptorsAt + descriptors;
      final int[] present = this.readDirectory(groups, firstChildren);
      int splits = 0;
      int firstSplits = 0;
      for (int index = 0; index < descriptors; index++) {
        final int descriptor = this.data[descriptorsAt + index] & 0xFF;
        final int mode = descriptor & MODE_MASK;
        final int quantizer = descriptor >> QUANTIZER_SHIFT;
        if (
          mode > MODE_SPLIT || (quantizer != 0 && mode != MODE_COMPACT) || (this.keyframe && (mode == MODE_MOTION || mode == MODE_COMPACT))
        ) {
          throw new Mcv2Exception("Invalid descriptor");
        }
        if (mode == MODE_SPLIT) {
          if (index >= lastLevel) {
            throw new Mcv2Exception("Split below the bounded depth");
          }
          splits++;
          if (index < firstChildren) {
            firstSplits++;
          }
        }
      }
      if ((long) QUARTERS * firstSplits != levelOne || (long) QUARTERS * (splits - firstSplits) != levelTwo) {
        throw new Mcv2Exception("Level counts disagree with splits");
      }
      final int[] positions = new int[descriptors * POSITION_INTS];
      for (int index = 0; index < firstChildren; index++) {
        position(positions, index, (present[index] % this.columns) * ROOT_SIZE, (present[index] / this.columns) * ROOT_SIZE, ROOT_SIZE);
      }
      int child = firstChildren;
      for (int index = 0; index < lastLevel; index++) {
        if ((this.data[descriptorsAt + index] & MODE_MASK) != MODE_SPLIT) {
          continue;
        }
        final int at = index * POSITION_INTS;
        final int half = positions[at + 2] / 2;
        for (int corner = 0; corner < QUARTERS; corner++) {
          position(positions, child++, positions[at] + (corner % 2) * half, positions[at + 1] + (corner / 2) * half, half);
        }
      }
      final int[] leaves = new int[(this.roots + descriptors - firstChildren - splits) * LEAF_INTS];
      int leaf = 0;
      int cursor = 0;
      splits = 0;
      final int payloadEnd = this.data.length;
      for (int index = 0; index < descriptors; index++) {
        if (
          index % WALK_SPAN == 0 &&
          u32(this.data, walksAt + (index / WALK_SPAN) * Integer.BYTES) != (cursor | ((long) splits << CURSOR_BITS))
        ) {
          throw new Mcv2Exception("Invalid walk checkpoint");
        }
        final int descriptor = this.data[descriptorsAt + index] & 0xFF;
        final int mode = descriptor & MODE_MASK;
        if (mode == MODE_SPLIT) {
          splits++;
          continue;
        }
        final int at = index * POSITION_INTS;
        final int offset = this.start + cursor;
        final int size = positions[at + 2];
        cursor += this.recordLength(mode, size, offset, payloadEnd);
        leaf(leaves, leaf++, positions[at], positions[at + 1], size, mode, descriptor >> QUANTIZER_SHIFT, offset);
      }
      if (this.start + cursor != payloadEnd) {
        throw new Mcv2Exception("Records do not end at the frame end");
      }
      int listed = 0;
      for (int root = 0; root < this.roots; root++) {
        if (listed < present.length && present[listed] == root) {
          listed++;
        } else {
          leaf(leaves, leaf++, (root % this.columns) * ROOT_SIZE, (root / this.columns) * ROOT_SIZE, ROOT_SIZE, MODE_SKIP, 0, 0);
        }
      }
      return new Frame(this, leaves);
    }

    private int[] readDirectory(final int groups, final int count) throws Mcv2Exception {
      final int[] present = new int[count];
      int seen = 0;
      for (int group = 0; group < groups; group++) {
        final long mask = u32(this.data, HEADER_BYTES + group * Integer.BYTES);
        final int valid = Math.min(GROUP_ROOTS, this.roots - group * GROUP_ROOTS);
        if (mask >>> valid != 0 || Long.bitCount(mask) > count - seen) {
          throw new Mcv2Exception("Invalid presence mask");
        }
        if (group % CHECKPOINT_GROUPS == 0 && u32(this.data, HEADER_BYTES + (groups + group / CHECKPOINT_GROUPS) * Integer.BYTES) != seen) {
          throw new Mcv2Exception("Invalid directory checkpoint");
        }
        for (int bit = 0; bit < valid; bit++) {
          if (((mask >>> bit) & 1) != 0) {
            present[seen++] = group * GROUP_ROOTS + bit;
          }
        }
      }
      if (seen != count) {
        throw new Mcv2Exception("Presence count differs from level zero");
      }
      return present;
    }

    private int recordLength(final int mode, final int size, final int offset, final int end) throws Mcv2Exception {
      final int length;
      if (mode == MODE_COMPACT) {
        if (offset >= end) {
          throw new Mcv2Exception("Truncated compact control");
        }
        final int control = this.data[offset] & 0xFF;
        final int kind = control & 15;
        final int form = control >> 4;
        if (kind > COMPACT_GRID_Y || form > 2) {
          throw new Mcv2Exception("Invalid compact control");
        }
        length = 1 + form + COMPACT_BYTES[kind];
      } else if (mode == MODE_PATTERN) {
        length = patternSize(size);
      } else {
        length = recordSize(mode, size);
      }
      if (length > end - offset) {
        throw new Mcv2Exception("Truncated record");
      }
      if (mode == MODE_PATTERN && (this.data[offset + 2 * CHANNELS] & 0xFF) > 1) {
        throw new Mcv2Exception("Invalid pattern selector");
      }
      return length;
    }
  }

  private static void position(final int[] positions, final int index, final int left, final int top, final int size) {
    final int at = index * POSITION_INTS;
    positions[at] = left;
    positions[at + 1] = top;
    positions[at + 2] = size;
  }

  private static void leaf(
    final int[] leaves,
    final int index,
    final int left,
    final int top,
    final int size,
    final int mode,
    final int quantizer,
    final int offset
  ) {
    final int at = index * LEAF_INTS;
    leaves[at] = left;
    leaves[at + 1] = top;
    leaves[at + 2] = size;
    leaves[at + 3] = mode;
    leaves[at + 4] = quantizer;
    leaves[at + 5] = offset;
  }

  /**
   * Parses and reconstructs a complete frame.
   *
   * @param bytes complete encoded frame
   * @param reference previous RGB24 picture; may be null for keyframes
   * @param referenceId id of that picture; ignored for keyframes
   * @return newly allocated RGB24 picture
   * @throws NullPointerException if bytes is null
   * @throws Mcv2Exception if syntax or reference validation fails
   */
  public static byte[] decode(final byte[] bytes, final byte @Nullable [] reference, final long referenceId) throws Mcv2Exception {
    return decode(parse(bytes), reference, referenceId);
  }

  /**
   * Reconstructs a validated frame into a new RGB24 array.
   *
   * @param frame validated frame
   * @param reference previous RGB24 picture; may be null for keyframes
   * @param referenceId id of that picture; ignored for keyframes
   * @return newly allocated RGB24 picture
   * @throws NullPointerException if frame is null
   * @throws Mcv2Exception if the required reference is missing or has the wrong id or byte length
   */
  public static byte[] decode(final Frame frame, final byte @Nullable [] reference, final long referenceId) throws Mcv2Exception {
    final byte[] output = new byte[frame.width * frame.height * CHANNELS];
    decode(frame, reference, referenceId, output);
    return output;
  }

  /**
   * Reconstructs a complete frame. An output alias of the reference uses an owned snapshot.
   *
   * @param frame validated frame
   * @param reference previous RGB24 picture; may be null for keyframes
   * @param referenceId id of that picture; ignored for keyframes
   * @param output array of exactly width * height * 3 bytes
   * @throws NullPointerException if frame or output is null
   * @throws IllegalArgumentException if output has the wrong length
   * @throws Mcv2Exception if the required reference is missing or has the wrong id or byte length
   */
  public static void decode(final Frame frame, final byte @Nullable [] reference, final long referenceId, final byte[] output)
    throws Mcv2Exception {
    final byte[] prediction = reference(frame, reference, referenceId);
    decodeRows(frame, samePicture(prediction, output) ? prediction.clone() : prediction, referenceId, output, 0, frame.height);
  }

  /**
   * Decodes a row range, leaving other rows unchanged. Disjoint ranges may run concurrently;
   * the reference must remain stable and must not alias output.
   *
   * @param frame validated frame
   * @param reference previous RGB24 picture; may be null for keyframes
   * @param referenceId id of that picture; ignored for keyframes
   * @param output array of exactly width * height * 3 bytes
   * @param fromRow first row, inclusive
   * @param toRow last row, exclusive
   * @throws NullPointerException if frame or output is null
   * @throws IllegalArgumentException if output size, row bounds or reference aliasing is invalid
   * @throws Mcv2Exception if the required reference is missing or has the wrong id or byte length
   */
  public static void decodeRows(
    final Frame frame,
    final byte @Nullable [] reference,
    final long referenceId,
    final byte[] output,
    final int fromRow,
    final int toRow
  ) throws Mcv2Exception {
    final byte[] prediction = reference(frame, reference, referenceId);
    Preconditions.checkArgument(output.length == frame.width * frame.height * CHANNELS, "Output size does not match the frame");
    Preconditions.checkArgument(fromRow >= 0 && fromRow <= toRow && toRow <= frame.height, "Invalid row range");
    Preconditions.checkArgument(!samePicture(prediction, output), "Row decoding needs a separate reference");
    final Context context = new Context(frame, prediction, output);
    for (int at = 0; at < frame.leaves.length; at += LEAF_INTS) {
      context.leaf(at, fromRow, toRow);
    }
  }

  private static byte[] reference(final Frame frame, final byte @Nullable [] reference, final long referenceId) throws Mcv2Exception {
    if (frame.keyframe) {
      return NO_REFERENCE;
    }
    if (reference == null || referenceId != frame.referenceId || reference.length != frame.width * frame.height * CHANNELS) {
      throw new Mcv2Exception("Reference frame mismatch");
    }
    return reference;
  }

  @SuppressWarnings("ReferenceEquality")
  private static boolean samePicture(final byte[] first, final byte[] second) {
    return first == second;
  }

  private static final class Context {

    private final Frame frame;
    private final byte[] reference;
    private final byte[] output;
    private final int[] nodes = new int[GRID * GRID];
    private final int[] rows = new int[GRID * ROOT_SIZE];

    private Context(final Frame frame, final byte[] reference, final byte[] output) {
      this.frame = frame;
      this.reference = reference;
      this.output = output;
    }

    private void leaf(final int at, final int fromRow, final int toRow) {
      final int[] leaves = this.frame.leaves;
      final int left = leaves[at];
      final int top = leaves[at + 1];
      final int size = leaves[at + 2];
      final int mode = leaves[at + 3];
      final int quantizer = leaves[at + 4];
      final int offset = leaves[at + 5];
      final int firstRow = Math.max(top, fromRow);
      final int lastRow = Math.min(top + size, toRow);
      final int right = Math.min(left + size, this.frame.width);
      if (firstRow >= lastRow || left >= right) {
        return;
      }
      final byte[] data = this.frame.data;
      int motionX = 0;
      int motionY = 0;
      int color0 = 0;
      int color1 = 0;
      int word = 0;
      int orientation = 0;
      int kind = COMPACT_DC;
      int body = 0;
      int orange = 0;
      int green = 0;
      if (mode == MODE_SOLID || mode == MODE_PALETTE) {
        color0 = rgb(data, offset);
        if (mode == MODE_PALETTE) {
          color1 = rgb(data, offset + CHANNELS);
        }
      } else if (mode == MODE_PATTERN) {
        color0 = rgb(data, offset);
        color1 = rgb(data, offset + CHANNELS);
        word = offset + 2 * CHANNELS;
        orientation = data[word];
        word++;
      } else if (mode == MODE_MOTION) {
        motionX = data[offset];
        motionY = data[offset + 1];
      } else if (mode == MODE_COMPACT) {
        kind = data[offset] & 15;
        final int form = (data[offset] & 0xFF) >> 4;
        motionX = compactX(data, offset);
        motionY = compactY(data, offset);
        body = offset + 1 + form;
        if (kind != COMPACT_DC) {
          for (int node = 0; node < GRID * GRID; node++) {
            this.nodes[node] = signed((data[body + node / 2] & 0xFF) >> ((node % 2) * 4), 4);
          }
          horizontal(this.nodes, size, this.rows);
          if (kind == COMPACT_GRID) {
            orange = data[body + 8];
            green = data[body + 9];
          }
        }
      }
      final int scale = 4 * size * size;
      final int shift = 2 * (Integer.numberOfTrailingZeros(size) + 1);
      for (int row = firstRow; row < lastRow; row++) {
        final int localRow = row - top;
        final int position = Math.min(Math.max((2 * localRow + 1) * GRID - size, 0), (GRID - 1) * 2 * size);
        final int lower = position / (2 * size);
        final int upper = Math.min(lower + 1, GRID - 1);
        final int weight = position % (2 * size);
        final int sourceRow = Math.min(Math.max(row + motionY, 0), this.frame.height - 1) * this.frame.width;
        for (int column = left; column < right; column++) {
          final int localColumn = column - left;
          final int target = (row * this.frame.width + column) * CHANNELS;
          int color = color0;
          if (mode == MODE_PALETTE) {
            final int pixel = localRow * size + localColumn;
            color = ((data[offset + 2 * CHANNELS + pixel / Byte.SIZE] >> (pixel % Byte.SIZE)) & 1) == 0 ? color0 : color1;
          } else if (mode == MODE_PATTERN) {
            final int axis = orientation == 0 ? localColumn : localRow;
            color = ((data[word + axis / Byte.SIZE] >> (axis % Byte.SIZE)) & 1) == 0 ? color0 : color1;
          }
          if (mode == MODE_MOTION || mode == MODE_COMPACT || (mode == MODE_SKIP && !this.frame.keyframe)) {
            final int source = (sourceRow + Math.min(Math.max(column + motionX, 0), this.frame.width - 1)) * CHANNELS;
            int luma = 0;
            if (mode == MODE_COMPACT) {
              luma =
                kind == COMPACT_DC
                  ? data[body] * scale
                  : this.rows[lower * size + localColumn] * (2 * size - weight) + this.rows[upper * size + localColumn] * weight;
            }
            this.output[target] = (byte) round(
              (this.reference[source] & 0xFF) * scale + ((luma + (orange - green) * scale) << quantizer),
              shift
            );
            this.output[target + 1] = (byte) round(
              (this.reference[source + 1] & 0xFF) * scale + ((luma + green * scale) << quantizer),
              shift
            );
            this.output[target + 2] = (byte) round(
              (this.reference[source + 2] & 0xFF) * scale + ((luma - (orange + green) * scale) << quantizer),
              shift
            );
          } else {
            this.output[target] = (byte) (color >> 16);
            this.output[target + 1] = (byte) (color >> 8);
            this.output[target + 2] = (byte) color;
          }
        }
      }
    }
  }

  private static void horizontal(final int[] nodes, final int size, final int[] rows) {
    final int span = 2 * size;
    for (int row = 0; row < GRID; row++) {
      for (int column = 0; column < size; column++) {
        final int position = Math.min(Math.max((2 * column + 1) * GRID - size, 0), (GRID - 1) * span);
        final int lower = position / span;
        final int upper = Math.min(lower + 1, GRID - 1);
        final int weight = position % span;
        rows[row * size + column] = nodes[row * GRID + lower] * (span - weight) + nodes[row * GRID + upper] * weight;
      }
    }
  }

  private static int round(final int value, final int shift) {
    return Math.min(Math.max((value + (1 << (shift - 1))) >> shift, 0), MAX_CHANNEL);
  }

  private static int rgb(final byte[] data, final int at) {
    return ((data[at] & 0xFF) << 16) | ((data[at + 1] & 0xFF) << 8) | (data[at + 2] & 0xFF);
  }

  /**
   * Reads an unsigned little-endian 16-bit integer.
   *
   * @param data source bytes
   * @param at first byte offset
   * @return unsigned value
   * @throws IndexOutOfBoundsException if either byte lies outside data
   */
  public static int u16(final byte[] data, final int at) {
    return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
  }

  /**
   * Reads an unsigned little-endian 32-bit integer.
   *
   * @param data source bytes
   * @param at first byte offset
   * @return unsigned value
   * @throws IndexOutOfBoundsException if any byte lies outside data
   */
  public static long u32(final byte[] data, final int at) {
    return (data[at] & 0xFFL) | ((data[at + 1] & 0xFFL) << 8) | ((data[at + 2] & 0xFFL) << 16) | ((data[at + 3] & 0xFFL) << 24);
  }

  /**
   * Writes the low 16 bits in little-endian order.
   *
   * @param data destination bytes
   * @param at first byte offset
   * @param value value to write
   * @throws IndexOutOfBoundsException if either byte lies outside data
   */
  public static void putU16(final byte[] data, final int at, final int value) {
    data[at] = (byte) value;
    data[at + 1] = (byte) (value >>> 8);
  }

  /**
   * Writes the low 32 bits in little-endian order.
   *
   * @param data destination bytes
   * @param at first byte offset
   * @param value value to write
   * @throws IndexOutOfBoundsException if any byte lies outside data
   */
  public static void putU32(final byte[] data, final int at, final long value) {
    for (int index = 0; index < Integer.BYTES; index++) {
      data[at + index] = (byte) (value >>> (index * Byte.SIZE));
    }
  }

  /**
   * Compares frame ids using unsigned 32-bit wraparound.
   *
   * @param id candidate frame id
   * @param last last committed frame id
   * @return whether the forward distance is strictly between zero and 2^31
   */
  public static boolean follows(final long id, final long last) {
    final long distance = (id - last) & MAX_U32;
    return distance != 0 && distance < 1L << 31;
  }

  /**
   * Checks the supported square leaf sizes.
   *
   * @param size side in pixels
   * @return whether size is 8, 16 or 32
   */
  public static boolean isBlockSize(final int size) {
    return size == SMALLEST_BLOCK || size == 2 * SMALLEST_BLOCK || size == ROOT_SIZE;
  }

  /**
   * Maps a supported leaf size to its fitting-array index.
   *
   * @param size 8, 16 or 32 pixels
   * @return 0, 1 or 2 respectively
   */
  public static int sizeIndex(final int size) {
    return Integer.numberOfTrailingZeros(size) - 3;
  }

  /**
   * Sign-extends the low bits of an integer.
   *
   * @param value packed integer
   * @param bits signed field width, 1 through 32
   * @return sign-extended value
   */
  public static int signed(final int value, final int bits) {
    return (value << (Integer.SIZE - bits)) >> (Integer.SIZE - bits);
  }

  /**
   * Returns the full pattern record length.
   *
   * @param size 8, 16 or 32 pixels
   * @return record length in bytes
   */
  public static int patternSize(final int size) {
    return 2 * CHANNELS + 1 + size / Byte.SIZE;
  }

  /**
   * Returns a fixed-length record size.
   *
   * @param mode SKIP, MOTION, SOLID, PALETTE, PATTERN or SPLIT
   * @param size 8, 16 or 32 pixels
   * @return record length in bytes; zero for SKIP and SPLIT
   * @throws IllegalArgumentException if mode is COMPACT or invalid
   */
  public static int recordSize(final int mode, final int size) {
    return switch (mode) {
      case MODE_SKIP, MODE_SPLIT -> 0;
      case MODE_MOTION -> 2;
      case MODE_SOLID -> CHANNELS;
      case MODE_PALETTE -> 2 * CHANNELS + (size * size) / Byte.SIZE;
      case MODE_PATTERN -> patternSize(size);
      default -> throw new IllegalArgumentException("Variable or invalid record mode");
    };
  }

  /**
   * Reads the horizontal vector of a validated compact record.
   *
   * @param data validated frame bytes
   * @param offset record byte offset
   * @return signed whole-pixel displacement
   * @throws IndexOutOfBoundsException if the record lies outside data
   */
  public static int compactX(final byte[] data, final int offset) {
    final int form = (data[offset] & 0xFF) >> 4;
    return form == 1 ? signed(data[offset + 1] & 15, 4) : form == 2 ? data[offset + 1] : 0;
  }

  /**
   * Reads the vertical vector of a validated compact record.
   *
   * @param data validated frame bytes
   * @param offset record byte offset
   * @return signed whole-pixel displacement
   * @throws IndexOutOfBoundsException if the record lies outside data
   */
  public static int compactY(final byte[] data, final int offset) {
    final int form = (data[offset] & 0xFF) >> 4;
    return form == 1 ? signed((data[offset + 1] & 0xFF) >> 4, 4) : form == 2 ? data[offset + 2] : 0;
  }
}
