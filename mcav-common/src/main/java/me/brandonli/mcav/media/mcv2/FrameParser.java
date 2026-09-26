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
package me.brandonli.mcav.media.mcv2;

import static me.brandonli.mcav.media.mcv2.Mcv2Format.*;

import com.google.common.base.Preconditions;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Validates a complete MCV2 frame before anything may use it, exactly as the reference decoder's
 * {@code v2.parse_frame} does: every pointer, count, table, checkpoint and record is checked against the one canonical
 * layout, so a frame is accepted here if and only if the reference accepts it, with the documented exception of the
 * syntax {@link UnsupportedSyntaxException} names.
 *
 * <p>The parser reads only inside the bytes it is given, allocates at most in proportion to their length, and never
 * trusts a count or offset it has not checked. Whatever the input, it either returns a frame or throws
 * {@link Mcv2Exception}.
 */
public final class FrameParser {

  /** The largest frame a short index can address: its descriptors hold 16-bit offsets. */
  private static final int ROOT_TABLE_LIMIT = HALF_WORD;

  /** The address space an immediate motion record takes beside the frame when it is decoded. */
  private static final int IMMEDIATE_RECORD_BYTES = MOTION_BYTES;

  private static final int INITIAL_LEAVES = 64;

  /** A descriptor's block in the derived form's positions: its x, y and size. */
  private static final int POSITION_INTS = 3;

  private static final long WORD_MASK = 0xFFFFFFFFL;

  /** The bytes of a selector word of the table of block size {@code 8 << index}: a byte of the axis, then the bits. */
  private static int selectorEntry(final int index) {
    return 1 + (SMALLEST_BLOCK << index) / Byte.SIZE;
  }

