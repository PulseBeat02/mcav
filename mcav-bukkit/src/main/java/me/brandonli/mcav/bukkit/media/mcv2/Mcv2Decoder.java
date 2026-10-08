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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Validates and decodes MCV2 version 3. Parsed frames own their bytes and are immutable. */
public final class Mcv2Decoder {

  public static final int HEADER_BYTES = 32;
  public static final int MAX_FRAME_BYTES = 131071;
  public static final int MAX_DIMENSION = 4096;
  public static final int MAGIC = 0x3256434D;
  public static final int VERSION = 3;
  public static final int ROOT_SIZE = 32;
  public static final int SMALLEST_BLOCK = 8;
  public static final int BLOCK_SIZES = 3;
  public static final int CHANNELS = 3;
  public static final int MAX_CHANNEL = 255;
  public static final int QUARTERS = 4;
  public static final int GROUP_ROOTS = 32;
  public static final int CHECKPOINT_GROUPS = 8;
  public static final int WALK_SPAN = 8;
  public static final int KEYFRAME = 1;
  public static final int MODE_SKIP = 0;
  public static final int MODE_MOTION = 1;
  public static final int MODE_SOLID = 2;
  public static final int MODE_PALETTE = 3;
  public static final int MODE_PATTERN = 4;
  public static final int MODE_COMPACT = 5;
  public static final int MODE_SPLIT = 6;
  public static final int MODE_MASK = 31;
  public static final int QUANTIZER_SHIFT = 5;
  public static final int MAX_QUANTIZER = 7;
  public static final int COMPACT_DC = 0;
  public static final int COMPACT_GRID = 1;
  public static final int COMPACT_GRID_Y = 2;
  public static final int DIMENSIONS_OFFSET = 8;
  public static final int FRAME_ID_OFFSET = 12;
  public static final int REFERENCE_ID_OFFSET = 16;
  public static final int PAYLOAD_START_OFFSET = 20;
  public static final int TOTAL_OFFSET = 24;
  public static final int DEFAULT_COLOR_OFFSET = 28;
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

  /** A validated leaf, including its position outside the visible picture. */
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
    private final int defaultColor;
    private final int[] leaves;
    private final int endpointCount;
    private final int endpointsAt;
    private final int[] selectorCounts;
    private final int[] selectorsAt;

    private Frame(final Parser parser, final int[] leaves) {
      this.data = parser.data;
      this.width = parser.width;
      this.height = parser.height;
      this.frameId = u32(this.data, FRAME_ID_OFFSET);
      this.referenceId = u32(this.data, REFERENCE_ID_OFFSET);
      this.keyframe = parser.keyframe;
      this.payloadStart = parser.start;
      this.defaultColor = rgb(this.data, DEFAULT_COLOR_OFFSET);
      this.leaves = leaves;
      this.endpointCount = parser.endpointCount;
      this.endpointsAt = parser.endpointsAt;
      this.selectorCounts = parser.selectorCounts;
      this.selectorsAt = parser.selectorsAt;
    }

    public byte[] getData() {
      return this.data.clone();
    }

    public int getWidth() {
      return this.width;
    }

    public int getHeight() {
      return this.height;
    }

    public long getFrameId() {
      return this.frameId;
    }

    public long getReferenceId() {
      return this.referenceId;
    }

    public boolean isKeyframe() {
      return this.keyframe;
    }

    public int getPayloadStart() {
      return this.payloadStart;
    }

    public int getDefaultColor() {
      return this.defaultColor;
    }

    public int getLeafCount() {
      return this.leaves.length / LEAF_INTS;
    }

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

    public byte[] getEndpointTable() {
      return Arrays.copyOfRange(this.data, this.endpointsAt, this.data.length);
    }