  private FrameParser() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Parses and validates a frame. The bytes are copied, so the caller may reuse its array.
   *
   * @param bytes the frame
   * @return the validated frame
   * @throws UnsupportedSyntaxException if the bytes are an MCV1 frame or use the syntax of a reverted round
   * @throws Mcv2Exception             if the bytes are not a valid MCV2 frame
   */
  public static Mcv2Frame parse(final byte[] bytes) throws Mcv2Exception {
    Preconditions.checkNotNull(bytes, "Frame bytes must not be null");
    if (bytes.length >= WORD_BYTES && u32(bytes, 0) == MCV1_MAGIC) {
      throw new UnsupportedSyntaxException("MCV1 frames are not supported");
    }
    if (bytes.length < WORD_BYTES || u32(bytes, 0) != MAGIC) {
      throw new Mcv2Exception("Not an MCV2 frame");
    }
    if (bytes.length < HEADER_BYTES || bytes.length > MAX_FRAME_BYTES) {
      throw new Mcv2Exception("Invalid frame length");
    }
    final byte[] data = bytes.clone();
    final long config = u32(data, CONFIGURATION_OFFSET);
    final long dimensions = u32(data, DIMENSIONS_OFFSET);
    final long frameId = u32(data, FRAME_ID_OFFSET);
    final long referenceId = u32(data, REFERENCE_ID_OFFSET);
    final long motion = u32(data, MOTION_OFFSET);
    final long count = u32(data, ROOT_COUNT_OFFSET);
    final long start = u32(data, PAYLOAD_START_OFFSET);
    final long total = u32(data, TOTAL_OFFSET);
    final long color = u32(data, DEFAULT_COLOR_OFFSET);
    final int width = (int) (dimensions & HALF_WORD);
    final int height = (int) (dimensions >>> HALF_WORD_BITS);
    final int flags = (int) (config >>> HALF_WORD_BITS);
    final boolean reserved = u32(data, RESERVED_OFFSET) != 0 || u32(data, RESERVED_OFFSET + WORD_BYTES) != 0;
    if ((config & HALF_WORD) != CONFIGURATION || (flags & ~ALL_FLAGS) != 0 || reserved) {
      throw new Mcv2Exception("Unsupported MCV2 header");
    }
    if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION || total != data.length) {
      throw new Mcv2Exception("Invalid dimensions or total");
    }
    final int stride = (flags & SHORT_INDEX) != 0 ? SHORT_DESCRIPTOR_BYTES : WORD_BYTES;
    if (stride == SHORT_DESCRIPTOR_BYTES && total > ROOT_TABLE_LIMIT) {
      throw new Mcv2Exception("Short index frame exceeds the address range");
    }
    final int columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
    final int roots = columns * ((height + ROOT_SIZE - 1) / ROOT_SIZE);
    if (count != roots || start < HEADER_BYTES || start > total) {
      throw new Mcv2Exception("Invalid root table");
    }
    final boolean keyframe = (flags & KEYFRAME) != 0;
    if ((keyframe && (frameId != referenceId || motion != 0)) || (!keyframe && frameId == referenceId)) {
      throw new Mcv2Exception("Invalid reference metadata");
    }
    final boolean defaultSolid = (flags & DEFAULT_SOLID) != 0;
    if (color > OFFSET_MASK || (defaultSolid && !keyframe) || (!defaultSolid && color != 0)) {
      throw new Mcv2Exception("Invalid default color");
    }
    if ((flags & TWO_LEVEL_WALK) != 0 && (flags & DERIVED_OFFSETS) == 0) {
      throw new Mcv2Exception("Two-level walk requires derived offsets");
    }
    if ((flags & PACKED_SYMBOLS) != 0 && (flags & DERIVED_OFFSETS) == 0) {
      throw new Mcv2Exception("Packed symbols require derived offsets");
    }
    if ((flags & MOTION_TABLE) != 0) {
      throw new UnsupportedSyntaxException("The motion table of round 15 is not supported");
    }
    final int bgr = (int) color;
    // the header stores the colour's bytes as blue, green, red; the frame hands it on as RGB
    final int rgb = ((bgr & 0xFF) << 16) | (bgr & 0xFF00) | ((bgr >> 16) & 0xFF);
    final Mcv2Frame.Header header = new Mcv2Frame.Header(
      width,
      height,
      frameId,
      referenceId,
      flags,
      signed((int) (motion & HALF_WORD), HALF_WORD_BITS),
      signed((int) (motion >>> HALF_WORD_BITS), HALF_WORD_BITS),
      (int) start,
      rgb
    );
    if ((flags & DERIVED_OFFSETS) != 0) {
      if ((flags & SPARSE) == 0 || (flags & DERIVED_DIRECTORY) == 0 || (flags & SHORT_INDEX) != 0) {
        throw new Mcv2Exception("Derived offsets require the level-ordered sparse form");
      }
      return new DerivedParser(data, header, roots, columns).parse();
    }
    return new StoredParser(data, header, roots, columns, stride).parse();
  }

  /** A growable list of leaves, six integers each. */
  private static final class Leaves {

    private int[] values = new int[INITIAL_LEAVES * Mcv2Frame.LEAF_INTS];

    private int size;

    void add(final int x, final int y, final int size, final int mode, final int q, final int offset) {
      if (this.size + Mcv2Frame.LEAF_INTS > this.values.length) {
        this.values = Arrays.copyOf(this.values, this.values.length * 2);
      }
      final int[] v = this.values;
      final int at = this.size;
      v[at + Mcv2Frame.LEAF_X] = x;
      v[at + Mcv2Frame.LEAF_Y] = y;
      v[at + Mcv2Frame.LEAF_SIZE] = size;
      v[at + Mcv2Frame.LEAF_MODE] = mode;
      v[at + Mcv2Frame.LEAF_Q] = q;
      v[at + Mcv2Frame.LEAF_OFFSET] = offset;
      this.size += Mcv2Frame.LEAF_INTS;
    }

    int count() {
      return this.size / Mcv2Frame.LEAF_INTS;
    }

    int get(final int leaf, final int field) {
      return this.values[leaf * Mcv2Frame.LEAF_INTS + field];
    }

    int[] toArray() {
      return Arrays.copyOf(this.values, this.size);
    }
  }

  private static void checkUnsupportedLeaf(final int mode) throws UnsupportedSyntaxException {
    if (mode == MODE_COARSE_PALETTE_2 || mode == MODE_COARSE_PALETTE_4 || mode == MODE_INDEXED_MOTION) {
      throw new UnsupportedSyntaxException("Leaf mode " + mode + " of a reverted round is not supported");
    }
  }

  /** The stored index forms: wide or short descriptors with explicit offsets, depth-first child tables. */
  private static final class StoredParser {

    private final byte[] data;

    private final Mcv2Frame.Header header;

    private final int roots;

    private final int columns;

    private final int stride;

    private final int start;

    private final int flags;

    private final Leaves leaves = new Leaves();

    private int cursor;

    private StoredParser(final byte[] data, final Mcv2Frame.Header header, final int roots, final int columns, final int stride) {
      this.data = data;
      this.header = header;
      this.roots = roots;
      this.columns = columns;
      this.stride = stride;
      this.start = header.payloadStart();
      this.flags = header.flags();
    }

    private int word(final int offset) {
      if (this.stride == SHORT_DESCRIPTOR_BYTES) {
        // a short descriptor holds the offset in its first two bytes and the mode and quantizer byte in its third
        return u16(this.data, offset) | ((this.data[offset + 2] & 0xFF) << MODE_SHIFT);
      }
      return (int) u32(this.data, offset);
    }

    Mcv2Frame parse() throws Mcv2Exception {
      final int[] words = new int[this.roots];
      if ((this.flags & DERIVED_DIRECTORY) != 0 && (this.flags & SPARSE) == 0) {
        throw new Mcv2Exception("Derived directory requires the sparse root form");
      }
      if ((this.flags & SPARSE) != 0) {
        this.readSparseRoots(words);
      } else {
        this.cursor = HEADER_BYTES + this.roots * this.stride;
        if (this.cursor > this.start) {
          throw new Mcv2Exception("Short root index");
        }
        for (int i = 0; i < this.roots; i++) {
          words[i] = this.word(HEADER_BYTES + i * this.stride);
        }
      }
      for (int i = 0; i < this.roots; i++) {
        this.walk(words[i], (i % this.columns) * ROOT_SIZE, (i / this.columns) * ROOT_SIZE, ROOT_SIZE);
      }
      if (this.cursor != this.start) {
        throw new Mcv2Exception("Noncanonical node table");
      }
      this.checkPayload();
      return new Mcv2Frame(this.data, this.header, this.leaves.toArray(), null, null);
    }

    private void readSparseRoots(final int[] words) throws Mcv2Exception {
      final int groups = (this.roots + GROUP_ROOTS - 1) / GROUP_ROOTS;
      final boolean derived = (this.flags & DERIVED_DIRECTORY) != 0;
      final int checkpoints = (groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS;
      this.cursor = HEADER_BYTES + (derived ? groups * WORD_BYTES + checkpoints * WORD_BYTES : groups * STORED_GROUP_BYTES);
      if (this.cursor > this.start) {
        throw new Mcv2Exception("Short root directory");
      }
      int root = 0;
      for (int group = 0; group < groups; group++) {
        final long mask;
        long pointer;
        if (derived) {
          mask = u32(this.data, HEADER_BYTES + group * WORD_BYTES);
          pointer = this.cursor;
          if (group % CHECKPOINT_GROUPS == 0) {
            pointer = u32(this.data, HEADER_BYTES + groups * WORD_BYTES + (group / CHECKPOINT_GROUPS) * WORD_BYTES);
          }
        } else {
          mask = u32(this.data, HEADER_BYTES + group * STORED_GROUP_BYTES);
          pointer = u32(this.data, HEADER_BYTES + group * STORED_GROUP_BYTES + WORD_BYTES);
        }
        final int valid = Math.min(GROUP_ROOTS, this.roots - group * GROUP_ROOTS);
        if (pointer != this.cursor || mask >>> valid != 0 || this.cursor + (long) Long.bitCount(mask) * this.stride > this.start) {
          throw new Mcv2Exception("Bad root directory");
        }
        for (int bit = 0; bit < valid; bit++) {
          int value = 0;
          if (((mask >>> bit) & 1) != 0) {
            value = this.word(this.cursor);
            this.cursor += this.stride;
            if (value == 0) {
              throw new Mcv2Exception("Explicit sparse skip");
            }
          }
          words[root++] = value;
        }
      }
    }

    private void walk(final int word, final int x, final int y, final int size) throws Mcv2Exception {
      final int mode = (word >>> MODE_SHIFT) & MODE_MASK;
      final int q = word >>> QUANTIZER_SHIFT;
      final int offset = word & OFFSET_MASK;
      if (mode == MODE_SPLIT || mode == MODE_SPARSE_SPLIT) {
        if (size == SMALLEST_BLOCK || q != 0 || offset != this.cursor) {
          throw new Mcv2Exception("Invalid split address or depth");
        }
        final int[] children = new int[QUARTERS];
        if (mode == MODE_SPARSE_SPLIT) {
          // a sparse split lists at most three children: with all four it would be a plain split
          if (this.stride != SHORT_DESCRIPTOR_BYTES || this.cursor >= this.start || (this.data[this.cursor] & 0xFF) >= ALL_QUARTERS) {
            throw new Mcv2Exception("Invalid sparse child mask or layout");
          }
          final int mask = this.data[this.cursor] & 0xFF;
          this.cursor += 1;
          if (this.cursor + Integer.bitCount(mask) * this.stride > this.start) {
            throw new Mcv2Exception("Truncated sparse children");
          }
          for (int i = 0; i < QUARTERS; i++) {
            if (((mask >> i) & 1) != 0) {
              children[i] = this.word(this.cursor);
              if (children[i] == 0) {
                throw new Mcv2Exception("Explicit sparse child skip");
              }
              this.cursor += this.stride;
            }
          }
        } else {
          if (this.cursor + QUARTERS * this.stride > this.start) {
            throw new Mcv2Exception("Truncated children");
          }
          for (int i = 0; i < QUARTERS; i++) {
            children[i] = this.word(this.cursor + i * this.stride);
          }
          this.cursor += QUARTERS * this.stride;
        }
        final int half = size / 2;
        for (int i = 0; i < QUARTERS; i++) {
          this.walk(children[i], x + (i % 2) * half, y + (i / 2) * half, half);
        }
        return;
      }
      checkUnsupportedLeaf(mode);
      final boolean leafMode = mode <= MODE_PATTERN || mode == MODE_IMMEDIATE_MOTION;
      if (!leafMode || (q != 0 && !isResidual(mode) && mode != MODE_COMPACT) || (mode == MODE_SKIP && word != 0)) {
        throw new Mcv2Exception("Invalid leaf descriptor");
      }
      if (mode == MODE_IMMEDIATE_MOTION && offset >> HALF_WORD_BITS != 0) {
        throw new Mcv2Exception("Invalid leaf descriptor");
      }
      if ((this.flags & KEYFRAME) != 0 && isTemporalInKeyframe(mode, this.flags)) {
        throw new Mcv2Exception("Temporal keyframe leaf");
      }
      this.leaves.add(x, y, size, mode, q, offset);
    }

    private void checkPayload() throws Mcv2Exception {
      final int count = this.leaves.count();
      int immediates = 0;
      for (int i = 0; i < count; i++) {
        if (this.leaves.get(i, Mcv2Frame.LEAF_MODE) == MODE_IMMEDIATE_MOTION) {
          immediates++;
        }
      }
      // an immediate record is materialized beside the frame when decoding, so it is charged the address space a
      // stored record would have occupied
      if ((long) this.data.length + (long) IMMEDIATE_RECORD_BYTES * immediates > MAX_FRAME_BYTES) {
        throw new Mcv2Exception("Immediate records exceed the frame address range");
      }
      for (int i = 0; i < count; i++) {
        final int mode = this.leaves.get(i, Mcv2Frame.LEAF_MODE);
        if (mode == MODE_IMMEDIATE_MOTION) {
          continue;
        }
        final int size = this.leaves.get(i, Mcv2Frame.LEAF_SIZE);
        final int q = this.leaves.get(i, Mcv2Frame.LEAF_Q);
        final int offset = this.leaves.get(i, Mcv2Frame.LEAF_OFFSET);
        final int length;
        if (mode == MODE_PATTERN) {
          length = patternSize(size, false, false);
          PatternRecord.expand(this.data, offset, size, null, null);
        } else if (mode == MODE_COMPACT) {
          length = CompactRecord.parse(this.data, offset, q).length();
        } else {
          length = recordSize(mode, size);
        }
        if (mode != MODE_SKIP && (offset != this.cursor || length > this.data.length - offset)) {
          throw new Mcv2Exception("Invalid leaf payload");
        }
        this.cursor += length;
      }
      if (this.cursor != this.data.length) {
        throw new Mcv2Exception("Noncanonical payload length");
      }
    }
  }

  private static boolean isTemporalInKeyframe(final int mode, final int flags) {
    return (
      mode == MODE_MOTION ||
      mode == MODE_COMPACT ||
      mode == MODE_IMMEDIATE_MOTION ||
      isResidual(mode) ||
      (mode == MODE_SKIP && (flags & DEFAULT_SOLID) == 0)
    );
  }

  /** The derived form: level-ordered descriptors with no offsets, every address recovered by bounded walks. */
  private static final class DerivedParser {

    private final byte[] data;

    private final Mcv2Frame.Header header;

    private final int roots;

    private final int columns;

    private final int start;

    private final int total;

    private final int flags;

    private int base;

    private byte@Nullable[] symbols;

    private int width;

    private DerivedParser(final byte[] data, final Mcv2Frame.Header header, final int roots, final int columns) {
      this.data = data;
      this.header = header;
      this.roots = roots;
      this.columns = columns;
      this.start = header.payloadStart();
      this.total = data.length;
      this.flags = header.flags();
      // until a symbol table says otherwise, a descriptor is a byte
      this.width = Byte.SIZE;
    }

    Mcv2Frame parse() throws Mcv2Exception {
      final int groups = (this.roots + GROUP_ROOTS - 1) / GROUP_ROOTS;
      final int stored = (groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS;
      final int head = HEADER_BYTES + (groups + stored) * WORD_BYTES;
      if (head + BLOCK_SIZES * Short.BYTES > this.start) {
        throw new Mcv2Exception("Short derived index");
      }
      final int[] levels = new int[BLOCK_SIZES];
      for (int level = 0; level < BLOCK_SIZES; level++) {
        levels[level] = u16(this.data, head + level * Short.BYTES);
      }
      this.base = head + BLOCK_SIZES * Short.BYTES;
      final int descriptors = levels[0] + levels[1] + levels[2];
      if ((this.flags & PACKED_SYMBOLS) != 0) {
        this.readSymbolTable();
      }
      final Walk walk = this.readWalk(this.base + (descriptors * this.width + Byte.SIZE - 1) / Byte.SIZE, descriptors);
      final Tables tables = this.readTableSizes(walk);
      final int wordBytes = tables.wordBytes();
      final int endpointBytes = tables.pairCount * tables.pairEntry;
      final byte[] book = tables.pairCount > 0 ? this.readEndpointTable(tables.pairCount, tables.pairEntry, wordBytes) : null;
      final byte[][] books = (this.flags & SELECTOR_TABLE) != 0
        ? this.readSelectorTables(tables.wordCounts, endpointBytes, wordBytes)
        : null;
      final int[] present = this.readDirectory(groups, levels[0]);
      final byte[] modes = new byte[descriptors];
      final byte[] quantizers = new byte[descriptors];
      for (int i = 0; i < descriptors; i++) {
        final int symbol = this.descriptor(i);
        modes[i] = (byte) (symbol & MODE_MASK);
        quantizers[i] = (byte) (symbol >> SYMBOL_QUANTIZER_SHIFT);
      }
      final int split = levels[0] + levels[1];
      if (QUARTERS * countSplits(modes, 0, levels[0]) != levels[1] || QUARTERS * countSplits(modes, levels[0], split) != levels[2]) {
        throw new Mcv2Exception("Level counts disagree with the split descriptors");
      }
      if (countSplits(modes, split, descriptors) != 0) {
        throw new Mcv2Exception("Split below the bounded depth");
      }
      final int[] positions = this.positions(present, modes, levels[0], split, descriptors);
      final Leaves leaves = new Leaves();
      this.readLeaves(walk, modes, quantizers, positions, book, books, leaves, this.total - wordBytes - endpointBytes);
      this.addUnlistedRoots(present, levels[0], leaves);
      return new Mcv2Frame(this.data, this.header, leaves.toArray(), book, books);
    }

    /** The walk checkpoints' plane: where it starts, how many there are, and the delta widths of the two-level form. */
    private record Walk(int base, int points, boolean twoLevel, int cursorBits, int splitsBits) {}

    /** The sizes the index gives the endpoint table and the selector tables of the three block sizes. */
    private static final class Tables {

      private final int pairCount;

      private final int pairEntry;

      private final int[] wordCounts;

      private Tables(final int pairCount, final int pairEntry, final int[] wordCounts) {
        this.pairCount = pairCount;
        this.pairEntry = pairEntry;
        this.wordCounts = wordCounts;
      }

      private int wordBytes() {
        int bytes = 0;
        for (int i = 0; i < BLOCK_SIZES; i++) {
          bytes += this.wordCounts[i] * selectorEntry(i);
        }
        return bytes;
      }
    }

    private Walk readWalk(final int walkBase, final int descriptors) throws Mcv2Exception {
      final int points = (descriptors + WALK_SPAN - 1) / WALK_SPAN;
      if ((this.flags & TWO_LEVEL_WALK) == 0) {
        return new Walk(walkBase, points, false, 0, 0);
      }
      if (walkBase + 2 > this.start) {
        throw new Mcv2Exception("Short walk widths");
      }
      final int cursorBits = this.data[walkBase] & 0xFF;
      final int splitsBits = this.data[walkBase + 1] & 0xFF;
      if (cursorBits < 1 || cursorBits > Short.SIZE || splitsBits < 1 || splitsBits > Short.SIZE || cursorBits + splitsBits > DELTA_BITS) {
        throw new Mcv2Exception("Invalid walk delta widths");
      }
      return new Walk(walkBase, points, true, cursorBits, splitsBits);
    }

    /** Reads the table sizes after the walk plane, and checks that the index ends exactly where the payload starts. */
    private Tables readTableSizes(final Walk walk) throws Mcv2Exception {
      int region = walkBytes(walk.points(), walk.twoLevel());
      final boolean endpointTable = (this.flags & ENDPOINT_TABLE) != 0;
      int pairCount = 0;
      if (endpointTable) {
        final int at = walk.base() + region;
        if (at >= this.start) {
          throw new Mcv2Exception("Short endpoint table");
        }
        pairCount = this.data[at] & 0xFF;
        if (pairCount < 1) {
          throw new Mcv2Exception("Invalid endpoint table size");
        }
      }
      final int[] wordCounts = new int[BLOCK_SIZES];
      final boolean selectorTable = (this.flags & SELECTOR_TABLE) != 0;
      if (selectorTable) {
        final int at = walk.base() + region + (endpointTable ? 1 : 0);
        if (at + BLOCK_SIZES > this.start) {
          throw new Mcv2Exception("Short selector table sizes");
        }
        for (int i = 0; i < BLOCK_SIZES; i++) {
          wordCounts[i] = this.data[at + i] & 0xFF;
          final boolean flagged = (this.flags & (SELECTOR_TABLE_8 << i)) != 0;
          if ((wordCounts[i] != 0) != flagged) {
            throw new Mcv2Exception("Selector table size contradicts its flag");
          }
        }
      }
      final boolean coarseEndpoints = (this.flags & ENDPOINT_565) != 0;
      if (endpointTable) {
        region += 1;
      }
      if (selectorTable) {
        region += BLOCK_SIZES;
      }
      if (coarseEndpoints && !endpointTable) {
        throw new Mcv2Exception("565 endpoints without an endpoint table");
      }
      if (walk.base() + region != this.start) {
        throw new Mcv2Exception("Noncanonical derived index length");
      }
      return new Tables(pairCount, coarseEndpoints ? ENDPOINT_565_PAIR_BYTES : ENDPOINT_PAIR_BYTES, wordCounts);
    }

    /** Every descriptor's block: the listed roots first, then the quarters of every split, level by level. */
    private int[] positions(final int[] present, final byte[] modes, final int roots, final int splittable, final int descriptors) {
      final int[] positions = new int[descriptors * POSITION_INTS];
      for (int index = 0; index < roots; index++) {
        final int root = present[index];
        this.position(positions, index, (root % this.columns) * ROOT_SIZE, (root / this.columns) * ROOT_SIZE, ROOT_SIZE);
      }
      int child = roots;
      for (int index = 0; index < splittable; index++) {
        if (modes[index] != MODE_SPLIT) {
          continue;
        }
        final int x = positions[index * POSITION_INTS];
        final int y = positions[index * POSITION_INTS + 1];
        final int half = positions[index * POSITION_INTS + 2] / 2;
        for (int corner = 0; corner < QUARTERS; corner++) {
          this.position(positions, child++, x + (corner % 2) * half, y + (corner / 2) * half, half);
        }
      }
      return positions;
    }

    private void position(final int[] positions, final int index, final int x, final int y, final int size) {
      positions[index * POSITION_INTS] = x;
      positions[index * POSITION_INTS + 1] = y;
      positions[index * POSITION_INTS + 2] = size;
    }

    /**
     * Walks the descriptors in order, checking every walk checkpoint against the payload cursor and the splits before
     * it, validates and lists every leaf, and checks that the records end where the tables begin.
     */
    private void readLeaves(
      final Walk walk,
      final byte[] modes,
      final byte[] quantizers,
      final int[] positions,
      final byte@Nullable[] book,
      final byte@Nullable[][] books,
      final Leaves leaves,
      final int payloadEnd
    ) throws Mcv2Exception {
      final int[] pairs = new int[walk.points() * 2];
      int splits = 0;
      int cursor = 0;
      for (int index = 0; index < modes.length; index++) {
        final int mode = modes[index];
        if (index % WALK_SPAN == 0) {
          final int point = index / WALK_SPAN;
          final long entry = this.walkEntry(walk, point);
          if (entry >>> Integer.SIZE != cursor || (entry & WORD_MASK) != splits) {
            throw new Mcv2Exception("Noncanonical walk checkpoint");
          }
          pairs[point * 2] = cursor;
          pairs[point * 2 + 1] = splits;
        }
        if (mode == MODE_SPLIT) {
          splits++;
          continue;
        }
        final int size = positions[index * POSITION_INTS + 2];
        final byte[] words = books == null ? null : books[sizeIndex(size)];
        final int offset = this.start + cursor;
        final int length = this.validateLeaf(mode, quantizers[index], size, offset, book, words);
        final int x = positions[index * POSITION_INTS];
        final int y = positions[index * POSITION_INTS + 1];
        leaves.add(x, y, size, mode, quantizers[index], mode == MODE_SKIP ? 0 : offset);
        cursor += length;
        if (this.start + (long) cursor > this.total) {
          throw new Mcv2Exception("Invalid leaf payload");
        }
      }
      if (this.start + cursor != payloadEnd) {
        throw new Mcv2Exception("Noncanonical payload length");
      }
      if (walk.twoLevel() && !minimalWidths(pairs, walk.points(), walk.cursorBits(), walk.splitsBits())) {
        throw new Mcv2Exception("Walk delta widths are not minimal");
      }
    }

    /** Adds the roots the directory leaves out, which SKIP, or a keyframe's default colour fills. */
    private void addUnlistedRoots(final int[] present, final int listedCount, final Leaves leaves) throws Mcv2Exception {
      final boolean[] listed = new boolean[this.roots];
      for (int i = 0; i < listedCount; i++) {
        listed[present[i]] = true;
      }
      for (int root = 0; root < this.roots; root++) {
        if (!listed[root]) {
          if ((this.flags & KEYFRAME) != 0 && (this.flags & DEFAULT_SOLID) == 0) {
            throw new Mcv2Exception("Temporal keyframe leaf");
          }
          leaves.add((root % this.columns) * ROOT_SIZE, (root / this.columns) * ROOT_SIZE, ROOT_SIZE, MODE_SKIP, 0, 0);
        }
      }
    }

    private void readSymbolTable() throws Mcv2Exception {
      if (this.base >= this.start) {
        throw new Mcv2Exception("Short symbol table");
      }
      final int size = this.data[this.base] & 0xFF;
      if (size < 1 || size > MAX_SYMBOLS || this.base + 1 + size > this.total) {
        throw new Mcv2Exception("Invalid symbol table size");
      }
      final byte[] table = Arrays.copyOfRange(this.data, this.base + 1, this.base + 1 + size);
      for (int i = 1; i < size; i++) {
        if ((table[i] & 0xFF) <= (table[i - 1] & 0xFF)) {
          throw new Mcv2Exception("Noncanonical symbol table");
        }
      }
      this.symbols = table;
      this.width = symbolWidth(size);
      this.base += 1 + size;
    }

    private int descriptor(final int index) throws Mcv2Exception {
      final byte[] table = this.symbols;
      if (table == null) {
        return this.data[this.base + index] & 0xFF;
      }
      final int position = index * this.width;
      final int at = this.base + position / Byte.SIZE;
      // two bytes always cover a symbol; the second can lie past the plane, in the walk region that always follows it
      final int word = (this.data[at] & 0xFF) | ((this.data[at + 1] & 0xFF) << Byte.SIZE);
      final int symbol = (word >> (position % Byte.SIZE)) & ((1 << this.width) - 1);
      if (symbol >= table.length) {
        throw new Mcv2Exception("Symbol index outside the table");
      }
      return table[symbol] & 0xFF;
    }

    private static int countSplits(final byte[] modes, final int from, final int to) {
      int splits = 0;
      for (int i = from; i < to; i++) {
        if (modes[i] == MODE_SPLIT) {
          splits++;
        }
      }
      return splits;
    }

    private byte[] readEndpointTable(final int pairCount, final int pairEntry, final int wordBytes) throws Mcv2Exception {
      final int endpointBytes = pairCount * pairEntry;
      final int pairsAt = this.total - endpointBytes;
      if (pairsAt - wordBytes < this.start) {
        throw new Mcv2Exception("Truncated endpoint table");
      }
      final byte[] book = new byte[pairCount * ENDPOINT_PAIR_BYTES];
      for (int i = 0; i < pairCount; i++) {
        if (pairEntry == ENDPOINT_565_PAIR_BYTES) {
          final int at = pairsAt + i * ENDPOINT_565_PAIR_BYTES;
          putRgb(book, i * ENDPOINT_PAIR_BYTES, unpack565(this.data[at], this.data[at + 1]));
          putRgb(book, i * ENDPOINT_PAIR_BYTES + CHANNELS, unpack565(this.data[at + RGB565_BYTES], this.data[at + RGB565_BYTES + 1]));
        } else {
          System.arraycopy(this.data, pairsAt + i * ENDPOINT_PAIR_BYTES, book, i * ENDPOINT_PAIR_BYTES, ENDPOINT_PAIR_BYTES);
        }
      }
      if (distinctEntries(book, ENDPOINT_PAIR_BYTES) != pairCount) {
        throw new Mcv2Exception("Noncanonical endpoint table");
      }
      return book;
    }

    private static void putRgb(final byte[] target, final int at, final int rgb) {
      target[at] = (byte) (rgb >> (2 * Byte.SIZE));
      target[at + 1] = (byte) (rgb >> Byte.SIZE);
      target[at + 2] = (byte) rgb;
    }

    private static int distinctEntries(final byte[] table, final int entry) {
      final Set<String> seen = new HashSet<>();
      for (int i = 0; i + entry <= table.length; i += entry) {
        seen.add(Arrays.toString(Arrays.copyOfRange(table, i, i + entry)));
      }
      return seen.size();
    }

    private byte[][] readSelectorTables(final int[] wordCounts, final int endpointBytes, final int wordBytes) throws Mcv2Exception {
      int at = this.total - endpointBytes - wordBytes;
      if (at < this.start) {
        throw new Mcv2Exception("Truncated selector table");
      }
      final byte[][] books = new byte[BLOCK_SIZES][];
      for (int i = 0; i < BLOCK_SIZES; i++) {
        final int entry = selectorEntry(i);
        final int n = wordCounts[i];
        if (n != 0) {
          final byte[] chunk = Arrays.copyOfRange(this.data, at, at + n * entry);
          if (distinctEntries(chunk, entry) != n) {
            throw new Mcv2Exception("Noncanonical selector table");
          }
          books[i] = chunk;
        }
        at += n * entry;
      }
      return books;
    }

    private int[] readDirectory(final int groups, final int n0) throws Mcv2Exception {
      final int[] present = new int[this.roots];
      int seen = 0;
      for (int group = 0; group < groups; group++) {
        final long mask = u32(this.data, HEADER_BYTES + group * WORD_BYTES);
        final int valid = Math.min(GROUP_ROOTS, this.roots - group * GROUP_ROOTS);
        if (mask >>> valid != 0) {
          throw new Mcv2Exception("Bad root directory");
        }
        if (group % CHECKPOINT_GROUPS == 0) {
          final long checkpoint = u32(this.data, HEADER_BYTES + groups * WORD_BYTES + (group / CHECKPOINT_GROUPS) * WORD_BYTES);
          if (checkpoint != seen) {
            throw new Mcv2Exception("Noncanonical descriptor checkpoint");
          }
        }
        for (int bit = 0; bit < valid; bit++) {
          if (((mask >>> bit) & 1) != 0) {
            present[seen++] = group * GROUP_ROOTS + bit;
          }
        }
      }
      if (seen != n0) {
        throw new Mcv2Exception("Root count differs from the level-0 count");
      }
      return present;
    }

    /** The (payload cursor, split prefix) pair of a walk checkpoint, as the high and low halves of a long. */
    private long walkEntry(final Walk walk, final int index) throws Mcv2Exception {
      if (!walk.twoLevel()) {
        final int at = walk.base() + index * WALK_PAIR_BYTES;
        return pair(u16(this.data, at), u16(this.data, at + Short.BYTES));
      }
      // after the two delta widths: the absolute pairs of every WALK_STRIDE-th checkpoint, then the others' deltas
      final int anchor = walk.base() + 2 + (index / WALK_STRIDE) * WALK_PAIR_BYTES;
      final int baseCursor = u16(this.data, anchor);
      final int baseSplits = u16(this.data, anchor + Short.BYTES);
      if (index % WALK_STRIDE == 0) {
        return pair(baseCursor, baseSplits);
      }
      final int coarse = (walk.points() + WALK_STRIDE - 1) / WALK_STRIDE;
      final int at = walk.base() + 2 + coarse * WALK_PAIR_BYTES + (index - 1 - (index - 1) / WALK_STRIDE) * Short.BYTES;
      final int entry = u16(this.data, at);
      if (entry >>> (walk.cursorBits() + walk.splitsBits()) != 0) {
        throw new Mcv2Exception("Noncanonical walk delta padding");
      }
      return pair(baseCursor + (entry & ((1 << walk.cursorBits()) - 1)), baseSplits + (entry >>> walk.cursorBits()));
    }

    /** A walk checkpoint's payload cursor and split prefix as the high and low halves of a long. */
    private static long pair(final long cursor, final long splits) {
      return (cursor << Integer.SIZE) | splits;
    }

    private static boolean minimalWidths(final int[] pairs, final int walkpoints, final int cursorBits, final int splitsBits) {
      int cursor = 0;
      int splits = 0;
      for (int index = 0; index < walkpoints; index++) {
        final int anchor = index - (index % WALK_STRIDE);
        cursor = Math.max(cursor, pairs[index * 2] - pairs[anchor * 2]);
        splits = Math.max(splits, pairs[index * 2 + 1] - pairs[anchor * 2 + 1]);
      }
      return bitLength(cursor) == cursorBits && bitLength(splits) == splitsBits;
    }

    private static int bitLength(final int value) {
      return Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(value));
    }

    /** Validates one leaf of the derived form and returns the length of its record. */
    private int validateLeaf(
      final int mode,
      final int q,
      final int size,
      final int offset,
      final byte@Nullable[] book,
      final byte@Nullable[] words
    ) throws Mcv2Exception {
      checkUnsupportedLeaf(mode);
      // splits never get here: the walk counts them before it validates a leaf
      if (mode > MODE_PATTERN || (q != 0 && !isResidual(mode) && mode != MODE_COMPACT)) {
        throw new Mcv2Exception("Invalid leaf descriptor");
      }
      if ((this.flags & KEYFRAME) != 0 && isTemporalInKeyframe(mode, this.flags)) {
        throw new Mcv2Exception("Temporal keyframe leaf");
      }
      if (mode == MODE_PATTERN) {
        PatternRecord.expand(this.data, offset, size, book, words);
        return patternSize(size, book != null, words != null);
      }
      if (mode == MODE_COMPACT) {
        return CompactRecord.parse(this.data, offset, q).length();
      }
      return recordSize(mode, size);
    }
  }
}