    public byte[] getSelectorTable(final int size) {
      Preconditions.checkArgument(isBlockSize(size), "Invalid leaf size %s", size);
      final int index = sizeIndex(size);
      final int start = this.selectorsAt[index];
      return Arrays.copyOfRange(this.data, start, start + this.selectorCounts[index] * (1 + size / Byte.SIZE));
    }
  }

  /** Parses an owned copy, rejecting all versions other than version 3. */
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
    if (data[4] != VERSION) {
      throw new Mcv2Exception("Not an MCV2 version 3 frame");
    }
    if ((data[5] & ~KEYFRAME) != 0 || data[6] != 0 || data[7] != 0 || data[31] != 0 || u32(data, TOTAL_OFFSET) != data.length) {
      throw new Mcv2Exception("Invalid frame header");
    }
    final int width = u16(data, DIMENSIONS_OFFSET);
    final int height = u16(data, DIMENSIONS_OFFSET + Short.BYTES);
    if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION) {
      throw new Mcv2Exception("Invalid dimensions");
    }
    final boolean keyframe = (data[5] & KEYFRAME) != 0;
    if (keyframe != (u32(data, FRAME_ID_OFFSET) == u32(data, REFERENCE_ID_OFFSET)) || (!keyframe && rgb(data, DEFAULT_COLOR_OFFSET) != 0)) {
      throw new Mcv2Exception("Invalid reference metadata or default color");
    }
    final long start = u32(data, PAYLOAD_START_OFFSET);
    if (start < HEADER_BYTES || start > data.length) {
      throw new Mcv2Exception("Invalid payload start");
    }
    return new Parser(data, width, height, keyframe, (int) start).parse();
  }

  private static final class Parser {

    private final byte[] data;
    private final int width;
    private final int height;
    private final boolean keyframe;
    private final int start;
    private final int columns;
    private final int roots;
    private int endpointCount;
    private int endpointsAt;
    private final int[] selectorCounts = new int[BLOCK_SIZES];
    private final int[] selectorsAt = new int[BLOCK_SIZES];

    private Parser(final byte[] data, final int width, final int height, final boolean keyframe, final int start) {
      this.data = data;
      this.width = width;
      this.height = height;
      this.keyframe = keyframe;
      this.start = start;
      this.columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
      this.roots = this.columns * ((height + ROOT_SIZE - 1) / ROOT_SIZE);
    }

    private Frame parse() throws Mcv2Exception {
      final int groups = (this.roots + GROUP_ROOTS - 1) / GROUP_ROOTS;
      final int checkpoints = (groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS;
      final int countsAt = HEADER_BYTES + (groups + checkpoints) * Integer.BYTES;
      final int descriptorsAt = countsAt + BLOCK_SIZES * Integer.BYTES;
      if (descriptorsAt + Integer.BYTES > this.start) {
        throw new Mcv2Exception("Truncated index");
      }
      // Unsigned counts stay wide until their sum and the whole index fit inside the input.
      final long levelZero = u32(this.data, countsAt);
      final long levelOne = u32(this.data, countsAt + Integer.BYTES);
      final long levelTwo = u32(this.data, countsAt + 2 * Integer.BYTES);
      final long count = levelZero + levelOne + levelTwo;
      if (descriptorsAt + count + ((count + WALK_SPAN - 1) / WALK_SPAN) * Integer.BYTES + Integer.BYTES != this.start) {
        throw new Mcv2Exception("Invalid index length");
      }
      final int descriptors = (int) count;
      final int firstChildren = (int) levelZero;
      final int lastLevel = (int) (levelZero + levelOne);
      final int walksAt = descriptorsAt + descriptors;
      final int tablesAt = this.start - Integer.BYTES;
      this.readTables(tablesAt);
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
      final int payloadEnd = this.selectorsAt[0];
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
        throw new Mcv2Exception("Records do not end at the tables");
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

    private void readTables(final int countsAt) throws Mcv2Exception {
      this.endpointCount = this.data[countsAt] & 0xFF;
      this.endpointsAt = this.data.length - this.endpointCount * Integer.BYTES;
      int tableBytes = 0;
      for (int index = 0; index < BLOCK_SIZES; index++) {
        this.selectorCounts[index] = this.data[countsAt + 1 + index] & 0xFF;
        tableBytes += this.selectorCounts[index] * (1 + (SMALLEST_BLOCK << index) / Byte.SIZE);
      }
      int at = this.endpointsAt - tableBytes;
      if (at < this.start) {
        throw new Mcv2Exception("Truncated tables");
      }
      distinct(this.data, this.endpointsAt, this.endpointCount, Integer.BYTES, false);
      for (int index = 0; index < BLOCK_SIZES; index++) {
        final int entry = 1 + (SMALLEST_BLOCK << index) / Byte.SIZE;
        this.selectorsAt[index] = at;
        distinct(this.data, at, this.selectorCounts[index], entry, true);
        at += this.selectorCounts[index] * entry;
      }
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
        length = patternSize(size, this.endpointCount > 0, this.selectorCounts[sizeIndex(size)] > 0);
      } else {
        length = recordSize(mode, size);
      }
      if (length > end - offset) {
        throw new Mcv2Exception("Truncated record");
      }
      if (mode == MODE_PATTERN) {
        final boolean tabledEndpoints = this.endpointCount > 0;
        if (tabledEndpoints && (this.data[offset] & 0xFF) >= this.endpointCount) {
          throw new Mcv2Exception("Endpoint index outside table");
        }
        final int word = offset + (tabledEndpoints ? 1 : 2 * CHANNELS);
        final int words = this.selectorCounts[sizeIndex(size)];
        if (words > 0 ? (this.data[word] & 0xFF) >= words : (this.data[word] & 0xFF) > 1) {
          throw new Mcv2Exception("Invalid pattern selector");
        }
      }
      return length;
    }
  }

  private static void distinct(final byte[] data, final int start, final int count, final int entry, final boolean selectors)
    throws Mcv2Exception {
    final Set<Long> seen = new HashSet<>();
    for (int index = 0; index < count; index++) {
      final int at = start + index * entry;
      if (selectors && (data[at] & 0xFF) > 1) {
        throw new Mcv2Exception("Invalid table orientation");
      }
      long word = 0;
      for (int offset = 0; offset < entry; offset++) {
        word |= (data[at + offset] & 0xFFL) << (offset * Byte.SIZE);
      }
      if (!seen.add(word)) {
        throw new Mcv2Exception("Duplicate table entry");
      }
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

  // Temporary adapters keep callers compiling while the encoder is migrated in the following commit.
  public static byte[] decode(final Mcv2Frame frame, final byte @Nullable [] reference, final long referenceId) throws Mcv2Exception {
    return Legacy.decode(frame, reference, referenceId);
  }

  public static byte[] decode(final Mcv2Frame frame, final byte @Nullable [] reference, final long referenceId, final Workers workers)
    throws Mcv2Exception {
    return Legacy.decode(frame, reference, referenceId, workers);
  }

  public static byte[] decode(
    final Mcv2Frame frame,
    final byte @Nullable [] reference,
    final long referenceId,
    final Workers workers,
    final byte @Nullable [] output
  ) throws Mcv2Exception {
    return Legacy.decode(frame, reference, referenceId, workers, output);
  }

  public static byte[] decode(final byte[] bytes, final byte @Nullable [] reference, final long referenceId) throws Mcv2Exception {
    return decode(parse(bytes), reference, referenceId);
  }

  public static byte[] decode(final Frame frame, final byte @Nullable [] reference, final long referenceId) throws Mcv2Exception {
    final byte[] output = new byte[frame.width * frame.height * CHANNELS];
    decode(frame, reference, referenceId, output);
    return output;
  }

  /** Decodes into exactly one picture. Aliasing the reference is supported through an owned snapshot. */
  public static void decode(final Frame frame, final byte @Nullable [] reference, final long referenceId, final byte[] output)
    throws Mcv2Exception {
    final byte[] prediction = reference(frame, reference, referenceId);
    decodeRows(frame, samePicture(prediction, output) ? prediction.clone() : prediction, referenceId, output, 0, frame.height);
  }

  /** Decodes a disjoint row range into a caller's array; the reference must remain stable and must not alias output. */
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
      int color0 = this.frame.defaultColor;
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
        final boolean tabled = this.frame.endpointCount > 0;
        if (tabled) {
          final int pair = this.frame.endpointsAt + (data[offset] & 0xFF) * Integer.BYTES;
          color0 = unpack565(data[pair], data[pair + 1]);
          color1 = unpack565(data[pair + 2], data[pair + 3]);
        } else {
          color0 = rgb(data, offset);
          color1 = rgb(data, offset + CHANNELS);
        }
        word = offset + (tabled ? 1 : 2 * CHANNELS);
        final int index = sizeIndex(size);
        if (this.frame.selectorCounts[index] > 0) {
          word = this.frame.selectorsAt[index] + (data[word] & 0xFF) * (1 + size / Byte.SIZE);
        }
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

  public static int u16(final byte[] data, final int at) {
    return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
  }

  public static long u32(final byte[] data, final int at) {
    return (data[at] & 0xFFL) | ((data[at + 1] & 0xFFL) << 8) | ((data[at + 2] & 0xFFL) << 16) | ((data[at + 3] & 0xFFL) << 24);
  }

  public static void putU16(final byte[] data, final int at, final int value) {
    data[at] = (byte) value;
    data[at + 1] = (byte) (value >>> 8);
  }

  public static void putU32(final byte[] data, final int at, final long value) {
    for (int index = 0; index < Integer.BYTES; index++) {
      data[at + index] = (byte) (value >>> (index * Byte.SIZE));
    }
  }

  public static boolean follows(final long id, final long last) {
    final long distance = (id - last) & MAX_U32;
    return distance != 0 && distance < 1L << 31;
  }

  public static boolean isBlockSize(final int size) {
    return size == SMALLEST_BLOCK || size == 2 * SMALLEST_BLOCK || size == ROOT_SIZE;
  }

  public static int sizeIndex(final int size) {
    return Integer.numberOfTrailingZeros(size) - 3;
  }

  public static int signed(final int value, final int bits) {
    return (value << (Integer.SIZE - bits)) >> (Integer.SIZE - bits);
  }

  public static int patternSize(final int size, final boolean endpoints, final boolean selectors) {
    return (endpoints ? 1 : 2 * CHANNELS) + (selectors ? 1 : 1 + size / Byte.SIZE);
  }

  public static int recordSize(final int mode, final int size) {
    return switch (mode) {
      case MODE_SKIP, MODE_SPLIT -> 0;
      case MODE_MOTION -> 2;
      case MODE_SOLID -> CHANNELS;
      case MODE_PALETTE -> 2 * CHANNELS + (size * size) / Byte.SIZE;
      default -> throw new IllegalArgumentException("Variable or invalid record mode");
    };
  }

  public static int compactX(final byte[] data, final int offset) {
    final int form = (data[offset] & 0xFF) >> 4;
    return form == 1 ? signed(data[offset + 1] & 15, 4) : form == 2 ? data[offset + 1] : 0;
  }

  public static int compactY(final byte[] data, final int offset) {
    final int form = (data[offset] & 0xFF) >> 4;
    return form == 1 ? signed((data[offset + 1] & 0xFF) >> 4, 4) : form == 2 ? data[offset + 2] : 0;
  }

  public static int pack565(final int red, final int green, final int blue) {
    return ((red >> 3) << 11) | ((green >> 2) << 5) | (blue >> 3);
  }

  public static int unpack565(final int low, final int high) {
    final int value = (low & 0xFF) | ((high & 0xFF) << 8);
    final int red = value >> 11;
    final int green = (value >> 5) & 63;
    final int blue = value & 31;
    return (((red << 3) | (red >> 2)) << 16) | (((green << 2) | (green >> 4)) << 8) | (blue << 3) | (blue >> 2);
  }

  private static final class Legacy {

    /** Leaves decoded by one worker at a time. */
    private static final int GROUP = 256;

    private Legacy() {
      throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Decodes a validated frame on the calling thread.
     *
     * @param frame       the frame
     * @param reference   the previous decoded picture, required for a P frame and ignored for a keyframe
     * @param referenceId the id of that picture
     * @return the decoded picture, {@code width * height * 3} bytes
     * @throws Mcv2Exception if the frame is a P frame and the reference is missing, has the wrong size, or has another id
     * @throws NullPointerException if frame is null
     */
    private static byte[] decode(final Mcv2Frame frame, final byte @Nullable [] reference, final long referenceId) throws Mcv2Exception {
      return decode(frame, reference, referenceId, Workers.SEQUENTIAL);
    }

    /**
     * Decodes a validated frame. Every leaf covers its own pixels, so groups of leaves are decoded on the workers, each
     * with its own scratch space; the picture is the same for any number of workers.
     *
     * @param frame       the frame
     * @param reference   the previous decoded picture, required for a P frame and ignored for a keyframe
     * @param referenceId the id of that picture
     * @param workers     the workers
     * @return the decoded picture, {@code width * height * 3} bytes
     * @throws Mcv2Exception if the frame is a P frame and the reference is missing, has the wrong size, or has another id
     * @throws NullPointerException if frame or workers is null
     */
    private static byte[] decode(final Mcv2Frame frame, final byte @Nullable [] reference, final long referenceId, final Workers workers)
      throws Mcv2Exception {
      return decode(frame, reference, referenceId, workers, null);
    }

    /**
     * Decodes a validated frame into a picture the caller may reuse from frame to frame, when it has the frame's size;
     * a valid frame's leaves cover every pixel, so nothing of the picture before is left. The picture may be the
     * reference itself, which a P frame then reads from a copy.
     *
     * @param frame       the frame
     * @param reference   the previous decoded picture, required for a P frame and ignored for a keyframe
     * @param referenceId the id of that picture
     * @param workers     the workers
     * @param into        the picture to decode into, or null or one of another size for a new one
     * @return the decoded picture: {@code into} when it had the size, else a new one of {@code width * height * 3} bytes
     * @throws Mcv2Exception if the frame is a P frame and the reference is missing, has the wrong size, or has another id
     * @throws NullPointerException if {@code frame} or {@code workers} is null
     */
    private static byte[] decode(
      final Mcv2Frame frame,
      final byte @Nullable [] reference,
      final long referenceId,
      final Workers workers,
      final byte @Nullable [] into
    ) throws Mcv2Exception {
      Preconditions.checkNotNull(frame, "Frame must not be null");
      Preconditions.checkNotNull(workers, "Workers must not be null");
      final int width = frame.getWidth();
      final int height = frame.getHeight();
      final byte[] referencePicture;
      if (frame.isKeyframe()) {
        referencePicture = new byte[0];
      } else {
        if (reference == null || referenceId != frame.getReferenceId() || reference.length != width * height * Mcv2Format.CHANNELS) {
          throw new Mcv2Exception("Reference frame mismatch");
        }
        // a caller may decode into the picture it passes as the reference, but the leaves read the reference's pixels
        // after other leaves wrote theirs, so the reference is read from a copy then
        referencePicture = isSamePicture(reference, into) ? reference.clone() : reference;
      }
      final byte[] output =
        into != null && into.length == width * height * Mcv2Format.CHANNELS ? into : new byte[width * height * Mcv2Format.CHANNELS];
      final int[] leaves = frame.leafArray();
      final int count = leaves.length / Mcv2Frame.LEAF_INTS;
      final int groups = (count + GROUP - 1) / GROUP;
      final AtomicReferenceArray<@Nullable Mcv2Exception> failures = new AtomicReferenceArray<>(groups);
      workers.forEach(
        groups,
        () -> new LegacyContext(frame, referencePicture, output),
        (context, group) -> {
          final int end = Math.min(count, (group + 1) * GROUP) * Mcv2Frame.LEAF_INTS;
          try {
            for (int leafOffset = group * GROUP * Mcv2Frame.LEAF_INTS; leafOffset < end; leafOffset += Mcv2Frame.LEAF_INTS) {
              context.leaf(
                leaves[leafOffset + Mcv2Frame.LEAF_X],
                leaves[leafOffset + Mcv2Frame.LEAF_Y],
                leaves[leafOffset + Mcv2Frame.LEAF_SIZE],
                leaves[leafOffset + Mcv2Frame.LEAF_MODE],
                leaves[leafOffset + Mcv2Frame.LEAF_Q],
                leaves[leafOffset + Mcv2Frame.LEAF_OFFSET]
              );
            }
          } catch (final Mcv2Exception exception) {
            failures.set(group, exception);
          }
        }
      );
      // the failure a sequential decode would meet first
      for (int group = 0; group < groups; group++) {
        final Mcv2Exception failure = failures.get(group);
        if (failure != null) {
          throw failure;
        }
      }
      return output;
    }

    /** The state of one decode: the frame, the reference and the output picture. */
    private static final class LegacyContext {

      private final Mcv2Frame frame;

      private final byte[] data;

      private final byte[] reference;

      private final byte[] output;

      private final int width;

      private final int height;

      private final Reconstruction.Scratch scratch = new Reconstruction.Scratch();

      private final int[] prediction = new int[Mcv2Format.ROOT_SIZE * Mcv2Format.ROOT_SIZE * Mcv2Format.CHANNELS];

      private final int[] block = new int[Mcv2Format.ROOT_SIZE * Mcv2Format.ROOT_SIZE * Mcv2Format.CHANNELS];

      private LegacyContext(final Mcv2Frame frame, final byte[] reference, final byte[] output) {
        this.frame = frame;
        this.data = frame.data();
        this.reference = reference;
        this.output = output;
        this.width = frame.getWidth();
        this.height = frame.getHeight();
      }

      private void predict(final int blockLeft, final int blockTop, final int size, final int motionX, final int motionY) {
        Reconstruction.predict(this.reference, this.width, this.height, blockLeft, blockTop, size, motionX, motionY, this.prediction);
      }

      void leaf(final int left, final int top, final int size, final int mode, final int quantizer, final int offset) throws Mcv2Exception {
        if (left >= this.width || top >= this.height) {
          return;
        }
        final int globalX = this.frame.getGlobalX();
        final int globalY = this.frame.getGlobalY();
        final byte[] data = this.data;
        final int[] out = this.block;
        switch (mode) {
          case Mcv2Format.MODE_SKIP -> {
            if (this.frame.isKeyframe()) {
              Reconstruction.solid(this.frame.getDefaultColor(), size, out);
            } else {
              this.predict(left, top, size, globalX, globalY);
              Reconstruction.predicted(this.prediction, size, out);
            }
          }
          case Mcv2Format.MODE_MOTION -> {
            this.predict(left, top, size, globalX + data[offset], globalY + data[offset + 1]);
            Reconstruction.predicted(this.prediction, size, out);
          }
          case Mcv2Format.MODE_IMMEDIATE_MOTION -> {
            this.predict(left, top, size, globalX + (byte) offset, globalY + (byte) (offset >> Byte.SIZE));
            Reconstruction.predicted(this.prediction, size, out);
          }
          case Mcv2Format.MODE_SOLID -> Reconstruction.solid(
            ((data[offset] & 0xFF) << 16) | ((data[offset + 1] & 0xFF) << 8) | (data[offset + 2] & 0xFF),
            size,
            out
          );
          case Mcv2Format.MODE_PALETTE -> Reconstruction.palette(data, offset, size, out);
          case Mcv2Format.MODE_PATTERN -> Reconstruction.pattern(
            PatternRecord.expand(data, offset, size, this.frame.endpointTable(), this.frame.selectorTable(size)),
            size,
            out
          );
          case Mcv2Format.MODE_COMPACT -> {
            final CompactRecord record = CompactRecord.parse(data, offset, quantizer);
            this.predict(left, top, size, globalX + record.dx(), globalY + record.dy());
            Reconstruction.compact(this.prediction, data, record.bodyOffset(), record.kind(), quantizer, size, this.scratch, out);
          }
          default -> {
            if (mode >= Mcv2Format.MODE_INTRA_Y4C1) {
              final boolean residual = Mcv2Format.isResidual(mode);
              if (residual) {
                this.predict(left, top, size, globalX + data[offset], globalY + data[offset + 1]);
              }
              Reconstruction.reduced(
                residual ? this.prediction : null,
                data,
                offset + (residual ? 2 : 0),
                Mcv2Format.lumaGrid(mode),
                Mcv2Format.chromaGrid(mode),
                quantizer,
                size,
                this.scratch,
                out
              );
            } else if (mode >= Mcv2Format.MODE_RESIDUAL) {
              this.predict(left, top, size, globalX + data[offset], globalY + data[offset + 1]);
              Reconstruction.residualGrid(
                this.prediction,
                data,
                offset + 2,
                1 << (mode - Mcv2Format.MODE_RESIDUAL),
                quantizer,
                size,
                this.scratch,
                out
              );
            } else {
              Reconstruction.intraGrid(data, offset, 1 << (mode - Mcv2Format.MODE_INTRA), size, this.scratch, out);
            }
          }
        }
        final int right = Math.min(left + size, this.width);
        final int bottom = Math.min(top + size, this.height);
        for (int row = top; row < bottom; row++) {
          for (int column = left; column < right; column++) {
            final int from = ((row - top) * size + (column - left)) * Mcv2Format.CHANNELS;
            final int to = (row * this.width + column) * Mcv2Format.CHANNELS;
            this.output[to] = (byte) out[from];
            this.output[to + 1] = (byte) out[from + 1];
            this.output[to + 2] = (byte) out[from + 2];
          }
        }
      }
    }

    /** Whether the picture decoded into is the reference itself: identity is the point, as only then is a copy needed. */
    @SuppressWarnings("ReferenceEquality")
    private static boolean isSamePicture(final byte[] reference, final byte @Nullable [] into) {
      return reference == into;
    }
  }
}
